import SwiftUI
import MultipeerConnectivity

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

    let callsign = "DELTA-6"
    let channels = [(1,"917.1"),(2,"918.3"),(3,"919.5"),(4,"920.7"),
                    (5,"921.3"),(6,"922.1"),(7,"922.9"),(8,"923.5")]

    private let me = Identity.generate()           // 실제 crypto_core 신원 (데모: 실행마다 새로 생성)
    private var transport: MultipeerTransport!
    private var session: SecureSession?
    private var theirKey = ""

    init() {
        transport = MultipeerTransport(displayName: callsign, listener: self)
    }

    var myPublicKey: String { me.publicKey() }

    func unlock() { screen = .channel }

    func selectChannel(_ n: Int) {
        channel = n
        transport.stop()
        transport.setChannel(n)
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
            self.theirKey = theirIdentityPubB64
            self.peerName = peer.displayName
            if !theirIdentityPubB64.isEmpty,
               let sn = try? self.me.safetyNumberWith(theirPubB64: theirIdentityPubB64) {
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
            let amResponder = self.me.publicKey() > self.theirKey
            if amResponder, let pk = try? self.me.generatePrekey() {
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
        guard let s = String(data: frame, encoding: .utf8), let tag = s.first else { return }
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
