package com.ht.hjtk.core.forge

import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 기기 안 APK 포장기. AI 팀이 만든 웹 앱(app/ 폴더)을 '설치 가능한 앱'으로 만든다.
 *  1) 셸 APK(빈 WebView 앱)를 열어 서명 흔적을 지우고
 *  2) assets/www/ 에 웹 앱 파일을 넣고
 *  3) 바이너리 매니페스트의 패키지 이름·앱 이름 자리표시를 바꾸고(같은 칸 안에서)
 *  4) 런처 아이콘을 새로 그린 그림으로 바꾼 뒤
 *  5) apksig로 서명한다(키는 기기 안 Keystore — 밖으로 나가지 않는다).
 * 컴퓨터도, 서버도, 인터넷도 필요 없다.
 */
object ApkForge {
    const val PKG_SLOT = "com.ht.made.app00000000"
    const val LABEL_SLOT = "HTMADE_LABEL_SLOT_0000000000000000000000000000000"

    /** 패키지 이름: 자리표시와 같은 길이(23자) — 프로젝트마다 다르게 해서 여러 앱을 함께 설치할 수 있다 */
    fun packageFor(seed: String): String {
        val tail = (seed.lowercase().filter { it in 'a'..'z' || it in '0'..'9' } + "00000000").take(8)
        return "com.ht.made.a" + (if (tail.first().isDigit()) "p" + tail.drop(1) else tail).padEnd(10, '0').take(10)
    }

    /** 앱 이름: UTF-16 기준 49자 안. 길면 자른다 */
    fun labelFor(name: String) = name.replace(Regex("[\\r\\n\\t]"), " ").trim().ifBlank { "HJTK 앱" }.take(30)

    fun repack(shell: ByteArray, www: Map<String, ByteArray>, pkg: String, label: String, icon: ((Int) -> ByteArray?)? = null): ByteArray {
        require(pkg.length == PKG_SLOT.length) { "패키지 이름 길이는 ${PKG_SLOT.length}자" }
        require(www.keys.any { it == "index.html" }) { "index.html이 없어요" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(shell.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name
                    if (name.startsWith("META-INF/") || name.startsWith("assets/www/")) continue
                    var data = zin.readBytes()
                    if (name == "AndroidManifest.xml") data = Axml.replace(data, mapOf(PKG_SLOT to pkg, LABEL_SLOT to labelFor(label)))
                    if (icon != null && name.startsWith("res/") && name.endsWith(".png")) pngSize(data)?.let { sz -> icon(sz)?.let { data = it } }
                    put(z, name, data, stored = name == "resources.arsc" || name.endsWith(".png"))
                }
            }
            www.toSortedMap().forEach { (p, bytes) -> put(z, "assets/www/" + p.trimStart('/'), bytes, stored = false) }
        }
        return out.toByteArray()
    }

    /** 서명(v2+v3, 최소 SDK 26). apksig가 압축 안 한 항목의 정렬도 맞춘다 */
    fun sign(unsigned: File, out: File, key: PrivateKey, certs: List<X509Certificate>) {
        val cfg = ApkSigner.SignerConfig.Builder("hjtk", key, certs).build()
        ApkSigner.Builder(listOf(cfg)).setInputApk(unsigned).setOutputApk(out).setMinSdkVersion(26)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
    }

    /** 만든 APK의 매니페스트 문자열(검증용) */
    fun manifestStrings(apk: ByteArray): List<String> {
        ZipInputStream(apk.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "AndroidManifest.xml") return Axml.strings(z.readBytes()) }
        }
        return emptyList()
    }

    private fun put(z: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) { e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = CRC32().apply { update(data) }.value }
        else e.method = ZipEntry.DEFLATED
        e.time = 315_532_800_000L     // 1980-01-01: 같은 입력이면 같은 결과
        z.putNextEntry(e); z.write(data); z.closeEntry()
    }

    private fun pngSize(b: ByteArray): Int? =
        if (b.size > 24 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) ((b[16].toInt() and 0xFF) shl 24) or ((b[17].toInt() and 0xFF) shl 16) or ((b[18].toInt() and 0xFF) shl 8) or (b[19].toInt() and 0xFF) else null
}
