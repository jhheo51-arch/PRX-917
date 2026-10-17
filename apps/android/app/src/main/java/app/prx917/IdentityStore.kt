package app.prx917

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uniffi.crypto_core.Identity
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 신원키 영구저장 — **생체/기기 인증에 묶인 Keystore 키로 봉인**.
 *
 * - 실제 비밀값은 crypto_core 가 pickle_key 로 암호화한다(pickle 문자열).
 * - pickle_key 는 Android Keystore 의 AES-256-GCM 키(WRAP_ALIAS)로 감싸 저장한다.
 *   이 Keystore 키는 `setUserAuthenticationRequired(true)` 라서 **매 사용마다** 인증이 필요하고,
 *   생체 등록이 바뀌면 영구 무효화된다(`setInvalidatedByBiometricEnrollment(true)`).
 * - 따라서 BiometricPrompt 의 CryptoObject 로 인증된 Cipher 가 있어야만 신원키를 복원할 수 있다.
 *   (UI 단에서 인증을 건너뛰어도 키는 열리지 않는다.)
 *
 * 흐름: [begin] 으로 Cipher 준비 → BiometricPrompt(CryptoObject) 인증 → [finish] 로 복원/생성.
 */
object IdentityStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "prx917_identity_wrap"
    private const val PREFS = "prx917_sealed"
    private const val K_PICKLE = "identity_pickle"      // crypto_core 가 이미 암호화한 pickle
    private const val K_WRAPPED = "pickle_key_wrapped"  // Keystore 키로 감싼 pickle_key
    private const val K_IV = "pickle_key_iv"

    // 이전 버전(인증 없이 열리던 EncryptedSharedPreferences) 저장소 — 최초 1회 마이그레이션용
    private const val LEGACY_PREFS = "prx917_secure"
    private const val LEGACY_PICKLE = "identity_pickle"
    private const val LEGACY_KEY = "pickle_key_b64"

    /** 인증 대기 중인 작업. cipher 를 BiometricPrompt.CryptoObject 에 넣어 인증한다. */
    class Pending internal constructor(val cipher: Cipher, internal val restore: Boolean)

    /** 생체 등록 변경 등으로 Keystore 키가 영구 무효화됨 → 신원 재생성([wipe]) 필요. */
    class KeyInvalidatedException(cause: Throwable) : Exception(cause)

    /** 저장된 신원이 있으면 복호화용, 없으면 신규 봉인용 Cipher 를 준비한다. */
    fun begin(ctx: Context): Pending {
        val prefs = prefs(ctx)
        val wrapped = prefs.getString(K_WRAPPED, null)
        val iv = prefs.getString(K_IV, null)
        val hasSealed = wrapped != null && iv != null && prefs.getString(K_PICKLE, null) != null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            if (hasSealed) {
                val key = existingKey() ?: throw KeyInvalidatedException(IllegalStateException("봉인 키 없음"))
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, newKey())
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedException(e)
        } catch (e: UnrecoverableKeyException) {
            throw KeyInvalidatedException(e)
        }
        return Pending(cipher, hasSealed)
    }

    /** 인증 성공 후, 인증된 cipher 로 신원을 복원하거나 새로 생성·봉인한다. */
    fun finish(ctx: Context, p: Pending, authed: Cipher): Identity {
        val prefs = prefs(ctx)
        if (p.restore) {
            val pickleKey = authed.doFinal(Base64.decode(prefs.getString(K_WRAPPED, null), Base64.NO_WRAP))
            return Identity.restore(prefs.getString(K_PICKLE, null)!!, pickleKey)
        }
        // 신규 봉인. 이전 버전 저장소가 있으면 그 신원을 그대로 옮긴다(안전번호 유지).
        val legacy = readLegacy(ctx)
        val pickleKey: ByteArray
        val id: Identity
        if (legacy != null) {
            pickleKey = legacy.first; id = legacy.second
        } else {
            pickleKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            id = Identity.generate()
        }
        val wrapped = authed.doFinal(pickleKey)
        prefs.edit()
            .putString(K_PICKLE, id.save(pickleKey))
            .putString(K_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(authed.iv, Base64.NO_WRAP))
            .commit()
        pickleKey.fill(0)
        if (legacy != null) ctx.deleteSharedPreferences(LEGACY_PREFS)
        return id
    }

    /** 신원 완전 삭제 — 봉인 데이터·Keystore 키 제거. 다음 잠금 해제 때 새 신원이 생성된다. */
    fun wipe(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        ctx.deleteSharedPreferences(LEGACY_PREFS)
        keyStore().deleteEntry(WRAP_ALIAS)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(WRAP_ALIAS, null) as SecretKey?

    private fun newKey(): SecretKey {
        keyStore().deleteEntry(WRAP_ALIAS)
        val spec = KeyGenParameterSpec.Builder(
            WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // API 30+: 매 사용마다(0초) 강한 생체 또는 기기 PIN/패턴.
                // API 24–29: 기본값(사용마다 인증, 생체 전용) — CryptoObject 는 생체로만 열린다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** 이전 버전 저장소에서 (pickle_key, Identity) 를 읽는다. 없거나 손상이면 null. */
    private fun readLegacy(ctx: Context): Pair<ByteArray, Identity>? = runCatching {
        val file = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (file.all.isEmpty()) return null
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val legacy = EncryptedSharedPreferences.create(
            ctx, LEGACY_PREFS, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val key = Base64.decode(legacy.getString(LEGACY_KEY, null) ?: return null, Base64.NO_WRAP)
        val pickle = legacy.getString(LEGACY_PICKLE, null) ?: return null
        key to Identity.restore(pickle, key)
    }.getOrNull()
}
