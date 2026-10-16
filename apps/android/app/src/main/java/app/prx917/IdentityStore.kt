package app.prx917

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uniffi.crypto_core.Identity
import java.security.SecureRandom

/**
 * 신원키 영구저장. 실제 비밀값은 crypto_core가 pickle_key로 암호화하고(pickle 문자열),
 * 그 pickle_key 와 pickle 을 **Android Keystore 로 봉인된 EncryptedSharedPreferences** 에 저장한다.
 * → 기기 밖으로 키가 평문 노출되지 않으며, 앱 재시작 후에도 동일 신원이 유지된다.
 */
object IdentityStore {
    private const val PREFS = "prx917_secure"
    private const val K_PICKLE = "identity_pickle"
    private const val K_KEY = "pickle_key_b64"

    /** 저장된 신원을 복원하거나, 없으면 새로 생성해 저장한다. */
    fun loadOrCreate(ctx: Context): Identity {
        val prefs = securePrefs(ctx)
        val keyB64 = prefs.getString(K_KEY, null)
        val pickle = prefs.getString(K_PICKLE, null)
        if (keyB64 != null && pickle != null) {
            val key = Base64.decode(keyB64, Base64.NO_WRAP)
            runCatching { return Identity.restore(pickle, key) }   // 손상 시 아래에서 재생성
        }
        return create(prefs)
    }

    /** 신원키 재생성 — 기존 저장 삭제 후 새로 생성·저장. */
    fun reset(ctx: Context): Identity {
        val prefs = securePrefs(ctx)
        prefs.edit().clear().apply()
        return create(prefs)
    }

    private fun create(prefs: SharedPreferences): Identity {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = Identity.generate()
        prefs.edit()
            .putString(K_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
            .putString(K_PICKLE, id.save(key))
            .apply()
        return id
    }

    private fun securePrefs(ctx: Context): SharedPreferences {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREFS,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
