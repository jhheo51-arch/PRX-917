package app.prx917

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import app.prx917.transport.NearbyTransport
import app.prx917.transport.RadioTransportListener
import uniffi.crypto_core.Identity
import uniffi.crypto_core.SecureSession

enum class Screen { LOCK, CHANNEL, DISCOVERY, SAFETY, CHAT, PAIRING, SETTINGS }
data class ChatMsg(val mine: Boolean, val text: String)

/** crypto_core(Identity/SecureSession) + NearbyTransport 를 잇는 앱 상태. */
class RadioViewModel(app: Application) : AndroidViewModel(app), RadioTransportListener {

    val screen = mutableStateOf(Screen.LOCK)
    val channel = mutableStateOf(3)
    val discovered = mutableStateListOf<String>()
    val peerName = mutableStateOf("")
    val safetyNumber = mutableStateOf("")
    val linked = mutableStateOf(false)
    val messages = mutableStateListOf<ChatMsg>()
    val scannedInfo = mutableStateOf("")           // QR로 받은 상대 신원 표시

    val callsign = "DELTA-6"
    val channels = listOf(
        1 to "917.1", 2 to "918.3", 3 to "919.5", 4 to "920.7",
        5 to "921.3", 6 to "922.1", 7 to "922.9", 8 to "923.5"
    )

    // 신원은 잠금 해제(CryptoObject 인증) 후에만 로드된다. 그 전엔 null.
    private var identity: Identity? = null
    private val me: Identity get() = checkNotNull(identity) { "잠금 해제 전 신원 접근" }
    val lockMsg = mutableStateOf("")               // 잠금 화면 안내/오류
    val needsReset = mutableStateOf(false)         // Keystore 키 무효화 → 신원 재생성 필요
    private val transport = NearbyTransport(app, callsign, this)
    private var session: SecureSession? = null
    private var theirKey = ""
    private var safetyConfirmed = false

    /** 내 신원 공개키(base64) — QR 페어링에 사용. */
    fun myPublicKey(): String = me.publicKey()
    /** 화면 표시용 짧은 지문. */
    fun myFingerprint(): String {
        val k = me.publicKey()
        return "IK: " + k.take(6) + "…" + k.takeLast(4)
    }

    /** 잠금 해제 1단계: 인증에 쓸 Cipher 준비. 키가 무효화됐으면 null + 재생성 안내. */
    fun beginUnlock(): IdentityStore.Pending? = try {
        IdentityStore.begin(getApplication())
    } catch (e: IdentityStore.KeyInvalidatedException) {
        needsReset.value = true
        lockMsg.value = "생체 등록이 바뀌어 신원키가 무효화됐습니다. 신원을 재생성해야 합니다."
        null
    } catch (e: Exception) {
        lockMsg.value = "키 준비 실패: ${e.message}"
        null
    }

    /** 잠금 해제 2단계: 인증된 Cipher 로만 신원을 복원/생성. */
    fun finishUnlock(p: IdentityStore.Pending, authed: javax.crypto.Cipher) {
        try {
            identity = IdentityStore.finish(getApplication(), p, authed)
            lockMsg.value = ""
            screen.value = Screen.CHANNEL
        } catch (e: Exception) {
            lockMsg.value = "신원 복원 실패: ${e.message}"
        }
    }

    /** 무효화된 신원 삭제 — 다음 인증 때 새 신원이 생성된다. */
    fun resetIdentity() {
        IdentityStore.wipe(getApplication())
        needsReset.value = false
        lockMsg.value = "신원을 삭제했습니다. 다시 인증하면 새 신원키가 생성됩니다."
    }

    fun goChannel() { screen.value = Screen.CHANNEL }
    fun goPairing() { screen.value = Screen.PAIRING }
    fun goSettings() { screen.value = Screen.SETTINGS }

    /** 신원키 재생성 — 기존 세션·신뢰 초기화 후 새 Identity 생성. */
    fun regenerate() {
        // 새 신원 생성에도 인증이 필요하므로: 삭제 후 잠금 화면에서 재인증 → 신규 봉인
        IdentityStore.wipe(getApplication())
        lock("신원키를 삭제했습니다. 인증하면 새 신원키가 생성됩니다.")
    }

