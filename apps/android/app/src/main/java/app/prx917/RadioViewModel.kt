package app.prx917

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import app.prx917.transport.NearbyTransport
import app.prx917.transport.RadioTransportListener
import uniffi.crypto_core.Identity
import uniffi.crypto_core.SecureSession

enum class Screen { LOCK, CHANNEL, DISCOVERY, SAFETY, CHAT }
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

    val callsign = "DELTA-6"
    val channels = listOf(
        1 to "917.1", 2 to "918.3", 3 to "919.5", 4 to "920.7",
        5 to "921.3", 6 to "922.1", 7 to "922.9", 8 to "923.5"
    )

    private val me = Identity.generate()                 // 실제 crypto_core 신원
    private val transport = NearbyTransport(app, callsign, this)
    private var session: SecureSession? = null
    private var theirKey = ""
    private var safetyConfirmed = false

    fun unlock() { screen.value = Screen.CHANNEL }

    fun selectChannel(n: Int) {
        channel.value = n
        transport.stop(); transport.setChannel(n); transport.start(me.publicKey())
        discovered.clear(); session = null; linked.value = false; safetyConfirmed = false
        screen.value = Screen.DISCOVERY
    }

    fun confirmSafety() { safetyConfirmed = true; screen.value = Screen.CHAT }
    fun rejectSafety() { transport.stop(); session = null; screen.value = Screen.DISCOVERY }

    fun send(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val s = session ?: return
        transport.send(s.encrypt(t.toByteArray()).toByteArray())
        messages.add(ChatMsg(true, t))
    }

    override fun onCleared() { transport.stop() }

    // 전송 콜백 → 메인 스레드
    private fun ui(block: () -> Unit) { Handler(Looper.getMainLooper()).post(block) }

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
