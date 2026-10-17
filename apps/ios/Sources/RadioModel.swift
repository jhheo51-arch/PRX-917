import SwiftUI
import MultipeerConnectivity
import LocalAuthentication

enum Screen { case lock, channel, discovery, safety, chat, settings }

struct ChatMessage: Identifiable {
    let id = UUID()
    let mine: Bool
    let text: String
}

/// crypto_core(Identity/SecureSession) + MultipeerTransport 를 잇는 앱 상태.
/// 전송 콜백은 백그라운드 스레드일 수 있어 메인 액터로 넘긴다.
@MainActor
final class RadioModel: ObservableObject, RadioTransportListener {
    @Published var screen: Screen = .lock
    @Published var channel = 3
    @Published var discovered: [String] = []
    @Published var peerName = ""
    @Published var safetyNumber = ""
    @Published var safetyConfirmed = false
    @Published var messages: [ChatMessage] = []
    @Published var linked = false
    @Published var unlocking = false
    @Published var unlockError: String?

    let callsign = "DELTA-6"
    let channels = [(1,"917.1"),(2,"918.3"),(3,"919.5"),(4,"920.7"),
                    (5,"921.3"),(6,"922.1"),(7,"922.9"),(8,"923.5")]

    private var me: Identity?                      // 잠금 해제(생체/기기 암호 인증) 후에만 Keychain 에서 로드
    private var transport: MultipeerTransport!
    private var session: SecureSession?
    private var theirKey = ""

    init() {
        transport = MultipeerTransport(displayName: callsign, listener: self)
    }

    var myPublicKey: String { me?.publicKey() ?? "" }

    /// 생체(미등록 시 기기 암호) 인증 → 인증된 LAContext 로 Keychain 에서 신원 로드 → 성공 시에만 진입.
    func unlock() async {
        guard !unlocking else { return }
        unlocking = true; unlockError = nil
        defer { unlocking = false }

        let ctx = LAContext()
        ctx.localizedCancelTitle = "취소"
        let reason = "PRX-917 신원키 잠금 해제"
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            unlockError = "기기 암호가 설정되어 있지 않아 신원키를 보호할 수 없습니다. 설정에서 암호를 지정하세요."
            return
        }
        do {
            _ = try await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            me = try IdentityStore.loadOrCreate(context: ctx)
            screen = .channel
        } catch let e as LAError where e.code == .userCancel || e.code == .appCancel || e.code == .systemCancel {
            unlockError = "인증이 취소되었습니다."
        } catch {
            unlockError = "잠금 해제 실패: \(error.localizedDescription)"
        }
    }

    /// 잠금: 전송 중단, 신원·세션·대화를 메모리에서 버리고 잠금 화면으로.
    func lock(reason: String? = nil) {
        transport.stop()
        session = nil; theirKey = ""
        discovered = []; messages = []; linked = false; safetyConfirmed = false
        safetyNumber = ""; peerName = ""
        me = nil
        backgroundAt = nil
        unlockError = reason
        screen = .lock
    }

    // MARK: - 백그라운드 재잠금 (PRD F-K6)
    static let relockAfter: TimeInterval = 30
    private var backgroundAt: Date?

    var isUnlocked: Bool { me != nil }

    func appDidEnterBackground() {
        if me != nil { backgroundAt = Date() }
    }

    func appWillEnterForeground() {
        guard me != nil, let t = backgroundAt else { return }
        backgroundAt = nil
        let away = Date().timeIntervalSince(t)
        // 시계를 되돌린 경우(음수)도 잠근다
        if away >= Self.relockAfter || away < 0 {
            lock(reason: "\(Int(Self.relockAfter))초 이상 자리를 비워 잠겼습니다.")
        }
    }

    func selectChannel(_ n: Int) {
        channel = n
        transport.stop()
        transport.setChannel(n)
        guard let me else { return }
        transport.start(myIdentityPubB64: me.publicKey())
        discovered = []; session = nil; linked = false; safetyConfirmed = false
        screen = .discovery
    }

    func confirmSafety() { safetyConfirmed = true; screen = .chat }
    func rejectSafety() { transport.stop(); session = nil; screen = .discovery }

    func send(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let sess = session,
              let wire = try? sess.encrypt(plaintext: Data(t.utf8)) else { return }
        transport.send(Data(wire.utf8))
        messages.append(ChatMessage(mine: true, text: t))
    }

    // MARK: - RadioTransportListener (전송 → 메인 액터)
    nonisolated func onPeerFound(_ peer: MCPeerID, theirIdentityPubB64: String) {
        Task { @MainActor in
            guard self.me != nil else { return }   // 잠긴 뒤 도착한 콜백 무시
            self.theirKey = theirIdentityPubB64
            self.peerName = peer.displayName
            if !theirIdentityPubB64.isEmpty,
               let sn = try? self.me?.safetyNumberWith(theirPubB64: theirIdentityPubB64) {
                self.safetyNumber = sn
            }
            if !self.discovered.contains(peer.displayName) {
                self.discovered.append(peer.displayName)
            }
        }
    }

    nonisolated func onConnected(_ peer: MCPeerID) {
        Task { @MainActor in
            self.linked = true
            // 역할 결정(양측 자동 연결 대비): 공개키가 큰 쪽이 프리키를 먼저 공개(responder)
            guard let me = self.me else { return }
            let amResponder = me.publicKey() > self.theirKey
            if amResponder, let pk = try? me.generatePrekey() {
                self.transport.send(Data(("K" + pk).utf8))
            }
            if !self.safetyConfirmed { self.screen = .safety }
        }
    }

    nonisolated func onFrameReceived(_ peer: MCPeerID, frame: Data) {
        Task { @MainActor in self.handle(frame) }
    }

    nonisolated func onDisconnected(_ peer: MCPeerID) {
        Task { @MainActor in self.linked = false }
    }

    private func handle(_ frame: Data) {
        guard let me, let s = String(data: frame, encoding: .utf8), let tag = s.first else { return }
        let body = String(s.dropFirst())
        switch tag {
        case "K":   // initiator: 상대 프리키로 세션 열기 (첫 send가 PreKey wire가 됨)
            session = try? me.openSession(theirIdB64: theirKey, theirPrekeyB64: body)
        case "P":   // responder: 세션 수락 + 첫 평문
            if let sess = try? me.acceptSession(theirIdB64: theirKey, wire: s) {
                session = sess
                if let d = sess.takeFirstMessage(), let t = String(data: d, encoding: .utf8) {
                    messages.append(ChatMessage(mine: false, text: t))
                }
            }
        case "N":
            if let sess = session, let d = try? sess.decrypt(wire: s),
               let t = String(data: d, encoding: .utf8) {
                messages.append(ChatMessage(mine: false, text: t))
            }
        default: break
        }
    }
}
