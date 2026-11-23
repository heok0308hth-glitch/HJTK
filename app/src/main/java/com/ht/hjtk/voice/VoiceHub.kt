package com.ht.hjtk.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ht.hjtk.engine.Thermal
import com.ht.voice.CbEngine
import com.ht.voice.CbTokenizer
import com.ht.voice.KoText
import com.ht.voice.VoicePrint
import com.ht.voice.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * HJTK의 목소리. 앱 안에서 전부 처리한다(다른 앱으로 넘기지 않는다).
 *  - 모델 3개(약 960MB)를 한 번 받는다 · 이어받기 · 크기 검증
 *  - '내 목소리'는 지문이 앱에 들어 있어 모델만 받으면 바로 말한다(음성 인코더 불필요)
 *  - 같은 문장은 한 번만 만든다(캐시) → 통화 대본을 미리 만들어 두면 통화 중에는 즉시 재생
 */
class VoiceHub(private val ctx: Context) {
    enum class Pack { MISSING, DOWNLOADING, READY, ERROR }
    data class State(val pack: Pack = Pack.MISSING, val progress: Float = 0f, val note: String = "", val busy: Boolean = false, val voiceId: String = "me")
    data class VoiceInfo(val id: String, val name: String, val builtIn: Boolean)

    val state = MutableStateFlow(State())
    val voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val modelDir = File(ctx.filesDir, "voice/model")
    private val voiceDir = File(ctx.filesDir, "voice/prints")
    private val cacheDir = File(ctx.cacheDir, "voice")
    private val synthLock = Mutex()
    @Volatile private var engine: CbEngine? = null
    private val tokenizer by lazy { CbTokenizer(ctx.assets.open("voice/cb_tok.txt").bufferedReader().lineSequence()) }
    private val prints = HashMap<String, VoicePrint>()
    private var dlJob: Job? = null

    init {
        voiceDir.mkdirs(); cacheDir.mkdirs()
        state.value = state.value.copy(pack = if (packReady()) Pack.READY else Pack.MISSING)
        refreshVoices()
    }

    fun packReady(): Boolean = CbEngine.FILES.all { (n, size) -> File(modelDir, n).length() == size }
    fun packBytes(): Long = CbEngine.FILES.sumOf { it.second }

    // ───────────── 목소리 목록 ─────────────
    fun refreshVoices() {
        val list = ArrayList<VoiceInfo>()
        list += VoiceInfo("me", "내 목소리", true)
        voiceDir.listFiles { f -> f.extension == "htvp" }?.sortedBy { it.lastModified() }?.forEach { f ->
            val name = File(f.path.removeSuffix(".htvp") + ".name").takeIf { it.exists() }?.readText() ?: f.nameWithoutExtension
            list += VoiceInfo(f.nameWithoutExtension, name, false)
        }
        voices.value = list
    }

    fun select(id: String) { state.value = state.value.copy(voiceId = id) }

    fun print(id: String = state.value.voiceId): VoicePrint = synchronized(prints) {
        prints.getOrPut(id) {
            val bytes = if (id == "me") ctx.assets.open("voice/me.htvp").readBytes() else File(voiceDir, "$id.htvp").readBytes()
            VoicePrint.read(bytes)
        }
    }

    fun deleteVoice(id: String) { if (id == "me") return; File(voiceDir, "$id.htvp").delete(); File(voiceDir, "$id.name").delete(); synchronized(prints) { prints.remove(id) }; if (state.value.voiceId == id) select("me"); refreshVoices() }

