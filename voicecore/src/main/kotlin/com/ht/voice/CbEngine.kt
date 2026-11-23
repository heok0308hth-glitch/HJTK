package com.ht.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 기기 안 음성 합성(Chatterbox 다국어, ONNX). 세 모델만 쓴다:
 *   embed_tokens(68MB) → language_model_q4(354MB, 소리 토큰 생성) → conditional_decoder(534MB, 파형)
 * 화자 조건은 [VoicePrint]로 받아서 음성 인코더(591MB)는 필요 없다.
 * 같은 입력이면 같은 소리 토큰이 나온다(탐욕 선택 + 반복 벌점) — 그래서 문장 캐시가 의미가 있다.
 */
class CbEngine(private val dir: File, private val threads: Int = 4, private val env: OrtEnvironment = OrtEnvironment.getEnvironment()) : AutoCloseable {
    private fun open(name: String): OrtSession = env.createSession(File(dir, name).path, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val embed = open(FILE_EMBED)
    private val lm = open(FILE_LM)
    private val dec = open(FILE_DEC)

    class Cancelled : RuntimeException("cancelled")

    /** 소리 토큰 생성. [cancel]이 true를 돌려주면 즉시 멈춘다(통화 끊김 등) */
    fun speechTokens(ids: LongArray, voice: VoicePrint, exaggeration: Float = 0.5f, repetition: Float = 1.2f,
                     maxNew: Int = 0, cancel: () -> Boolean = { false }): LongArray {
        val limit = if (maxNew > 0) maxNew else (ids.size * 6 + 60).coerceAtMost(600)   // 글자 수에 비례한 상한: 끝없이 웅얼대지 않게
        val ex = OnnxTensor.createTensor(env, floatArrayOf(exaggeration))
        val out = ArrayList<Long>(); out += CbTokenizer.START_SPEECH.toLong()
        val seen = HashSet<Int>(); seen += CbTokenizer.START_SPEECH
        var prev: OrtSession.Result? = null
        try {
            val pos = LongArray(ids.size) { if (ids[it] >= CbTokenizer.START_SPEECH) 0 else it - 1L }
            var x = embedOf(ids, pos, ex, prefix = voice)
            var total = x.second
            var past: Map<String, OnnxTensor> = emptyPast()
            for (i in 0 until limit) {
                if (cancel()) throw Cancelled()
                val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(total) { 1 }), longArrayOf(1, total.toLong()))
                val inputs = HashMap<String, OnnxTensor>(64); inputs["inputs_embeds"] = x.first; inputs["attention_mask"] = mask; inputs.putAll(past)
                val r = lm.run(inputs)
                x.first.close(); mask.close()
                prev?.close(); if (i == 0) past.values.forEach { it.close() }
                prev = r
                val logits = r.get(0) as OnnxTensor
                val shape = logits.info.shape; val vocab = shape[2].toInt(); val seq = shape[1].toInt()
                val buf = logits.floatBuffer
                var best = -1; var bv = Float.NEGATIVE_INFINITY
                val base = (seq - 1) * vocab
                for (v in 0 until vocab) {
                    var l = buf.get(base + v)
                    if (v in seen) l = if (l > 0) l / repetition else l * repetition
                    if (l > bv) { bv = l; best = v }
                }
                out += best.toLong(); seen += best
                if (best == CbTokenizer.STOP_SPEECH) break
                past = HashMap<String, OnnxTensor>(64).also { m -> for (k in 0 until LAYERS) { m["past_key_values.$k.key"] = r.get(1 + 2 * k) as OnnxTensor; m["past_key_values.$k.value"] = r.get(2 + 2 * k) as OnnxTensor } }
                x = embedOf(longArrayOf(best.toLong()), longArrayOf(i + 1L), ex, prefix = null)
                total += 1
            }
        } finally { prev?.close(); ex.close() }
        val gen = out.drop(1).let { if (it.lastOrNull() == CbTokenizer.STOP_SPEECH.toLong()) it.dropLast(1) else it }
        return gen.toLongArray()
    }

    /** 소리 토큰 → 24kHz 파형 */
    fun decode(tokens: LongArray, voice: VoicePrint): FloatArray {
        val all = voice.prompt + tokens
        OnnxTensor.createTensor(env, LongBuffer.wrap(all), longArrayOf(1, all.size.toLong())).use { t ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.xvec), longArrayOf(1, voice.xvec.size.toLong())).use { x ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.feat), longArrayOf(1, (voice.feat.size / 80).toLong(), 80)).use { f ->
                    dec.run(mapOf("speech_tokens" to t, "speaker_embeddings" to x, "speaker_features" to f)).use { r ->
                        val w = (r.get(0) as OnnxTensor).floatBuffer
                        return FloatArray(w.remaining()).also { w.get(it) }
                    }
                }
            }
        }
    }

    fun synth(ids: LongArray, voice: VoicePrint, exaggeration: Float = 0.5f, cancel: () -> Boolean = { false }): FloatArray {
        val t = speechTokens(ids, voice, exaggeration, cancel = cancel)
        if (cancel()) throw Cancelled()
        return if (t.isEmpty()) FloatArray(0) else decode(t, voice)
    }

    private fun embedOf(ids: LongArray, pos: LongArray, ex: OnnxTensor, prefix: VoicePrint?): Pair<OnnxTensor, Int> {
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { i ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(pos), longArrayOf(1, pos.size.toLong())).use { p ->
                embed.run(mapOf("input_ids" to i, "position_ids" to p, "exaggeration" to ex)).use { r ->
                    val e = (r.get(0) as OnnxTensor).floatBuffer
                    val n = ids.size
                    if (prefix == null) {
                        val a = FloatArray(n * DIM); e.get(a)
                        return OnnxTensor.createTensor(env, FloatBuffer.wrap(a), longArrayOf(1, n.toLong(), DIM.toLong())) to n
                    }
                    val total = prefix.condLen + n
                    val a = FloatArray(total * DIM)
                    System.arraycopy(prefix.cond, 0, a, 0, prefix.condLen * DIM)
                    e.get(a, prefix.condLen * DIM, n * DIM)
                    return OnnxTensor.createTensor(env, FloatBuffer.wrap(a), longArrayOf(1, total.toLong(), DIM.toLong())) to total
                }
            }
        }
    }

    private fun emptyPast(): Map<String, OnnxTensor> = HashMap<String, OnnxTensor>(64).also { m ->
        for (k in 0 until LAYERS) for (kv in arrayOf("key", "value"))
            m["past_key_values.$k.$kv"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong()))
    }

    override fun close() { embed.close(); lm.close(); dec.close() }

    companion object {
        const val LAYERS = 30; const val HEADS = 16; const val HEAD_DIM = 64; const val DIM = 1024; const val SR = 24000
        const val FILE_EMBED = "embed_tokens.onnx"; const val FILE_LM = "language_model_q4.onnx"; const val FILE_DEC = "conditional_decoder.onnx"
        /** 받을 파일(허깅페이스 onnx-community/chatterbox-multilingual-ONNX, MIT) — 이름과 크기 */
        val FILES: List<Pair<String, Long>> = listOf(
            "embed_tokens.onnx" to 13_286L, "embed_tokens.onnx_data" to 68_390_912L,
            "language_model_q4.onnx" to 227_911L, "language_model_q4.onnx_data" to 353_621_248L,
            "conditional_decoder.onnx" to 6_350_448L, "conditional_decoder.onnx_data" to 533_970_816L,
        )
        const val BASE_URL = "https://huggingface.co/onnx-community/chatterbox-multilingual-ONNX/resolve/main/onnx/"
    }
}
