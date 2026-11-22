package com.hjtk.app.android

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Thin TTS wrapper; init failures are reported, never thrown at the caller. */
class TtsSpeaker(context: Context) {

    private var ready = false
    private val tts: TextToSpeech? = runCatching {
        TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
    }.getOrNull()

    suspend fun speak(text: String) {
        val engine = tts ?: error("음성 엔진을 초기화하지 못했습니다.")
        if (!ready) {
            // give the engine a moment on first use
            var waited = 0
            while (!ready && waited < 1500) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }
        }
        if (!ready) error("음성 엔진이 준비되지 않았습니다.")
        engine.language = Locale.KOREAN
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hjtk-${System.currentTimeMillis()}")
        }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
    }
}
