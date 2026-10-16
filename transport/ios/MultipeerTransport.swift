import Foundation
import MultipeerConnectivity

// ⚠️ 참고 구현(미컴파일): iOS 앱 프로젝트에 넣고 Info.plist에
//    NSLocalNetworkUsage/NSBonjourServices(_prx917-chN._tcp) 등을 설정해야 동작한다.
//    crypto_core(Identity/SecureSession)는 UniFFI 바인딩으로 임포트된다.

protocol RadioTransportListener: AnyObject {
    func onPeerFound(_ peer: MCPeerID, theirIdentityPubB64: String)
    func onConnected(_ peer: MCPeerID)
    func onFrameReceived(_ peer: MCPeerID, frame: Data)
    func onDisconnected(_ peer: MCPeerID)
}

/// MultipeerConnectivity 기반 오프그리드 1:1 전송.
final class MultipeerTransport: NSObject {
    private let myPeer: MCPeerID
    private var session: MCSession!
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?
    private var serviceType = "prx917-ch1"        // 채널 (1–15자, 소문자·숫자·-)
    private var myIdentityPubB64 = ""
    weak var listener: RadioTransportListener?

    init(displayName: String, listener: RadioTransportListener) {
        self.myPeer = MCPeerID(displayName: displayName)
        self.listener = listener
        super.init()
        session = MCSession(peer: myPeer, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self
    }

    func setChannel(_ n: Int) { serviceType = "prx917-ch\(n)" }

    func start(myIdentityPubB64: String) {
        self.myIdentityPubB64 = myIdentityPubB64
        advertiser = MCNearbyServiceAdvertiser(
            peer: myPeer, discoveryInfo: ["id": myIdentityPubB64], serviceType: serviceType)
        advertiser?.delegate = self
        advertiser?.startAdvertisingPeer()
        browser = MCNearbyServiceBrowser(peer: myPeer, serviceType: serviceType)
        browser?.delegate = self
        browser?.startBrowsingForPeers()
    }

    func stop() {
        advertiser?.stopAdvertisingPeer(); browser?.stopBrowsingForPeers()
        session.disconnect()
    }

    func send(_ frame: Data) {
        guard !session.connectedPeers.isEmpty else { return }
        try? session.send(frame, toPeers: session.connectedPeers, with: .reliable)
    }
}

extension MultipeerTransport: MCNearbyServiceBrowserDelegate {
    func browser(_ b: MCNearbyServiceBrowser, foundPeer peer: MCPeerID,
                 withDiscoveryInfo info: [String: String]?) {
        listener?.onPeerFound(peer, theirIdentityPubB64: info?["id"] ?? "")
        b.invitePeer(peer, to: session, withContext: nil, timeout: 20)
    }
    func browser(_ b: MCNearbyServiceBrowser, lostPeer peer: MCPeerID) {}
}

extension MultipeerTransport: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ a: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peer: MCPeerID,
                    withContext ctx: Data?,
                    invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(true, session)
    }
}

extension MultipeerTransport: MCSessionDelegate {
    func session(_ s: MCSession, peer: MCPeerID, didChange state: MCSessionState) {
        switch state {
        case .connected:
            advertiser?.stopAdvertisingPeer(); browser?.stopBrowsingForPeers()
            listener?.onConnected(peer)
        case .notConnected:
            listener?.onDisconnected(peer)
        default: break
        }
    }
    func session(_ s: MCSession, didReceive data: Data, fromPeer peer: MCPeerID) {
        listener?.onFrameReceived(peer, frame: data)
    }
    // 미사용 필수 델리게이트
    func session(_ s: MCSession, didReceive stream: InputStream, withName n: String, fromPeer p: MCPeerID) {}
    func session(_ s: MCSession, didStartReceivingResourceWithName n: String, fromPeer p: MCPeerID, with pr: Progress) {}
    func session(_ s: MCSession, didFinishReceivingResourceWithName n: String, fromPeer p: MCPeerID, at u: URL?, withError e: Error?) {}
}

/// 전송 ↔ crypto_core 글루. 프레임 태그 K/P/N 처리.
final class RadioLink: RadioTransportListener {
    private let me: Identity
    private let transport: MultipeerTransport
    private let onMessage: (String) -> Void
    private let onSafetyNumber: (String) -> Void

    private var peerIdentityB64 = ""
    private var session: SecureSession?
    private var isInitiator = false

    init(me: Identity, transport: MultipeerTransport,
         onMessage: @escaping (String) -> Void,
         onSafetyNumber: @escaping (String) -> Void) {
        self.me = me; self.transport = transport
        self.onMessage = onMessage; self.onSafetyNumber = onSafetyNumber
    }

    /// "호출" 버튼 → 내가 발신자.
    func callPeer() { isInitiator = true }

    func onPeerFound(_ peer: MCPeerID, theirIdentityPubB64: String) {
        guard !theirIdentityPubB64.isEmpty else { return }
        peerIdentityB64 = theirIdentityPubB64
        if let sn = try? me.safetyNumberWith(theirPubB64: theirIdentityPubB64) {
            onSafetyNumber(sn)                       // 양 화면 대조용
        }
    }

    func onConnected(_ peer: MCPeerID) {
        if !isInitiator, let pk = try? me.generatePrekey() {   // 수신자가 프리키 공개
            transport.send(Data(("K" + pk).utf8))
        }
    }

    func onFrameReceived(_ peer: MCPeerID, frame: Data) {
        guard let s = String(data: frame, encoding: .utf8), let tag = s.first else { return }
        let body = String(s.dropFirst())
        switch tag {
        case "K":                                    // 발신자: 세션 열기
            session = try? me.openSession(theirIdB64: peerIdentityB64, theirPrekeyB64: body)
        case "P":                                    // 수신자: 세션 수락 + 첫 평문
            if let sess = try? me.acceptSession(theirIdB64: peerIdentityB64, wire: s) {
                session = sess
                if let d = sess.takeFirstMessage(), let t = String(data: d, encoding: .utf8) {
                    onMessage(t)
                }
            }
        case "N":                                    // 일반 메시지
            if let sess = session, let d = try? sess.decrypt(wire: s),
               let t = String(data: d, encoding: .utf8) {
                onMessage(t)
            }
        default: break
        }
    }

    func onDisconnected(_ peer: MCPeerID) { session = nil }

    /// 사용자 입력 전송. 발신자의 첫 전송이 PreKey wire가 된다.
    func sendText(_ text: String) {
        guard let sess = session, let wire = try? sess.encrypt(plaintext: Data(text.utf8)) else { return }
        transport.send(Data(wire.utf8))
    }
}
