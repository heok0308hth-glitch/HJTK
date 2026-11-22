package com.hjtk.app

import com.hjtk.app.android.prefs.SecretCodec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The AES-GCM envelope that protects API keys and mail passwords. */
class SecretCodecTest {

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsPlaintext() {
        val k = key()
        val packed = SecretCodec.encrypt(k, "sk-test-1234")
        assertNotEquals("sk-test-1234", packed)
        assertEquals("sk-test-1234", SecretCodec.decrypt(k, packed))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val k = key()
        val once = SecretCodec.encrypt(k, "같은 값")
        val twice = SecretCodec.encrypt(k, "같은 값")
        assertNotEquals(once, twice)
        assertEquals(SecretCodec.decrypt(k, once), SecretCodec.decrypt(k, twice))
    }

    @Test
    fun wrongKeyReturnsNullInsteadOfThrowing() {
        val packed = SecretCodec.encrypt(key(), "비밀")
        assertNull(SecretCodec.decrypt(key(), packed))
    }

    @Test
    fun tamperedPayloadReturnsNull() {
        val k = key()
        val packed = SecretCodec.encrypt(k, "비밀")
        val broken = packed.dropLast(4) + "AAAA"
        assertNull(SecretCodec.decrypt(k, broken))
    }

    @Test
    fun koreanAndNewlinesSurvive() {
        val k = key()
        val text = "네이버 앱 비밀번호\n1234-5678\n한글 ✅"
        assertEquals(text, SecretCodec.decrypt(k, SecretCodec.encrypt(k, text)))
    }
}