    /** 잠금: 전송 중단, 신원·세션·대화를 메모리에서 버리고 잠금 화면으로. */
    fun lock(msg: String = "") {
        transport.stop()
        session = null; theirKey = ""; safetyConfirmed = false
        discovered.clear(); messages.clear(); linked.value = false
        safetyNumber.value = ""; peerName.value = ""; scannedInfo.value = ""
        identity = null
        backgroundAt = 0L
        lockMsg.value = msg
        screen.value = Screen.LOCK
    }

    // 백그라운드 재잠금 (PRD F-K6). elapsedRealtime 은 기기 절전 시간도 포함한다.
    private var backgroundAt = 0L

    fun onAppBackground() {
        if (identity != null) backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onAppForeground() {
        if (identity == null || backgroundAt == 0L) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        backgroundAt = 0L
        if (away >= RELOCK_AFTER_MS) lock("${RELOCK_AFTER_MS / 1000}초 이상 자리를 비워 잠겼습니다.")
    }

    fun selectChannel(n: Int) {
        channel.value = n
        transport.stop(); transport.setChannel(n); transport.start(me.publicKey())
        discovered.clear(); session = null; linked.value = false; safetyConfirmed = false
        screen.value = Screen.DISCOVERY
    }

    fun confirmSafety() { safetyConfirmed = true; screen.value = Screen.CHAT }
    fun rejectSafety() { transport.stop(); session = null; screen.value = Screen.DISCOVERY }

    /** 링크 끊김 후 같은 채널로 재탐색/재연결. */
    fun reconnect() { selectChannel(channel.value) }

    /** QR로 상대 신원키를 받았을 때 — 사전 신뢰(안전번호 미리 계산). */
    fun onScannedPeer(theirPubB64: String) {
        if (theirPubB64.isBlank() || identity == null) return
        theirKey = theirPubB64
        val sn = runCatching { me.safetyNumberWith(theirPubB64) }.getOrNull()
        scannedInfo.value = "수신: IK " + theirPubB64.take(6) + "… · 안전번호 " + (sn ?: "?")
    }

    fun send(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val s = session ?: return
        transport.send(s.encrypt(t.toByteArray()).toByteArray())
        messages.add(ChatMsg(true, t))
    }

    override fun onCleared() { transport.stop() }

    companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }

    // 전송 콜백 → 메인 스레드
    // 잠긴 뒤 도착한 콜백은 버린다(신원 없음).
    private fun ui(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { if (identity != null) block() }
    }

    override fun onPeerFound(peerId: String, theirIdentityPubB64: String) = ui {
        theirKey = theirIdentityPubB64
        if (theirIdentityPubB64.isNotEmpty()) safetyNumber.value = me.safetyNumberWith(theirIdentityPubB64)
        if (!discovered.contains(peerId)) discovered.add(peerId)
    }

    override fun onConnected(peerId: String) = ui {
        linked.value = true
        peerName.value = peerId
        // 역할 결정(양측 자동연결 대비): 공개키가 큰 쪽이 프리키 먼저 공개(responder)
        if (me.publicKey() > theirKey) transport.send(("K" + me.generatePrekey()).toByteArray())
        if (!safetyConfirmed) screen.value = Screen.SAFETY
    }

    override fun onFrameReceived(peerId: String, frame: ByteArray) = ui { handle(frame) }
    override fun onDisconnected(peerId: String) = ui { linked.value = false }

    private fun handle(frame: ByteArray) {
        val s = frame.decodeToString()
        when (s.firstOrNull()) {
            'K' -> session = me.openSession(theirKey, s.substring(1))     // initiator
            'P' -> me.acceptSession(theirKey, s).also {                   // responder
                session = it
                it.takeFirstMessage()?.let { m -> messages.add(ChatMsg(false, m.decodeToString())) }
            }
            'N' -> session?.let { messages.add(ChatMsg(false, it.decrypt(s).decodeToString())) }
        }
    }
}
