package com.ht.hjtk

import com.ht.hjtk.core.pet.SpriteSlicer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 합성 시트: 흰 배경, 행마다 프레임 수가 다르고(6·8·4), 크기도 조금씩 다르다 */
class SlicerTest {
    private fun sheet(): Triple<IntArray, Int, Int> {
        val w = 900; val h = 360; val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val counts = intArrayOf(6, 8, 4)
        counts.forEachIndexed { r, n ->
            repeat(n) { c ->
                val bw = 70 + (c % 3) * 6; val bh = 90 + (c % 2) * 8
                val x0 = 10 + c * 110; val y0 = 10 + r * 118 + (100 - bh)
                for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
                    // 몸통 가운데에 2px 틈(머리카락 사이 같은) — 한 프레임으로 남아야 한다
                    if (x == x0 + bw / 2 || x == x0 + bw / 2 + 1) continue
                    px[y * w + x] = 0xFF2A9DB0.toInt()
                }
            }
        }
        return Triple(px, w, h)
    }

    @Test fun slicesRowsAndFrames() {
        val (px, w, h) = sheet()
        val s = SpriteSlicer.slice(px, w, h)
        assertEquals(listOf(6, 8, 4), s.rows.map { it.size })
        assertTrue(!s.transparentBg)
        assertTrue(s.cell >= 98)
    }

    @Test fun atlasIsTransparentAndBottomAligned() {
        val (px, w, h) = sheet()
        val s = SpriteSlicer.slice(px, w, h)
        val (a, aw, ah) = SpriteSlicer.atlas(px, w, h, s)
        assertEquals(8 * s.cell, aw); assertEquals(3 * s.cell, ah)
        assertEquals(0, a[0])                                     // 배경 → 투명
        // 같은 행의 키 작은 프레임(c=0, 90px)과 큰 프레임(c=1, 98px)의 발 위치가 같다
        fun bottom(col: Int): Int { for (y in s.cell - 1 downTo 0) for (x in 0 until s.cell) if (a[y * aw + col * s.cell + x] != 0) return y; return -1 }
        assertEquals(bottom(0), bottom(1))
    }
}
