package com.ht.hjtk

import com.android.apksig.ApkVerifier
import com.ht.hjtk.core.forge.ApkForge
import com.ht.hjtk.core.forge.Axml
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/** 3.6 기기 안 APK 포장기: 실제 셸 APK로 포장 → 서명 → 검증(apksig · aapt2 · zipalign) */
class ForgeTest {
    private val shell = File("build/generated/forge/forge/shell.apk")
    private val sdk = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk").let { File(it) }
    private fun tool(n: String) = sdk.resolve("build-tools").listFiles()?.sortedDescending()?.map { it.resolve(n) }?.firstOrNull { it.exists() }

    private fun key(): Pair<PrivateKey, X509Certificate> {
        val ks = File.createTempFile("forge", ".p12").also { it.delete() }
        val p = ProcessBuilder("keytool", "-genkeypair", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "testpass", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-dname", "CN=HJTK Made App").redirectErrorStream(true).start()
        p.inputStream.readBytes(); assertEquals(0, p.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "testpass".toCharArray()) } }
        return (store.getKey("k", "testpass".toCharArray()) as PrivateKey) to (store.getCertificate("k") as X509Certificate)
    }

    /** 손으로 만든 단색 PNG(테스트 클래스패스엔 awt가 없다) */
    private fun png(size: Int): ByteArray {
        fun chunk(o: java.io.DataOutputStream, type: String, data: ByteArray) {
            o.writeInt(data.size); val t = type.toByteArray(); o.write(t); o.write(data)
            o.writeInt(java.util.zip.CRC32().apply { update(t); update(data) }.value.toInt())
        }
        val raw = ByteArrayOutputStream()
        repeat(size) { raw.write(0); repeat(size) { raw.write(228); raw.write(77); raw.write(46); raw.write(255) } }
        val bos = ByteArrayOutputStream(); val o = java.io.DataOutputStream(bos)
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(o, "IHDR", ByteArrayOutputStream().also { java.io.DataOutputStream(it).apply { writeInt(size); writeInt(size); write(8); write(6); write(0); write(0); write(0) } }.toByteArray())
        chunk(o, "IDAT", java.util.zip.Deflater().run { setInput(raw.toByteArray()); finish(); val buf = ByteArray(raw.size() + 1024); val n = deflate(buf); buf.copyOf(n) })
        chunk(o, "IEND", ByteArray(0))
        return bos.toByteArray()
    }

    @Test fun manifestSlots_arePresentAndLongEnough() {
        assumeTrue(shell.exists())
        val s = ApkForge.manifestStrings(shell.readBytes())
        assertTrue(s.contains(ApkForge.PKG_SLOT)); assertTrue(s.contains(ApkForge.LABEL_SLOT))
        assertEquals(ApkForge.PKG_SLOT.length, ApkForge.packageFor("7F3K9Q2M4XZ").length)
        assertTrue(ApkForge.packageFor("7F3K9Q2M4XZ").matches(Regex("""com\.ht\.made\.a[a-z][a-z0-9]{9}""")))
    }

    @Test fun repackAndSign_producesInstallableApk() {
        assumeTrue(shell.exists())
        val pkg = ApkForge.packageFor("7F3K9Q2M4XZ")
        val www = mapOf("index.html" to "<!doctype html><title>할 일 정원</title><script src=main.js></script>".toByteArray(), "main.js" to "document.title='ok'".toByteArray())
        val unsigned = File.createTempFile("unsigned", ".apk").apply { writeBytes(ApkForge.repack(shell.readBytes(), www, pkg, "할 일 정원") { png(it) }) }
        val signed = File.createTempFile("signed", ".apk")
        val (k, c) = key()
        ApkForge.sign(unsigned, signed, k, listOf(c))
        val v = ApkVerifier.Builder(signed).build().verify()
        assertTrue("서명 검증: ${v.errors}", v.isVerified)
        assertTrue(v.isVerifiedUsingV2Scheme)
        val strings = ApkForge.manifestStrings(signed.readBytes())
        assertTrue(strings.contains(pkg)); assertTrue(strings.contains("할 일 정원")); assertFalse(strings.contains(ApkForge.PKG_SLOT))
        ZipFile(signed).use { z ->
            assertNotNull(z.getEntry("assets/www/main.js"))
            assertEquals(java.util.zip.ZipEntry.STORED, z.getEntry("resources.arsc").method)
        }
        // 안드로이드 도구로도 확인(있을 때만): 패키지·이름 파싱, 4바이트 정렬
        tool("aapt2")?.let { a ->
            val out = ProcessBuilder(a.path, "dump", "badging", signed.path).redirectErrorStream(true).start().inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(out, out.contains("package: name='$pkg'")); assertTrue(out, out.contains("application-label:'할 일 정원'"))
        }
        tool("zipalign")?.let { za -> assertEquals(0, ProcessBuilder(za.path, "-c", "4", signed.path).start().waitFor()) }
    }

    @Test fun axmlReplace_refusesOverflow() {
        assumeTrue(shell.exists())
        val m = java.util.zip.ZipInputStream(shell.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name == "AndroidManifest.xml" }; z.readBytes() }
        try { Axml.replace(m, mapOf(ApkForge.PKG_SLOT to "com.ht.made." + "x".repeat(80))); fail("넘치면 막아야 한다") } catch (e: IllegalArgumentException) { }
    }
}
