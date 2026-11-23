package com.ht.hjtk.call

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/** 켜져 있기만 하면 되는 접근성 서비스(화면을 읽지 않는다). 켜져 있는 동안 HJTK는 통화 중 마이크를 쓸 수 있다 */
class EarService : AccessibilityService() {
    override fun onServiceConnected() { Ear.enabled.value = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { Ear.enabled.value = false; super.onDestroy() }
}

/**
 * 통화 귀: 스피커로 나오는 상대 목소리를 마이크로 듣고 → 말이 끝나면(0.8초 조용) → 받아 적는다.
 * AI가 말하는 동안은 듣지 않는다(자기 목소리를 받아 적지 않게). 녹음은 저장하지 않는다.
 * 받아 적기: Android 13+ 기기 음성 인식에 이 소리를 직접 넣는다(무료·기기 안). 안 되면 Gemini 키, 그것도 없으면 직접 적기.
 */
object Ear {
    val enabled = MutableStateFlow(false)
    val level = MutableStateFlow(0f)          // 0..1 듣는 소리 크기(화면 표시)
    val listening = MutableStateFlow(false)
    @Volatile var muted = false                // AI가 말하는 중
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    const val SR = 16000

    fun isOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.endsWith("/.call.EarService") || it.endsWith("/com.ht.hjtk.call.EarService") }.also { enabled.value = it }
    }
    fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 듣기 시작: 말 한 덩어리가 끝날 때마다 [onUtterance]에 PCM(16kHz) */
    @SuppressLint("MissingPermission")
    fun start(onUtterance: suspend (ShortArray) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR)) }.getOrNull() ?: return@launch
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@launch }
            rec.startRecording(); listening.value = true
            val frame = ShortArray(SR / 50)                // 20ms
            val seg = ArrayList<Short>(SR * 20)
            var noise = 300.0; var speech = 0; var quiet = 0; var silentAll = 0
            try {
                while (isActive) {
                    val n = rec.read(frame, 0, frame.size); if (n <= 0) continue
                    var e = 0.0; for (i in 0 until n) e += frame[i] * frame[i].toDouble(); val rms = sqrt(e / n)
                    level.value = (rms / 4000).toFloat().coerceIn(0f, 1f)
                    if (rms < 1.0) silentAll++ else silentAll = 0
                    if (muted) { seg.clear(); speech = 0; quiet = 0; continue }
                    val voiced = rms > maxOf(noise * 2.8, 450.0)
                    if (!voiced) noise = noise * 0.98 + rms * 0.02
                    if (voiced) { speech++; quiet = 0 } else if (speech > 0) quiet++
                    if (speech > 0) for (i in 0 until n) seg += frame[i]
                    // 0.4초 이상 말하고 0.8초 조용하면 한 덩어리(최대 15초)
                    if ((speech >= 20 && quiet >= 40) || seg.size > SR * 15) {
                        val pcm = seg.toShortArray(); seg.clear(); speech = 0; quiet = 0
                        onUtterance(pcm)
                    } else if (speech in 1..19 && quiet >= 40) { seg.clear(); speech = 0; quiet = 0 }   // 짧은 잡음은 버린다
                }
            } finally { runCatching { rec.stop() }; rec.release(); listening.value = false; level.value = 0f }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** 받아 적기(기기 음성 인식 · Android 13+) — 실패하면 null */
    suspend fun transcribe(ctx: Context, pcm: ShortArray): String? {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CompletableDeferred<String?>()
        val sr = withContext(Dispatchers.Main) {
            val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { done.complete(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
                override fun onError(error: Int) { done.complete(null) }
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SR)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, false))
            r
        }
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                    val b = ByteArray(pcm.size * 2); for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                    o.write(b); o.write(ByteArray(SR))   // 끝에 0.5초 무음: 인식기가 말이 끝났다고 알게
                }
            }
        }
        val text = withTimeoutOrNull(12_000) { done.await() }
        withContext(Dispatchers.Main) { runCatching { sr.destroy() } }
        runCatching { pipe[0].close() }
        return text?.trim()?.ifBlank { null }
    }
}