    // ───────────── 모델 받기(앱 안에서, 이어받기) ─────────────
    fun download() {
        if (dlJob?.isActive == true || packReady()) return
        dlJob = scope.launch(Dispatchers.IO) {
            modelDir.mkdirs()
            val total = packBytes()
            try {
                for ((name, size) in CbEngine.FILES) {
                    val f = File(modelDir, name)
                    if (f.length() == size) continue
                    if (f.length() > size) f.delete()
                    var tries = 0
                    while (f.length() < size) {
                        if (!isActive) return@launch
                        try { fetch(CbEngine.BASE_URL + name, f, size) {} } catch (e: Exception) {
                            if (!isActive) return@launch
                            if (++tries >= 4) throw e
                            state.value = state.value.copy(note = "연결이 끊겨 다시 이어받는 중… ($tries/3)")
                            kotlinx.coroutines.delay(2_000L * tries)
                        }
                        progress(total)
                    }
                }
                state.value = state.value.copy(pack = Pack.READY, progress = 1f, note = "준비 완료")
            } catch (e: Exception) {
                state.value = state.value.copy(pack = Pack.ERROR, note = "받기 실패: ${e.message?.take(80)} · 다시 누르면 이어받아요")
            }
        }
    }

    fun cancelDownload() { dlJob?.cancel(); state.value = state.value.copy(pack = if (packReady()) Pack.READY else Pack.MISSING, note = "멈춤 · 이어받을 수 있어요") }

    private fun progress(total: Long) {
        val got = CbEngine.FILES.sumOf { (n, s) -> File(modelDir, n).length().coerceAtMost(s) }
        state.value = state.value.copy(pack = Pack.DOWNLOADING, progress = got / total.toFloat(), note = "%.0f / %.0f MB".format(got / 1e6, total / 1e6))
    }

