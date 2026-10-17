package app.prx917

import android.app.Application
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import java.security.MessageDigest
import kotlin.random.Random
import app.prx917.transport.NearbyTransport
import app.prx917.transport.RadioTransportListener
import uniffi.crypto_core.Identity
import uniffi.crypto_core.SecureSession

enum class Screen { LOCK, CHANNEL, DISCOVERY, SAFETY, CHAT, PAIRING, SETTINGS }
enum class Delivery { NONE, PENDING, DELIVERED, FAILED }
data class ChatMsg(
    val mine: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val id: String = "",
    val status: Delivery = Delivery.NONE,
)

/**
 * 암호문 안쪽 평문 포맷 (iOS 와 동일):
 *   "M" + id(소문자 hex 8) + text  → 메시지, 수신 측이 ACK
 *   "A" + id                        → 전달 확인. 반드시 세션으로 암호화해 보낸다(위조 방지)
 *   그 밖                            → 구버전 평문, ACK 없음
 */
private object Inner {
    private val HEX8 = Regex("^[0-9a-f]{8}$")
    sealed class Parsed {
        data class Msg(val id: String, val text: String) : Parsed()
        data class Ack(val id: String) : Parsed()
        data class Legacy(val text: String) : Parsed()
        object Hello : Parsed()
    }
    /** 세션 개시 신호. initiator 가 세션을 열자마자 보내 responder 도 곧바로 송신할 수 있게 한다. */
    const val HELLO = "S"
    fun newId() = "%08x".format(Random.nextInt())
    fun msg(id: String, text: String) = "M$id$text"
    fun ack(id: String) = "A$id"
    fun parse(s: String): Parsed {
        if (s == HELLO) return Parsed.Hello
        val id = s.drop(1).take(8)
        if (s.length >= 9 && HEX8.matches(id)) {
            if (s[0] == 'M') return Parsed.Msg(id, s.substring(9))
            if (s[0] == 'A' && s.length == 9) return Parsed.Ack(id)
        }
        return Parsed.Legacy(s)
    }
}

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

    val callsign = mutableStateOf(Settings.callsign(app))
    val autoDelete = mutableStateOf(Settings.autoDelete(app))
    val sound = mutableStateOf(Settings.sound(app))
    val channels = listOf(
        1 to "917.1", 2 to "918.3", 3 to "919.5", 4 to "920.7",
        5 to "921.3", 6 to "922.1", 7 to "922.9", 8 to "923.5"
    )

    // 신원은 잠금 해제(CryptoObject 인증) 후에만 로드된다. 그 전엔 null.
    private var identity: Identity? = null
    private val me: Identity get() = checkNotNull(identity) { "잠금 해제 전 신원 접근" }
    val lockMsg = mutableStateOf("")               // 잠금 화면 안내/오류
    val needsReset = mutableStateOf(false)         // Keystore 키 무효화 → 신원 재생성 필요
    private val transport = NearbyTransport(app, callsign.value, this)
    private val peerNames = mutableMapOf<String, String>()   // endpointId → 상대 콜사인
    private var session: SecureSession? = null
    private var theirKey = ""
    private var safetyConfirmed = false
    val verifiedPeer = mutableStateOf(false)       // 이 상대와 안전번호를 확인한 적 있음
    private val seenIds = mutableSetOf<String>()   // 세션 내 중복 수신 제거

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
        Settings.clearVerifiedPairs(getApplication())   // 새 신원이면 모든 상대와 재확인
        needsReset.value = false
        lockMsg.value = "신원을 삭제했습니다. 다시 인증하면 새 신원키가 생성됩니다."
    }

    fun goChannel() { screen.value = Screen.CHANNEL }

    /** 콜사인 변경. 오류 메시지를 돌려주고, 성공이면 null. 진행 중인 연결은 끊는다. */
    fun changeCallsign(raw: String): String? {
        val v = Settings.normalizeCallsign(raw) ?: return "영문 대문자·숫자·하이픈(-) 2~12자로 입력하세요."
        if (v == callsign.value) return null
        Settings.setCallsign(getApplication(), v)
        callsign.value = v
        transport.stop()
        transport.setDisplayName(v)
        session = null; theirKey = ""; safetyConfirmed = false; verifiedPeer.value = false
        discovered.clear(); peerNames.clear(); linked.value = false
        safetyNumber.value = ""; peerName.value = ""
        return null
    }

    fun setAutoDelete(on: Boolean) {
        Settings.setAutoDelete(getApplication(), on)
        autoDelete.value = on
        purgeOld()
    }

    fun setSound(on: Boolean) {
        Settings.setSound(getApplication(), on)
        sound.value = on
    }

    // 자동 삭제: 24시간 지난 메시지 제거. 1분마다 + 메시지 추가 때 검사.
    private val main = Handler(Looper.getMainLooper())
    private val purgeTick = object : Runnable {
        override fun run() { purgeOld(); main.postDelayed(this, 60_000L) }
    }
    init { main.postDelayed(purgeTick, 60_000L) }

    private fun purgeOld() {
        if (!autoDelete.value) return
        val cutoff = System.currentTimeMillis() - AUTO_DELETE_AFTER_MS
        messages.removeAll { it.at < cutoff }
    }

    private fun addMessage(m: ChatMsg) {
        messages.add(m)
        purgeOld()
        if (!m.mine && sound.value) playReceiveSound()
    }

    private fun playReceiveSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(getApplication(), uri)?.play()
        }
    }
    fun goPairing() { screen.value = Screen.PAIRING }
    fun goSettings() { screen.value = Screen.SETTINGS }

    /** 신원키 재생성 — 기존 세션·신뢰 초기화 후 새 Identity 생성. */
    fun regenerate() {
        // 새 신원 생성에도 인증이 필요하므로: 삭제 후 잠금 화면에서 재인증 → 신규 봉인
        IdentityStore.wipe(getApplication())
        Settings.clearVerifiedPairs(getApplication())   // 새 신원이면 모든 상대와 재확인
        lock("신원키를 삭제했습니다. 인증하면 새 신원키가 생성됩니다.")
    }

    /** 잠금: 전송 중단, 신원·세션·대화를 메모리에서 버리고 잠금 화면으로. */
    fun lock(msg: String = "") {
        transport.stop()
        session = null; theirKey = ""; safetyConfirmed = false
        discovered.clear(); peerNames.clear(); messages.clear(); linked.value = false
        seenIds.clear(); verifiedPeer.value = false
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
        seenIds.clear(); verifiedPeer.value = false
        screen.value = Screen.DISCOVERY
    }

    fun confirmSafety() {
        safetyConfirmed = true
        verifiedPeer.value = true
        if (theirKey.isNotEmpty()) Settings.addVerifiedPair(getApplication(), pairHash(theirKey))
        screen.value = Screen.CHAT
    }

    /** SHA-256(내 공개키|상대 공개키). 내 신원이 바뀌면 자동으로 무효가 된다. */
    private fun pairHash(their: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${me.publicKey()}|$their".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun clearVerified() = Settings.clearVerifiedPairs(getApplication())
    fun rejectSafety() { transport.stop(); session = null; verifiedPeer.value = false; screen.value = Screen.DISCOVERY }

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
        val id = Inner.newId()
        val s = session?.takeIf { linked.value } ?: run {
            // 링크 끊김 또는 세션 수립 전(연결 직후 몇 초) — 조용히 버리지 않고 즉시 미전달로 표시
            addMessage(ChatMsg(true, t, id = id, status = Delivery.FAILED)); return
        }
        transport.send(s.encrypt(Inner.msg(id, t).toByteArray()).toByteArray())
        addMessage(ChatMsg(true, t, id = id, status = Delivery.PENDING))
        // 10초 안에 ACK 가 없으면 미전달
        main.postDelayed({ setStatus(id, Delivery.FAILED, onlyIf = Delivery.PENDING) }, ACK_TIMEOUT_MS)
    }

    private fun setStatus(id: String, to: Delivery, onlyIf: Delivery? = null) {
        val i = messages.indexOfFirst { it.mine && it.id == id }
        if (i < 0) return
        if (onlyIf != null && messages[i].status != onlyIf) return
        messages[i] = messages[i].copy(status = to)
    }

    private fun failPending() {
        for (i in messages.indices) {
            if (messages[i].status == Delivery.PENDING) messages[i] = messages[i].copy(status = Delivery.FAILED)
        }
    }

    override fun onCleared() { main.removeCallbacks(purgeTick); transport.stop() }

    companion object {
        const val RELOCK_AFTER_MS = 30_000L
        const val AUTO_DELETE_AFTER_MS = 24 * 60 * 60 * 1000L
        const val ACK_TIMEOUT_MS = 10_000L
    }

    // 전송 콜백 → 메인 스레드
    // 잠긴 뒤 도착한 콜백은 버린다(신원 없음).
    private fun ui(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { if (identity != null) block() }
    }

    override fun onPeerFound(peerId: String, theirName: String, theirIdentityPubB64: String) = ui {
        theirKey = theirIdentityPubB64
        if (theirIdentityPubB64.isNotEmpty()) safetyNumber.value = me.safetyNumberWith(theirIdentityPubB64)
        val name = theirName.ifBlank { peerId }
        peerNames[peerId] = name
        if (!discovered.contains(name)) discovered.add(name)
    }

    override fun onConnected(peerId: String) = ui {
        linked.value = true
        peerName.value = peerNames[peerId] ?: peerId
        // 역할 결정(양측 자동연결 대비): 공개키가 큰 쪽이 프리키 먼저 공개(responder)
        if (me.publicKey() > theirKey) transport.send(("K" + me.generatePrekey()).toByteArray())
        // 예전에 안전번호를 확인한 상대면 재확인 생략
        if (!safetyConfirmed && theirKey.isNotEmpty() &&
            Settings.isVerifiedPair(getApplication(), pairHash(theirKey))) {
            safetyConfirmed = true
            verifiedPeer.value = true
            screen.value = Screen.CHAT
        }
        if (!safetyConfirmed) screen.value = Screen.SAFETY
    }

    override fun onFrameReceived(peerId: String, frame: ByteArray) = ui { handle(frame) }
    override fun onDisconnected(peerId: String) = ui { linked.value = false; failPending() }

    // 복호화 실패(변조·재전송 등)는 조용히 버린다 — 예외로 앱이 죽지 않게.
    private fun handle(frame: ByteArray) = runCatching {
        val s = frame.decodeToString()
        when (s.firstOrNull()) {
            'K' -> {                                                         // initiator
                val sess = me.openSession(theirKey, s.substring(1))
                session = sess
                // 곧바로 개시 신호를 보내 responder 쪽 세션도 수립시킨다 (먼저 말 걸 수 있도록)
                transport.send(sess.encrypt(Inner.HELLO.toByteArray()).toByteArray())
            }
            'P' -> {
                // 상대가 답을 받기 전 연달아 보낸 PreKey 메시지는 기존 세션으로 푼다
                val existing = session?.let { runCatching { it.decrypt(s) }.getOrNull() }
                if (existing != null) onInner(existing.decodeToString())
                else me.acceptSession(theirKey, s).also {                // responder
                    session = it
                    seenIds.clear()
                    it.takeFirstMessage()?.let { m -> onInner(m.decodeToString()) }
                }
            }
            'N' -> session?.let { onInner(it.decrypt(s).decodeToString()) }
        }
        Unit
    }

    private fun onInner(plain: String) {
        when (val p = Inner.parse(plain)) {
            is Inner.Parsed.Msg -> {
                val s = session ?: return
                // ACK 는 중복이어도 다시 보낸다(첫 ACK 가 유실됐을 수 있음)
                transport.send(s.encrypt(Inner.ack(p.id).toByteArray()).toByteArray())
                if (seenIds.add(p.id)) addMessage(ChatMsg(false, p.text, id = p.id))
            }
            is Inner.Parsed.Ack -> setStatus(p.id, Delivery.DELIVERED)
            Inner.Parsed.Hello -> Unit                                   // 세션 수립용, 표시 안 함
            is Inner.Parsed.Legacy -> addMessage(ChatMsg(false, p.text))
        }
    }
}
