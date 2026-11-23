package com.ht.hjtk.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 녹음 다듬기 — 서버에서 '내 목소리'를 만들 때와 같은 순서:
 * 24kHz 모노 → 70Hz 고역 통과(웅웅거림 제거) → 무음 구간을 0.12초로 압축 → 말소리 크기 -20dB로 맞춤 → 최대 30초.
 * 음색을 바꾸거나 숨소리·잡음을 더하지 않는다.
 */
object VoicePrep {
    const val SR = 24000

    fun clean(x: FloatArray, sr: Int): FloatArray {
        var y = if (sr == SR) x.copyOf() else resample(x, sr, SR)
        y = highpass(y, 70.0)
        y = squeezeSilence(y)
        if (y.size > SR * 30) y = y.copyOf(SR * 30)
        val rms = sqrt(y.fold(0.0) { a, v -> a + v * v } / y.size.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-6f)
        var g = 0.1f / rms
        val peak = y.maxOfOrNull { abs(it) } ?: 0f
        if (peak * g > 0.95f) g = 0.95f / peak
        for (i in y.indices) y[i] *= g
        return y
    }

    /** 윈도 sinc 보간(16탭) — 말소리에는 충분하고 계단 잡음이 없다 */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        val ratio = to.toDouble() / from; val n = (x.size * ratio).toInt(); val out = FloatArray(n)
        val cutoff = minOf(1.0, ratio); val half = 8
        for (i in 0 until n) {
            val t = i / ratio; val c = t.toInt(); var acc = 0.0; var wsum = 0.0
            for (k in c - half + 1..c + half) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val s = if (abs(d) < 1e-9) 1.0 else sin(PI * d * cutoff) / (PI * d * cutoff)
                val w = 0.5 + 0.5 * cos(PI * d / half)
                acc += x[k] * s * w; wsum += s * w
            }
            out[i] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    fun highpass(x: FloatArray, fc: Double): FloatArray {
        val w = 2 * PI * fc / SR; val q = 0.707; val alpha = sin(w) / (2 * q); val cw = cos(w)
        val b0 = (1 + cw) / 2; val b1 = -(1 + cw); val b2 = (1 + cw) / 2; val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        val y = FloatArray(x.size); var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val o = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x[i].toDouble(); y2 = y1; y1 = o; y[i] = o.toFloat()
        }
        return y
    }

    /** 10ms 창 기준, 최대치보다 45dB 낮으면 무음. 무음은 0.12초까지만 남긴다 */
    fun squeezeSilence(x: FloatArray): FloatArray {
        val win = SR / 100; val frames = x.size / win
        if (frames == 0) return x
        val rms = FloatArray(frames) { f -> var s = 0.0; for (k in f * win until (f + 1) * win) s += x[k] * x[k]; sqrt(s / win).toFloat() }
        val top = rms.max().coerceAtLeast(1e-6f); val gate = top * 0.0056f   // -45dB
        val out = ArrayList<Float>(x.size); var quiet = 0
        for (f in 0 until frames) {
            if (rms[f] < gate) { quiet++; if (quiet > 12) continue } else quiet = 0
            for (k in f * win until (f + 1) * win) out += x[k]
        }
        return out.toFloatArray()
    }

    /** 음성 인코더 결과 → .htvp(서버에서 만든 '내 목소리'와 같은 형식) */
    fun pack(r: OrtSession.Result): ByteArray {
        val ts = (0 until 4).map { r.get(it) as OnnxTensor }
        val size = 8 + ts.sumOf { t -> 4 + 4 * t.info.shape.size + 4 + t.info.shape.fold(1L) { a, b -> a * b } * (if (t.info.type == ai.onnxruntime.OnnxJavaType.INT64) 8 else 4) }.toInt()
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("HTVP".toByteArray()).putInt(1)
        for (t in ts) {
            val sh = t.info.shape; b.putInt(sh.size); sh.forEach { b.putInt(it.toInt()) }
            if (t.info.type == ai.onnxruntime.OnnxJavaType.INT64) { b.putInt(1); val lb = t.longBuffer; while (lb.hasRemaining()) b.putLong(lb.get()) }
            else { b.putInt(0); val fb = t.floatBuffer; while (fb.hasRemaining()) b.putFloat(fb.get()) }
        }
        return b.array()
    }
}
