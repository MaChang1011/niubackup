package net.machang.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-wrapped secret storage (M4c, design §6: 令牌存 Android Keystore).
 *
 * A single AES-256 key lives in the Android Keystore under an alias; values
 * are encrypted with AES/GCM and the ciphertext (IV + body, Base64) is kept
 * in a dedicated SharedPreferences file. Used for: account access/refresh
 * tokens, the device token, and the per-user mesh network secret. If the
 * keystore entry is ever lost the ciphertexts become unreadable and reads
 * return null — the app then simply asks the user to log in again.
 */
object SecureStore {
    private const val PREFS = "secure_store"
    private const val ALIAS = "niubackup-master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
    const val KEY_DEVICE_TOKEN = "device_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_ACCOUNT = "account"
    const val KEY_NETWORK_SECRET = "network_secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, key: String, value: String?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, key: String): String? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            val packed = Base64.decode(raw, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12))
            )
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /** Drop every account-bound secret (logout). */
    fun clearAccount(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_DEVICE_TOKEN).remove(KEY_USER_ID).remove(KEY_DEVICE_ID)
            .remove(KEY_ACCOUNT).remove(KEY_NETWORK_SECRET)
            .apply()
    }
}
