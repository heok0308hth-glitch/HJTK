package com.ht.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 소리 다듬기와 WAV 저장. 숨소리를 더하거나 음색을 바꾸지 않는다 — 앞뒤 무음만 정리하고 크기를 맞춘다. */
object Wav {
    fun encode(pcm: FloatArray, sr: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        for (s in pcm) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }

    /** 앞뒤 무음 자르기 + 최고 -1dBFS로 맞추기 + 10ms 페이드(딸깍 소리 방지) */
    fun polish(x: FloatArray, sr: Int = 24000, gate: Float = 0.012f): FloatArray {
        if (x.isEmpty()) return x
        val win = sr / 100
        fun loud(i: Int): Boolean { var m = 0f; for (k in i until minOf(x.size, i + win)) m = maxOf(m, kotlin.math.abs(x[k])); return m > gate }
        var a = 0; while (a < x.size && !loud(a)) a += win
        var b = x.size; while (b > a && !loud(maxOf(0, b - win))) b -= win
        a = (a - win * 3).coerceAtLeast(0); b = (b + win * 6).coerceAtMost(x.size)
        if (b <= a) return FloatArray(0)
        val y = x.copyOfRange(a, b)
        val peak = y.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-4f)
        val g = (0.89f / peak).coerceAtMost(4f)
        val f = minOf(win, y.size / 2)
        for (i in y.indices) {
            var v = y[i] * g
            if (i < f) v *= i / f.toFloat()
            if (i >= y.size - f) v *= (y.size - 1 - i) / f.toFloat()
            y[i] = v
        }
        return y
    }

    fun toPcm16(x: FloatArray): ShortArray = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
}
