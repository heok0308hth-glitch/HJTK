package com.ht.hjtk.core.forge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 컴파일된(바이너리) AndroidManifest.xml의 문자열 풀을 그 자리에서 고친다.
 * 문서 구조·오프셋은 그대로 두고, 같은 칸(바이트 수) 안에서 문자열만 바꾼다 → 짧거나 같은 길이면 안전하다.
 * 셸 매니페스트에 패키지·앱 이름을 넉넉한 길이의 자리표시로 넣어 둔 이유.
 */
object Axml {
    private const val RES_XML = 0x0003
    private const val RES_STRING_POOL = 0x0001
    private const val UTF8 = 0x100

    data class Pool(val start: Int, val count: Int, val utf8: Boolean, val stringsStart: Int, val offsets: IntArray)

    fun pool(b: ByteArray): Pool {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getShort(0).toInt() and 0xFFFF == RES_XML) { "바이너리 매니페스트가 아님" }
        val headerSize = bb.getShort(2).toInt() and 0xFFFF
        val p = headerSize
        require(bb.getShort(p).toInt() and 0xFFFF == RES_STRING_POOL) { "문자열 풀 없음" }
        val ph = bb.getShort(p + 2).toInt() and 0xFFFF
        val count = bb.getInt(p + 8)
        val flags = bb.getInt(p + 16)
        val stringsStart = bb.getInt(p + 20)
        val offs = IntArray(count) { bb.getInt(p + ph + it * 4) }
        return Pool(p, count, flags and UTF8 != 0, stringsStart, offs)
    }

    fun strings(b: ByteArray): List<String> { val pl = pool(b); return (0 until pl.count).map { read(b, pl, it).first } }

    /** (문자열, 그 칸이 차지한 바이트 수 — 끝 0 포함) */
    private fun read(b: ByteArray, pl: Pool, i: Int): Pair<String, Int> {
        val at = pl.start + pl.stringsStart + pl.offsets[i]
        return if (pl.utf8) {
            var k = at
            val (_, n1) = len8(b, k); k += n1
            val (bytes, n2) = len8(b, k); k += n2
            String(b, k, bytes, Charsets.UTF_8) to (k - at + bytes + 1)
        } else {
            var k = at
            var len = u16(b, k); k += 2
            if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or u16(b, k); k += 2 }
            String(b, k, len * 2, Charsets.UTF_16LE) to (k - at + len * 2 + 2)
        }
    }

    /** 바꾸기: 원래 문자열 안의 old를 new로. 칸이 모자라면 예외(셸 자리표시를 더 길게 잡아야 한다) */
    fun replace(src: ByteArray, map: Map<String, String>): ByteArray {
        val b = src.copyOf()
        val pl = pool(b)
        for (i in 0 until pl.count) {
            val (s, room) = read(b, pl, i)
            var t = s
            map.forEach { (o, n) -> if (t.contains(o)) t = t.replace(o, n) }
            if (t == s) continue
            val enc = encode(t, pl.utf8)
            require(enc.size <= room) { "'$t'이(가) 자리(${room}바이트)보다 길어요" }
            val at = pl.start + pl.stringsStart + pl.offsets[i]
            java.util.Arrays.fill(b, at, at + room, 0)
            System.arraycopy(enc, 0, b, at, enc.size)
        }
        return b
    }

    private fun encode(s: String, utf8: Boolean): ByteArray = if (utf8) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        fun put8(n: Int) { if (n > 0x7F) { out.write(((n shr 8) and 0x7F) or 0x80); out.write(n and 0xFF) } else out.write(n) }
        put8(s.length); put8(bytes.size); out.write(bytes); out.write(0); out.toByteArray()
    } else {
        val chars = s.toByteArray(Charsets.UTF_16LE)
        val n = s.length
        val out = java.io.ByteArrayOutputStream()
        require(n <= 0x7FFF) { "문자열이 너무 길어요" }
        out.write(n and 0xFF); out.write((n shr 8) and 0xFF); out.write(chars); out.write(0); out.write(0); out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun len8(b: ByteArray, i: Int): Pair<Int, Int> {
        val x = b[i].toInt() and 0xFF
        return if (x and 0x80 != 0) ((((x and 0x7F) shl 8) or (b[i + 1].toInt() and 0xFF)) to 2) else (x to 1)
    }
}
