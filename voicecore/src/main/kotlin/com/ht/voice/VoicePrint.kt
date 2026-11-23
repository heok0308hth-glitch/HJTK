package com.ht.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 목소리 지문(.htvp): 녹음에서 한 번만 뽑아 두는 화자 조건 4개.
 * 원래는 기기에서 591MB짜리 음성 인코더가 매번 만들던 값이라, 미리 구워 두면 그 모델 없이 바로 말할 수 있다.
 *  cond   [1,33,1024]  말투·음색 조건(언어 모델 앞에 붙는다)
 *  prompt [1,T]        참고 음성의 소리 토큰(25개/초)
 *  xvec   [1,192]      화자 임베딩
 *  feat   [1,2T,80]    참고 음성의 멜 특징(50프레임/초)
 */
class VoicePrint(
    val cond: FloatArray, val condLen: Int,
    val prompt: LongArray,
    val xvec: FloatArray,
    val feat: FloatArray,
) {
    val promptSeconds: Float get() = prompt.size / 25f

    /** 디코더에 넘기는 참고 길이를 줄인다: 짧을수록 빠르고(통화용), 길수록 닮는다 */
    fun trimmed(seconds: Float): VoicePrint {
        val n = (seconds * 25).toInt().coerceIn(25, prompt.size)
        if (n == prompt.size) return this
        val p = prompt.copyOfRange(prompt.size - n, prompt.size)
        val frames = feat.size / 80
        val f = feat.copyOfRange((frames - 2 * n).coerceAtLeast(0) * 80, feat.size)
        return VoicePrint(cond, condLen, p, xvec, f)
    }

    companion object {
        fun read(bytes: ByteArray): VoicePrint {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 8 && String(bytes, 0, 4) == "HTVP") { "목소리 지문 파일이 아니에요" }
            b.position(8)
            fun shape(): IntArray { val r = b.int; return IntArray(r) { b.int } }
            fun floats(): Pair<IntArray, FloatArray> { val s = shape(); require(b.int == 0); val n = s.fold(1) { a, x -> a * x }; return s to FloatArray(n) { b.float } }
            fun longs(): Pair<IntArray, LongArray> { val s = shape(); require(b.int == 1); val n = s.fold(1) { a, x -> a * x }; return s to LongArray(n) { b.long } }
            val (cs, c) = floats(); val (_, p) = longs(); val (_, x) = floats(); val (_, f) = floats()
            return VoicePrint(c, cs[1], p, x, f)
        }
    }
}
