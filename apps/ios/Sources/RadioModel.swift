import SwiftUI
import MultipeerConnectivity
import LocalAuthentication
import AudioToolbox
import UIKit
import CryptoKit

enum Screen { case lock, channel, discovery, safety, chat, settings }

enum DeliveryState { case pending, delivered, failed }

struct ChatMessage: Identifiable {
    let id = UUID()
    let mine: Bool
    let text: String
    var at = Date()                                // 자동삭제(24h) 기준 시각
    var wireId: String? = nil                      // 전달 확인용 메시지 id (소문자 16진 8자)
    var delivery: DeliveryState? = nil             // 내 메시지만 사용
}

/// 암호문 안쪽 평문 포맷 (PRD F-T6, Android 와 동일).
///   "M" + id(소문자 16진 8자) + 본문 : 메시지
///   "A" + id                        : 전달 확인(ACK) — 반드시 세션 암호화로 보낸다
///   그 밖                            : 구버전 평문 (그대로 표시, ACK 없음)
enum InnerFrame {
    case message(id: String, text: String)
    case ack(id: String)
    case legacy(String)

    static func newId() -> String { String(format: "%08x", UInt32.random(in: .min ... .max)) }

    private static func isHexId(_ s: Substring) -> Bool {
        s.count == 8 && s.allSatisfy { "0123456789abcdef".contains($0) }
    }

    static func parse(_ t: String) -> InnerFrame {
        guard let tag = t.first else { return .legacy(t) }
        let rest = t.dropFirst()
        let id = rest.prefix(8)
        switch tag {
        case "M" where isHexId(id): return .message(id: String(id), text: String(rest.dropFirst(8)))
        case "A" where isHexId(id) && rest.count == 8: return .ack(id: String(id))
        default: return .legacy(t)
        }
    }
}

/// 안전번호를 확인한 상대 기록 (PRD F-T3). 키 자체가 아니라 (내 키|상대 키) 의 SHA-256 만 저장한다.
enum VerifiedPeers {
    private static let key = "verified_pairs"
    private static let d = UserDefaults.standard

    static func hash(mine: String, theirs: String) -> String {
        SHA256.hash(data: Data((mine + "|" + theirs).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    static func contains(mine: String, theirs: String) -> Bool {
        (d.stringArray(forKey: key) ?? []).contains(hash(mine: mine, theirs: theirs))
    }
    static func add(mine: String, theirs: String) {
        var a = d.stringArray(forKey: key) ?? []
        let h = hash(mine: mine, theirs: theirs)
        if !a.contains(h) { a.append(h); d.set(a, forKey: key) }
    }
    static func clear() { d.removeObject(forKey: key) }
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
    @Published var verifiedPeer = false            // 이 상대의 안전번호를 (이전에 또는 방금) 확인함
    static let ackTimeout: TimeInterval = 10
    private var seenIds = Set<String>()            // 세션별 수신 메시지 id (중복 제거)

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
        session = nil; theirKey = ""; seenIds = []; verifiedPeer = false
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
        session = nil; theirKey = ""; seenIds = []; verifiedPeer = false
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
        discovered = []; session = nil; seenIds = []; linked = false; safetyConfirmed = false
        verifiedPeer = false
        screen = .discovery
    }

    func confirmSafety() {
        if let me, !theirKey.isEmpty { VerifiedPeers.add(mine: me.publicKey(), theirs: theirKey) }
        safetyConfirmed = true; verifiedPeer = true
        screen = .chat
    }
    func rejectSafety() {
        transport.stop(); session = nil; seenIds = []; verifiedPeer = false
        screen = .discovery
    }

    /// 설정: 검증한 상대 기록 전체 삭제. 다음 연결부터 안전번호를 다시 확인한다.
    /// 신원키 재생성/초기화 경로를 만들 때도 반드시 호출할 것.
    func clearVerifiedPeers() { VerifiedPeers.clear() }

    func send(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let id = InnerFrame.newId()
        guard !t.isEmpty, let sess = session,
              let wire = try? sess.encrypt(plaintext: Data(("M" + id + t).utf8)) else { return }
        transport.send(Data(wire.utf8))
        append(ChatMessage(mine: true, text: t, wireId: id, delivery: .pending))
        // 10초 안에 ACK 가 없으면 미전달
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.ackTimeout * 1_000_000_000))
            self?.setDelivery(id, onlyIf: .pending, to: .failed)
        }
    }

    private func setDelivery(_ id: String, onlyIf from: DeliveryState? = nil, to: DeliveryState) {
        guard let i = messages.firstIndex(where: { $0.mine && $0.wireId == id }) else { return }
        if let from, messages[i].delivery != from { return }
        messages[i].delivery = to
    }

    /// 복호화된 안쪽 평문 처리: 메시지 표시+ACK / ACK 반영 / 구버전 평문 표시.
    private func receive(_ plain: Data) {
        guard let t = String(data: plain, encoding: .utf8) else { return }
        switch InnerFrame.parse(t) {
        case .message(let id, let text):
            guard seenIds.insert(id).inserted else { return }      // 중복 수신 무시
            append(ChatMessage(mine: false, text: text, wireId: id))
            if let sess = session, let wire = try? sess.encrypt(plaintext: Data(("A" + id).utf8)) {
                transport.send(Data(wire.utf8))                     // ACK 도 세션 암호화
            }
        case .ack(let id):
            setDelivery(id, to: .delivered)                         // 이미 미전달이어도 전달로
        case .legacy(let text):
            append(ChatMessage(mine: false, text: text))
        }
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
            if !self.theirKey.isEmpty,
               VerifiedPeers.contains(mine: me.publicKey(), theirs: self.theirKey) {
                // 이전에 안전번호를 확인한 상대 — 재확인 생략 (PRD F-T3)
                self.safetyConfirmed = true; self.verifiedPeer = true
                self.screen = .chat
            } else if !self.safetyConfirmed {
                self.verifiedPeer = false
                self.screen = .safety
            }
        }
    }

    nonisolated func onFrameReceived(_ peer: MCPeerID, frame: Data) {
        Task { @MainActor in self.handle(frame) }
    }

    nonisolated func onDisconnected(_ peer: MCPeerID) {
        Task { @MainActor in
            self.linked = false
            for i in self.messages.indices where self.messages[i].delivery == .pending {
                self.messages[i].delivery = .failed
            }
        }
    }

    private func handle(_ frame: Data) {
        guard let me, let s = String(data: frame, encoding: .utf8), let tag = s.first else { return }
        let body = String(s.dropFirst())
        switch tag {
        case "K":   // initiator: 상대 프리키로 세션 열기 (첫 send가 PreKey wire가 됨)
            session = try? me.openSession(theirIdB64: theirKey, theirPrekeyB64: body)
        case "P":   // PreKey: 기존 세션으로 먼저 복호화, 실패하거나 세션이 없으면 새 세션 수락 + 첫 평문
            if let sess = session, let d = try? sess.decrypt(wire: s) {
                receive(d)
            } else if let sess = try? me.acceptSession(theirIdB64: theirKey, wire: s) {
                session = sess; seenIds = []
                if let d = sess.takeFirstMessage() { receive(d) }
            }
        case "N":
            if let sess = session, let d = try? sess.decrypt(wire: s) { receive(d) }
        default: break
        }
    }
}