    private fun fetch(url: String, f: File, size: Long, onChunk: (Long) -> Unit) {
        var u = URL(url); var redirects = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                if (f.length() > 0) setRequestProperty("Range", "bytes=${f.length()}-")
                setRequestProperty("User-Agent", "HJTK/3")
            }
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); if (++redirects > 6) error("redirect loop"); continue }
            if (code !in 200..299) { c.disconnect(); error("HTTP $code") }
            val append = code == 206
            RandomAccessFile(f, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(raf.length())
                c.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16); var last = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        raf.write(buf, 0, n)
                        if (raf.length() - last > 4_000_000) { last = raf.length(); onChunk(last); progress(packBytes()) }
                        if (dlJob?.isActive != true) break   // 멈춤을 누르면 바로 끊는다
                    }
                }
            }
            c.disconnect()
            if (f.length() > size) { f.delete(); error("크기가 달라요") }
            return
        }
    }

    // ───────────── 합성 ─────────────
    private fun engine(): CbEngine = engine ?: synchronized(this) {
        engine ?: CbEngine(modelDir, threads = Thermal.threads()).also { engine = it }
    }

    /** 쓰지 않을 때 메모리(약 1GB)를 돌려준다 */
    fun release() { synchronized(this) { engine?.close(); engine = null } }

    private fun key(voice: String, text: String, ex: Float, fast: Boolean): String =
        MessageDigest.getInstance("SHA-1").digest("$voice|$ex|$fast|$text".toByteArray()).joinToString("") { "%02x".format(it) }

    fun cached(text: String, fast: Boolean = true, ex: Float = 0.5f, voice: String = state.value.voiceId): File? =
        File(cacheDir, key(voice, KoText.normalize(text), ex, fast) + ".pcm").takeIf { it.exists() && it.length() > 0 }

    /**
     * 한 문장 → 24kHz PCM(다듬은 것). fast=true면 참고 음성을 4초로 줄여 디코더가 2배 빠르다(통화용).
     * 같은 문장·목소리·설정이면 저장해 둔 소리를 바로 돌려준다.
     */
    suspend fun sentence(text: String, fast: Boolean = true, ex: Float = 0.5f, voice: String = state.value.voiceId, fresh: Boolean = false, cancel: () -> Boolean = { false }): ShortArray =
        withContext(Dispatchers.Default) {
            val norm = KoText.normalize(text)
            val f = File(cacheDir, key(voice, norm, ex, fast) + ".pcm")
            if (!fresh && f.exists() && f.length() > 0) return@withContext readPcm(f)
            check(packReady()) { "음성 모델을 먼저 받아 주세요" }
            val pcm = synthLock.withLock {
                state.value = state.value.copy(busy = true)
                try {
                    // 통화용은 참고 음성을 2.5초로: 디코더가 훨씬 빨라진다(닮음 차이는 작다)
                    val vp = print(voice).let { if (fast) it.trimmed(2.5f) else it }
                    val ids = tokenizer.encode(norm)
                    val e = engine()
                    // 다시 만들기: 같은 글이라도 강조값을 조금 흔들어 다른 소리 토큰을 얻는다(탐욕 선택이라 그대로는 같은 결과)
                    val exx = if (fresh) ex + listOf(-0.07f, 0.06f, -0.04f, 0.08f).random() else ex
                    Wav.toPcm16(Wav.polish(e.synth(ids, vp, exx, cancel)))
                } finally { state.value = state.value.copy(busy = false) }
            }
            writePcm(f, pcm); trimCache()
            pcm
        }

    private fun readPcm(f: File): ShortArray { val b = f.readBytes(); return ShortArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xff)).toShort() } }
    private fun writePcm(f: File, s: ShortArray) { val b = ByteArray(s.size * 2); for (i in s.indices) { b[2 * i] = s[i].toByte(); b[2 * i + 1] = (s[i].toInt() shr 8).toByte() }; f.writeBytes(b) }
    private fun trimCache(limit: Long = 300_000_000) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= limit) break; total -= f.length(); f.delete() }
    }

    // ───────────── 재생 ─────────────
    private var track: AudioTrack? = null
    private val playLock = Mutex()

    /** 문장 단위로 만들면서 바로 재생: 첫 문장이 끝나기 전에 다음 문장을 만든다 */
    suspend fun speak(text: String, call: Boolean = false, onSentence: (String) -> Unit = {}, cancel: () -> Boolean = { false }) {
        // 통화는 짧은 구절 단위(22자)로 잘라 첫 소리를 빨리 낸다: 첫 구절을 말하는 동안 다음 구절을 만든다
        val parts = KoText.sentences(text, if (call) 22 else 60)
        val ch = Channel<Pair<String, ShortArray>>(2)
        val producer = scope.launch {
            try { for (p in parts) { if (cancel()) break; ch.send(p to sentence(p, fast = call, cancel = cancel)) } } catch (_: Exception) {} finally { ch.close() }
        }
        playLock.withLock { for ((t, pcm) in ch) { if (cancel()) break; onSentence(t); play(pcm, call) } }
        producer.cancel()
    }

    suspend fun play(pcm: ShortArray, call: Boolean = false) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val attrs = AudioAttributes.Builder()
            .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val t = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(CbEngine.SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        track = t
        try {
            t.write(pcm, 0, pcm.size); t.play()
            val ms = pcm.size * 1000L / CbEngine.SR
            val end = System.currentTimeMillis() + ms + 120
            while (System.currentTimeMillis() < end && t.playState == AudioTrack.PLAYSTATE_PLAYING) kotlinx.coroutines.delay(40)
        } finally { runCatching { t.stop() }; t.release(); if (track === t) track = null }
    }

    fun stop() { runCatching { track?.stop() } }

    // ───────────── 새 목소리(선택): 음성 인코더 591MB를 더 받으면 기기에서 지문을 만든다 ─────────────
    val encoderFile = File(modelDir, "speech_encoder.onnx")
    val encoderData = File(modelDir, "speech_encoder.onnx_data")
    fun encoderReady() = encoderFile.length() == 1_184_608L && encoderData.length() == 591_274_880L

    fun downloadEncoder() {
        if (dlJob?.isActive == true || encoderReady()) return
        dlJob = scope.launch(Dispatchers.IO) {
            modelDir.mkdirs()
            try {
                for ((name, size) in listOf("speech_encoder.onnx" to 1_184_608L, "speech_encoder.onnx_data" to 591_274_880L)) {
                    val f = File(modelDir, name); var tries = 0
                    while (f.length() < size) {
                        if (!isActive) return@launch
                        try { fetch(CbEngine.BASE_URL + name, f, size) {} } catch (e: Exception) { if (!isActive) return@launch; if (++tries >= 4) throw e; kotlinx.coroutines.delay(2_000L * tries) }
                        state.value = state.value.copy(note = "목소리 분석 모델 %.0f%%".format((encoderData.length() * 100f / 591_274_880L)))
                    }
                }
                state.value = state.value.copy(note = "목소리 분석 모델 준비 완료")
            } catch (e: Exception) { state.value = state.value.copy(note = "받기 실패: ${e.message?.take(60)}") }
        }
    }

    /**
     * 녹음 파일 → 목소리 지문. 다듬기(저역 잡음 제거 · 무음 압축 · 크기 맞춤)만 하고
     * 숨소리·효과음을 섞거나 음색을 바꾸지 않는다.
     */
    suspend fun makeVoice(uri: Uri, name: String): String = withContext(Dispatchers.Default) {
        check(encoderReady()) { "목소리 분석 모델이 필요해요" }
        val raw = decodeAudio(uri)
        val clean = VoicePrep.clean(raw.first, raw.second)
        require(clean.size >= CbEngine.SR * 5) { "말소리가 5초 이상 필요해요(지금 ${clean.size / CbEngine.SR}초)" }
        val id = "v" + System.currentTimeMillis().toString(36)
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        env.createSession(encoderFile.path, ai.onnxruntime.OrtSession.SessionOptions()).use { s ->
            ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(clean), longArrayOf(1, clean.size.toLong())).use { t ->
                s.run(mapOf("audio_values" to t)).use { r ->
                    File(voiceDir, "$id.htvp").writeBytes(VoicePrep.pack(r))
                }
            }
        }
        File(voiceDir, "$id.name").writeText(name.ifBlank { "새 목소리" })
        refreshVoices(); id
    }

    /** 오디오 파일(mp3·m4a·wav…)을 기기 디코더로 풀어 모노 float + 샘플레이트 */
    private fun decodeAudio(uri: Uri): Pair<FloatArray, Int> {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var chs = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = java.io.ByteArrayOutputStream(); val info = MediaCodec.BufferInfo(); var inDone = false
        val limit = 90L * sr * chs * 2   // 90초면 충분
        while (true) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { codec.outputFormat.let { sr = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); chs = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } }
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!; val arr = ByteArray(info.size); b.get(arr); out.write(arr)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || out.size() > limit) break
            }
        }
        codec.stop(); codec.release(); ex.release()
        val bytes = out.toByteArray(); val frames = bytes.size / 2 / chs
        val mono = FloatArray(frames) { f -> var s = 0f; for (c in 0 until chs) { val k = (f * chs + c) * 2; s += ((bytes[k + 1].toInt() shl 8) or (bytes[k].toInt() and 0xff)).toShort() / 32768f }; s / chs }
        return mono to sr
    }

    fun exportWav(pcm: ShortArray, out: File) { out.writeBytes(Wav.encode(FloatArray(pcm.size) { pcm[it] / 32768f }, CbEngine.SR)) }

    /** 상태 요약(설정·진단용) */
    fun summary(): String = when (state.value.pack) {
        Pack.READY -> "목소리 준비됨 · ${voices.value.firstOrNull { it.id == state.value.voiceId }?.name ?: "내 목소리"}"
        Pack.DOWNLOADING -> "음성 모델 받는 중 ${(state.value.progress * 100).toInt()}%"
        Pack.ERROR -> state.value.note
        Pack.MISSING -> "음성 모델 없음(약 %.0fMB)".format(packBytes() / 1e6)
    }
}
