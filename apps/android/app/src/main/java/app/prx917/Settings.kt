package app.prx917

import android.content.Context
import kotlin.random.Random

/**
 * 비밀이 아닌 사용자 설정(콜사인·자동삭제·알림음). 신원키와 무관하므로 일반 SharedPreferences.
 * iOS(UserDefaults)와 같은 규칙을 쓴다.
 */
object Settings {
    private const val PREFS = "prx917_settings"
    private const val K_CALLSIGN = "callsign"
    private const val K_AUTO_DELETE = "auto_delete"
    private const val K_SOUND = "sound"
    private const val K_VERIFIED = "verified_pairs"

    /** 영문 대문자·숫자·하이픈 2~12자. '|' 는 광고 이름 구분자라 금지된다. */
    private val CALLSIGN_RE = Regex("^[A-Z0-9-]{2,12}$")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 첫 실행 시 "UNIT-XXXX"(16진 4자리)를 만들어 저장한다. */
    fun callsign(ctx: Context): String {
        prefs(ctx).getString(K_CALLSIGN, null)?.let { return it }
        val generated = "UNIT-%04X".format(Random.nextInt(0x10000))
        prefs(ctx).edit().putString(K_CALLSIGN, generated).apply()
        return generated
    }

    /** 입력을 대문자로 정규화해 검증. 유효하면 정규화된 값, 아니면 null. */
    fun normalizeCallsign(raw: String): String? =
        raw.trim().uppercase().takeIf { CALLSIGN_RE.matches(it) }

    fun setCallsign(ctx: Context, v: String) = prefs(ctx).edit().putString(K_CALLSIGN, v).apply()

    fun autoDelete(ctx: Context) = prefs(ctx).getBoolean(K_AUTO_DELETE, false)
    fun setAutoDelete(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO_DELETE, v).apply()

    fun sound(ctx: Context) = prefs(ctx).getBoolean(K_SOUND, true)
    fun setSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_SOUND, v).apply()

    // 안전번호를 확인한 (내 키, 상대 키) 쌍의 SHA-256. 키 자체는 저장하지 않는다.
    fun isVerifiedPair(ctx: Context, hash: String) =
        prefs(ctx).getStringSet(K_VERIFIED, emptySet())!!.contains(hash)

    fun addVerifiedPair(ctx: Context, hash: String) {
        val set = prefs(ctx).getStringSet(K_VERIFIED, emptySet())!!.toMutableSet()  // 반환 Set 은 수정 금지라 복사
        set += hash
        prefs(ctx).edit().putStringSet(K_VERIFIED, set).apply()
    }

    fun clearVerifiedPairs(ctx: Context) = prefs(ctx).edit().remove(K_VERIFIED).apply()
}
