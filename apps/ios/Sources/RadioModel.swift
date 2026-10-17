import SwiftUI
import MultipeerConnectivity
import LocalAuthentication
import AudioToolbox
import UIKit

enum Screen { case lock, channel, discovery, safety, chat, settings }

struct ChatMessage: Identifiable {
    let id = UUID()
    let mine: Bool
    let text: String
    var at = Date()                                // 자동삭제(24h) 기준 시각
}

/// 비밀이 아닌 사용자 설정 — UserDefaults 저장 (Android SharedPreferences 와 같은 규칙).
enum AppSettings {
    private static let d = UserDefaults.standard
    private static let kCallsign = "callsign", kAutoDelete = "autoDelete", kSound = "sound"

    static let callsignPattern = "^[A-Z0-9-]{2,12}$"

    /// 첫 실행 시 "UNIT-" + 랜덤 16진 4자리를 만들어 저장.
    static var callsign: String {
        get {
            if let c = d.string(forKey: kCallsign), isValidCallsign(c) { return c }
            let c = String(format: "UNIT-%04X", Int.random(in: 0...0xFFFF))
            d.set(c, forKey: kCallsign)
            return c
        }
        set { d.set(newValue, forKey: kCallsign) }
    }
    static var autoDelete: Bool {
        get { d.object(forKey: kAutoDelete) as? Bool ?? false }
        set { d.set(newValue, forKey: kAutoDelete) }
    }
    static var sound: Bool {
        get { d.object(forKey: kSound) as? Bool ?? true }
        set { d.set(newValue, forKey: kSound) }
    }

    static func normalize(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
    static func isValidCallsign(_ c: String) -> Bool {
        c.range(of: callsignPattern, options: .regularExpression) != nil
    }
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

    @Published private(set) var callsign = AppSettings.callsign
    @Published var autoDelete = AppSettings.autoDelete {
        didSet { AppSettings.autoDelete = autoDelete; purgeExpired() }
    }
    @Published var soundOn = AppSettings.sound {
        didSet { AppSettings.sound = soundOn }
    }
    static let messageTTL: TimeInterval = 24 * 60 * 60
    private var purgeTimer: Timer?
    let channels = [(1,"917.1"),(2,"918.3"),(3,"919.5"),(4,"920.7"),
                    (5,"921.3"),(6,"922.1"),(7,"922.9"),(8,"923.5")]

    private var me: Identity?                      // 잠금 해제(생체/기기 암호 인증) 후에만 Keychain 에서 로드
    private var transport: MultipeerTransport!
    private var session: SecureSession?
    private var theirKey = ""

    init() {
        transport = MultipeerTransport(displayName: callsign, listener: self)
        // 1분마다 24h 지난 메시지 정리 (자동삭제 켜진 경우만)
        purgeTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.purgeExpired() }
        }
    }

    // MARK: - 설정

    /// 콜사인 변경. 성공 시 nil, 실패 시 오류 문구.
    /// MCPeerID 이름은 바꿀 수 없으므로 전송 계층을 새로 만들고, 현재 연결·세션은 끊는다.
    func setCallsign(_ raw: String) -> String? {
        let c = AppSettings.normalize(raw)
        guard AppSettings.isValidCallsign(c) else {
            return "영문 대문자·숫자·하이픈(-)으로 2~12자만 쓸 수 있습니다."
        }
        guard c != callsign else { return nil }
        AppSettings.callsign = c
        callsign = c
        transport.stop()
        transport = MultipeerTransport(displayName: c, listener: self)
        session = nil; theirKey = ""
        discovered = []; messages = []; linked = false; safetyConfirmed = false
        safetyNumber = ""; peerName = ""
        screen = .channel
        return nil
    }

    func goSettings() { screen = .settings }
    func goChannel() { screen = .channel }

    /// 자동삭제가 켜져 있으면 24시간 지난 메시지를 지운다.
    func purgeExpired() {
        guard autoDelete else { return }
        let cutoff = Date().addingTimeInterval(-Self.messageTTL)
        messages.removeAll { $0.at < cutoff }
    }

    private func append(_ msg: ChatMessage) {
        messages.append(msg)
        purgeExpired()
        if !msg.mine && soundOn {
            AudioServicesPlaySystemSound(1007)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
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
        append(ChatMessage(mine: true, text: t))
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
                    append(ChatMessage(mine: false, text: t))
                }
            }
        case "N":
            if let sess = session, let d = try? sess.decrypt(wire: s),
               let t = String(data: d, encoding: .utf8) {
                append(ChatMessage(mine: false, text: t))
            }
        default: break
        }
    }
}
