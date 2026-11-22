package com.hjtk.app.android.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the AES key comes from. Production: the Android Keystore. */
interface KeyProvider {
    fun key(): SecretKey?
    /** Drops a key that can no longer decrypt (lock screen changed, reset). */
    fun reset() = Unit
}

/**
 * Hardware-backed key in the Android Keystore. If the entry is gone (device
 * reset, key rotation) it is regenerated on demand; values encrypted with the
 * old key then fail to decrypt and the app simply asks again.
 */
class KeystoreKeyProvider : KeyProvider {
    override fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }.getOrNull()

    private fun generate(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun reset() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            ks.deleteEntry(ALIAS)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hjtk.master.v1"
    }
}

/**
 * AES-GCM envelope used for every stored secret: 12-byte IV followed by the
 * ciphertext, base64 encoded. Kept separate from Android so it can be tested
 * on the JVM with any key.
 */
object SecretCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(cipher.iv + body)
    }

    /** Returns null for wrong keys or tampered payloads instead of throwing. */
    fun decrypt(key: SecretKey, packed: String): String? = runCatching {
        val bytes = java.util.Base64.getDecoder().decode(packed)
        if (bytes.size <= 12) return null
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()
}

/**
 * AES-GCM values behind a key that never leaves the Keystore.
 *
 * API keys and mail passwords never touch plain SharedPreferences, never reach
 * logs and never leave the process in plain text. When the key is unavailable
 * reads return null and the app asks again, rather than silently falling back
 * to a weaker key.
 */
class SecurePrefs(
    context: android.content.Context,
    private val provider: KeyProvider = KeystoreKeyProvider()
) {

    private val prefs = context.applicationContext.getSharedPreferences("hjtk_secure", android.content.Context.MODE_PRIVATE)

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val key = provider.key() ?: return
        prefs.edit().putString(name, SecretCodec.encrypt(key, value)).apply()
    }

    fun get(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        val key = provider.key() ?: return null
        val value = SecretCodec.decrypt(key, packed)
        if (value == null) {
            // KeyPermanentlyInvalidatedException / AEADBadTag: the stored
            // ciphertext can never be read again. Drop the key so the next
            // write creates a fresh one instead of failing forever.
            provider.reset()
            prefs.edit().remove(name).apply()
        }
        return value
    }

    fun has(name: String): Boolean = !get(name).isNullOrEmpty()

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    /** Never printed; only used to mask a saved secret in the UI. */
    fun masked(name: String): String {
        val v = get(name) ?: return ""
        return if (v.length <= 4) "••••" else v.take(2) + "••••" + v.takeLast(2)
    }
}
