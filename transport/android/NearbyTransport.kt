package app.prx917.transport

// ⚠️ 참고 구현(미컴파일): Android 앱 프로젝트에 넣고 권한(위치/주변기기/BT)과
//    수명주기를 붙여야 동작한다. crypto_core는 이미 테스트 완료.

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import uniffi.crypto_core.Identity
import uniffi.crypto_core.SecureSession

/** 전송 계층 콜백 — 세션 글루(RadioLink)가 구현. */
interface RadioTransportListener {
    fun onPeerFound(peerId: String, theirName: String, theirIdentityPubB64: String)
    fun onConnected(peerId: String)
    fun onFrameReceived(peerId: String, frame: ByteArray)
    fun onDisconnected(peerId: String)
}

/** Nearby Connections 기반 오프그리드 1:1 전송. */
class NearbyTransport(
    context: Context,
    private var myDisplayName: String,
    private val listener: RadioTransportListener,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private var serviceId = "prx917.ch1"        // 채널 = PRC-999K 주파수 선택 대체
    private var myIdentityPubB64 = ""
    private var connectedEndpoint: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val found = mutableMapOf<String, String>()   // endpointId → 상대 신원키 (연결 대기 중)
    private var attempts = 0

    fun setChannel(n: Int) { serviceId = "prx917.ch$n" }

    /** 콜사인 변경 — 다음 start() 부터 새 이름으로 광고. */
    fun setDisplayName(name: String) { myDisplayName = name }

    fun start(myIdentityPubB64: String) {
        this.myIdentityPubB64 = myIdentityPubB64
        client.startAdvertising(advName(), serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
        client.startDiscovery(serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        found.clear(); attempts = 0
        client.stopAdvertising(); client.stopDiscovery()
        connectedEndpoint?.let { client.disconnectFromEndpoint(it) }
        connectedEndpoint = null
    }

    fun send(frame: ByteArray) {
        val ep = connectedEndpoint ?: return
        client.sendPayload(ep, Payload.fromBytes(frame))
    }

    // 광고 이름에 신원키를 실어 안전번호 계산을 가능케 함: "표시이름|신원키"
    private fun advName() = "$myDisplayName|$myIdentityPubB64"

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val theirId = info.endpointName.substringAfter('|', "")
            listener.onPeerFound(endpointId, info.endpointName.substringBefore('|'), theirId)
            found[endpointId] = theirId
            // 양쪽이 동시에 요청하면 Nearby 가 충돌로 둘 다 닫는다 → 신원키가 작은 쪽만 먼저 요청.
            // 상대가 나를 못 찾는 경우를 대비해, 큰 쪽도 일정 시간 뒤까지 연결이 없으면 요청한다.
            val iRequest = theirId.isEmpty() || myIdentityPubB64 < theirId
            schedule(endpointId, if (iRequest) 0L else FALLBACK_MS)
        }
        override fun onEndpointLost(endpointId: String) { found.remove(endpointId) }
    }

    /** 연결 요청을 delayMs 뒤에 보낸다. 실패하면 백오프 후 재시도. */
    private fun schedule(endpointId: String, delayMs: Long) {
        main.postDelayed({
            if (connectedEndpoint != null || endpointId !in found) return@postDelayed
            client.requestConnection(advName(), endpointId, lifecycle)
                .addOnFailureListener { retry(endpointId) }
        }, delayMs)
    }

    private fun retry(endpointId: String) {
        if (connectedEndpoint != null || endpointId !in found || attempts >= MAX_ATTEMPTS) return
        attempts++
        schedule(endpointId, RETRY_BASE_MS + (0..RETRY_JITTER_MS).random())
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val theirId = info.endpointName.substringAfter('|', "")
            listener.onPeerFound(endpointId, info.endpointName.substringBefore('|'), theirId)
            // info.authenticationDigits 는 전송계층 확인용. 최종 권위는 우리 안전번호.
            client.acceptConnection(endpointId, payloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoint = endpointId
                main.removeCallbacksAndMessages(null)
                found.clear(); attempts = 0
                client.stopAdvertising(); client.stopDiscovery()
                listener.onConnected(endpointId)
            } else {
                retry(endpointId)                              // 거절·충돌·타임아웃 → 재시도
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            listener.onDisconnected(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { listener.onFrameReceived(endpointId, it) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {}
    }

    private companion object {
        const val FALLBACK_MS = 8_000L
        const val RETRY_BASE_MS = 1_500L
        const val RETRY_JITTER_MS = 2_000L
        const val MAX_ATTEMPTS = 10
    }
}

/**
 * 전송 ↔ crypto_core 글루. 프레임 태그(K/P/N)로 핸드셰이크와 메시지를 처리한다.
 *  K<프리키>  : 수신자가 공개한 프리키
 *  P… / N…    : crypto_core wire (PreKey / 일반)
 */
class RadioLink(
    private val me: Identity,
    private val transport: NearbyTransport,
    private val onMessage: (String) -> Unit,
    private val onSafetyNumber: (String) -> Unit,
) : RadioTransportListener {

    private var peerIdentityB64: String = ""
    private var session: SecureSession? = null
    private var isInitiator = false

    /** "호출" 버튼 → 내가 발신자. */
    fun callPeer() { isInitiator = true }

    override fun onPeerFound(peerId: String, theirName: String, theirIdentityPubB64: String) {
        if (theirIdentityPubB64.isNotEmpty()) {
            peerIdentityB64 = theirIdentityPubB64
            onSafetyNumber(me.safetyNumberWith(theirIdentityPubB64))   // 양 화면 대조용
        }
    }

    override fun onConnected(peerId: String) {
        if (!isInitiator) {                        // 수신자가 프리키 먼저 공개
            transport.send(("K" + me.generatePrekey()).toByteArray())
        }
    }

    override fun onFrameReceived(peerId: String, frame: ByteArray) {
        val s = frame.decodeToString()
        when (s.firstOrNull()) {
            'K' -> session = me.openSession(peerIdentityB64, s.substring(1))  // 발신자: 세션 열기
            'P' -> me.acceptSession(peerIdentityB64, s).also {               // 수신자: 세션 수락
                session = it
                it.takeFirstMessage()?.let { m -> onMessage(m.decodeToString()) }
            }
            'N' -> session?.let { onMessage(it.decrypt(s).decodeToString()) }
        }
    }

    override fun onDisconnected(peerId: String) { session = null }

    /** 사용자가 입력한 텍스트 전송. 발신자의 첫 전송이 PreKey wire가 된다. */
    fun sendText(text: String) {
        val sess = session ?: return
        transport.send(sess.encrypt(text.toByteArray()).toByteArray())
    }
}
