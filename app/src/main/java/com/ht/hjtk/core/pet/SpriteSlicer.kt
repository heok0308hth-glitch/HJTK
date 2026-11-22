package com.ht.hjtk.core.pet

import kotlin.math.abs
import kotlin.math.max

/**
 * 아무 스프라이트 시트나 받아 '행 = 동작, 칸 = 프레임'으로 자른다. 안드로이드 의존 없음(JVM 테스트 가능).
 *
 *  1) 배경 판정: 투명 시트면 알파, 아니면 네 모서리의 대표색(허용오차)
 *  2) 행: 가로 투영에서 빈 줄이 이어지는 곳으로 자른다(행마다 프레임 수가 달라도 된다)
 *  3) 칸: 행 안에서 세로 투영의 빈 기둥으로 자른다. 머리카락처럼 가는 틈은 minGap으로 무시
 *  4) 정규화: 모든 프레임을 같은 셀에, 발(아래)을 기준선에 맞춰 가운데 정렬 → 걷기 동작이 위아래로 튀지 않는다
 */
object SpriteSlicer {
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)
    data class Sheet(val rows: List<List<Box>>, val cell: Int, val bgArgb: Int?, val transparentBg: Boolean)

    /** 배경이면 true */
    class Mask(val w: Int, val h: Int, private val bits: BooleanArray) {
        fun fg(x: Int, y: Int) = bits[y * w + x]
    }

    fun mask(px: IntArray, w: Int, h: Int, tol: Int = 34): Pair<Mask, Int?> {
        val transparentCorners = listOf(0, w - 1, (h - 1) * w, h * w - 1).count { (px[it] ushr 24) < 16 }
        if (transparentCorners >= 3) return Mask(w, h, BooleanArray(w * h) { (px[it] ushr 24) >= 48 }) to null
        // 모서리 4곳 근처 표본의 최빈 색을 배경으로
        val samples = ArrayList<Int>()
        for ((cx, cy) in listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)) for (dy in 0 until minOf(4, h)) for (dx in 0 until minOf(4, w)) {
            val x = if (cx == 0) dx else cx - dx; val y = if (cy == 0) dy else cy - dy
            samples += px[y * w + x]
        }
        val bg = samples.groupingBy { it and 0xF8F8F8 }.eachCount().maxByOrNull { it.value }!!.key
        val bits = BooleanArray(w * h) { i -> val c = px[i]; (c ushr 24) >= 48 && dist(c, bg) > tol }
        return Mask(w, h, bits) to (bg or (0xFF shl 24))
    }

    private fun dist(a: Int, b: Int): Int = max(abs(((a shr 16) and 255) - ((b shr 16) and 255)), max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255))))

    fun slice(px: IntArray, w: Int, h: Int, minGap: Int = 3, minSize: Int = 12): Sheet {
        val (m, bg) = mask(px, w, h)
        val rowsOn = BooleanArray(h) { y -> var c = 0; for (x in 0 until w) if (m.fg(x, y)) { c++; if (c > 1) break }; c > 1 }
        val bands = runs(rowsOn, minGap).filter { (a, b) -> b - a + 1 >= minSize }
        val rows = bands.map { (y0, y1) ->
            val colOn = BooleanArray(w) { x -> var on = false; for (y in y0..y1) if (m.fg(x, y)) { on = true; break }; on }
            runs(colOn, minGap).filter { (a, b) -> b - a + 1 >= minSize / 2 }.map { (x0, x1) ->
                // 칸 안에서 실제 내용의 위·아래를 다시 좁힌다(행 높이보다 작은 프레임)
                var top = y1; var bot = y0
                for (y in y0..y1) for (x in x0..x1) if (m.fg(x, y)) { if (y < top) top = y; if (y > bot) bot = y }
                Box(x0, top, x1 - x0 + 1, bot - top + 1)
            }.filter { it.w >= minSize / 2 && it.h >= minSize / 2 }
        }.filter { it.isNotEmpty() }
        val cell = (rows.flatten().maxOfOrNull { max(it.w, it.h) } ?: 0).let { it + it / 16 + 2 }
        return Sheet(rows, cell, bg, bg == null)
    }

    /** 참인 구간들. 거짓이 minGap 미만으로 끼어 있으면 하나로 잇는다 */
    private fun runs(on: BooleanArray, minGap: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < on.size) {
            if (!on[i]) { i++; continue }
            var j = i
            while (true) {
                while (j + 1 < on.size && on[j + 1]) j++
                var k = j + 1; var gap = 0
                while (k < on.size && !on[k] && gap < minGap) { k++; gap++ }
                if (k < on.size && on[k] && gap < minGap) { j = k; continue }
                break
            }
            out += i to j; i = j + 1
        }
        return out
    }

    /**
     * 정규화된 아틀라스 픽셀(행 = 동작, 열 = 프레임, 셀 = cell×cell). 배경은 투명으로.
     * 발 기준선: 각 행의 가장 큰 프레임 높이 기준으로 아래 정렬, 가로는 가운데.
     */
    fun atlas(px: IntArray, w: Int, h: Int, s: Sheet): Triple<IntArray, Int, Int> {
        val cols = s.rows.maxOf { it.size }; val aw = cols * s.cell; val ah = s.rows.size * s.cell
        val out = IntArray(aw * ah)
        val (m, _) = mask(px, w, h)
        s.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, b ->
                val ox = c * s.cell + (s.cell - b.w) / 2
                val oy = r * s.cell + (s.cell - b.h) - 1   // 아래(발) 정렬
                for (y in 0 until b.h) for (x in 0 until b.w) {
                    val sx = b.x + x; val sy = b.y + y
                    if (m.fg(sx, sy)) out[(oy + y) * aw + (ox + x)] = px[sy * w + sx]
                }
            }
        }
        return Triple(out, aw, ah)
    }
}
