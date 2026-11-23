package com.ht.hjtk.ear

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 통화 귀: HJTK가 "듣기 시작"을 보내면 마이크(스피커로 나오는 상대 목소리 포함)를 듣고,
 * 말이 끝날 때마다(0.8초 조용) 기기 음성 인식으로 받아 적어 HJTK에 글로 돌려준다.
 * HJTK가 말하는 동안은 듣지 않는다(MUTE). 소리는 저장하지 않는다. 화면 내용은 읽지 않는다.
 */
class EarService : AccessibilityService() {
    @Volatile private var running = false
    @Volatile private var muted = false
    private val main = Handler(Looper.getMainLooper())
    private val cmd = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                START -> start(); STOP -> running = false
                MUTE -> muted = true; UNMUTE -> muted = false
            }
        }
    }

    override fun onServiceConnected() {
        val f = IntentFilter().apply { addAction(START); addAction(STOP); addAction(MUTE); addAction(UNMUTE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(cmd, f, PERM, null, RECEIVER_EXPORTED) else registerReceiver(cmd, f, PERM, null)
        tell("state", "on")
    }
    override fun onDestroy() { running = false; runCatching { unregisterReceiver(cmd) }; tell("state", "off"); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun tell(k: String, v: String) = sendBroadcast(Intent(TEXT).setPackage("com.ht.hjtk").putExtra(k, v), PERM)

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running) return
        running = true
        thread(name = "ear") {
            val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR)) }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); tell("error", "마이크를 열 수 없어요(통화 귀 앱에서 마이크 허용)"); running = false; return@thread }
            rec.startRecording(); tell("state", "listening")
            val frame = ShortArray(SR / 50); val seg = ArrayList<Short>(SR * 15)
            var noise = 300.0; var speech = 0; var quiet = 0; var lastLevel = 0L
            try {
                while (running) {
                    val n = rec.read(frame, 0, frame.size); if (n <= 0) continue
                    var e = 0.0; for (i in 0 until n) e += frame[i] * frame[i].toDouble(); val rms = sqrt(e / n)
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 200) { lastLevel = now; tell("level", "%.2f".format((rms / 4000).coerceIn(0.0, 1.0))) }
                    if (muted) { seg.clear(); speech = 0; quiet = 0; continue }
                    val voiced = rms > maxOf(noise * 2.8, 450.0)
                    if (!voiced) noise = noise * 0.98 + rms * 0.02
                    if (voiced) { speech++; quiet = 0 } else if (speech > 0) quiet++
                    if (speech > 0) for (i in 0 until n) seg += frame[i]
                    if ((speech >= 20 && quiet >= 40) || seg.size > SR * 15) {
                        val pcm = seg.toShortArray(); seg.clear(); speech = 0; quiet = 0
                        tell("heard", "1")             // HJTK가 바로 맞장구를 칠 수 있게(받아 적기 전에)
                        transcribe(pcm) { t -> if (t != null) tell("text", t) else tell("miss", "1") }
                    } else if (speech in 1..19 && quiet >= 40) { seg.clear(); speech = 0; quiet = 0 }
                }
            } finally { runCatching { rec.stop() }; rec.release(); tell("state", "idle") }
        }
    }

    /** 기기 음성 인식에 이 소리를 직접 넣는다(Android 13+) */
    private fun transcribe(pcm: ShortArray, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(this)) { done(null); return }
        main.post {
            val pipe = ParcelFileDescriptor.createPipe()
            val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            var finished = false
            fun finish(t: String?) { if (finished) return; finished = true; runCatching { r.destroy() }; runCatching { pipe[0].close() }; done(t?.trim()?.ifBlank { null }) }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) = finish(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SR))
            thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                    val b = ByteArray(pcm.size * 2); for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                    o.write(b); o.write(ByteArray(SR)) } }
            }
            main.postDelayed({ finish(null) }, 10_000)
        }
    }

    companion object {
        const val SR = 16000
        const val PERM = "com.ht.hjtk.permission.EAR"
        const val START = "com.ht.hjtk.ear.START"; const val STOP = "com.ht.hjtk.ear.STOP"
        const val MUTE = "com.ht.hjtk.ear.MUTE"; const val UNMUTE = "com.ht.hjtk.ear.UNMUTE"
        const val TEXT = "com.ht.hjtk.EAR_TEXT"
    }
}
