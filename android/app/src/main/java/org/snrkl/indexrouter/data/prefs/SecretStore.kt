package org.snrkl.indexrouter.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the webhook shared secret, encrypted at rest by a hardware-backed
 * Android Keystore key that never leaves the TEE.
 *
 * The secret exists only on the device: it is generated here, shown once so it can
 * be pasted into the Pebble app's Authorization Header field, and never written to
 * the repo, a log, a status file or a config export. Losing it costs nothing —
 * regenerate and re-paste.
 *
 * Uses the Keystore directly rather than androidx.security-crypto, which is
 * deprecated and would add a dependency for the same 40 lines.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasSecret(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    /** Generates, stores and returns a new secret, replacing any existing one. */
    fun generate(): String {
        val raw = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val secret = android.util.Base64.encodeToString(
            raw,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        store(secret)
        return secret
    }

    /** The current secret, or null if none has been generated yet. */
    fun read(): String? {
        val packed = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val blob = android.util.Base64.decode(packed, android.util.Base64.NO_WRAP)
        if (blob.size <= IV_BYTES) return null
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() = prefs.edit().remove(KEY_CIPHERTEXT).apply()

    private fun store(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val packed = android.util.Base64.encodeToString(cipher.iv + body, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_CIPHERTEXT, packed).apply()
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Not tied to screen unlock: the webhook can arrive while the
                // phone is locked in a pocket, which is the normal case.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "webhook_secret"
        const val KEY_CIPHERTEXT = "secret_v1"
        const val KEY_ALIAS = "indexrouter_webhook_secret"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
