package com.ht.hjtk.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.core.I18n
import com.ht.hjtk.engine.LiveBus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 실시간 음성 대화. 폰의 기본 음성 인식·읽어 주기(시스템 서비스)를 쓰고, AI는 가장 빠른 모델이 맡는다.
 * 답은 문장이 끝나는 대로 읽기 시작해 기다림을 줄이고, '손 안 대고 이어서'를 켜면 읽기가 끝나는 즉시 다시 듣는다.
 */
@Composable
fun LiveScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val en = I18n.en
    var granted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var state by remember { mutableStateOf("idle") }   // idle · listen · think · speak
    var level by remember { mutableStateOf(0f) }
    var partial by remember { mutableStateOf("") }
    var handsFree by remember { mutableStateOf(true) }
    var who by remember { mutableStateOf("") }
    val turns = remember { mutableStateListOf<Pair<Boolean, String>>() }
    val list = rememberLazyListState()
    val lines by LiveBus.lines.collectAsState()
    val streaming = lines["live"]?.text.orEmpty()

    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    val spoken = remember { intArrayOf(0) }
    val listenRef = remember { arrayOfNulls<() -> Unit>(1) }

    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx) { st -> ttsReady = st == TextToSpeech.SUCCESS; tts[0]?.language = if (en) Locale.US else Locale.KOREAN }
        onDispose { runCatching { recognizer?.destroy() }; runCatching { tts[0]?.stop(); tts[0]?.shutdown() } }
    }

    fun speak(text: String, flush: Boolean) {
        if (!ttsReady || text.isBlank()) return
        tts[0]?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    // 스트리밍되는 답을 문장 단위로 바로 읽는다
    LaunchedEffect(streaming) {
        if (state != "think" && state != "speak") return@LaunchedEffect
        val done = Regex("[.!?。…]\\s|[.!?。]$|\\n").findAll(streaming).lastOrNull()?.range?.last ?: -1
        if (done + 1 > spoken[0]) { speak(streaming.substring(spoken[0], done + 1).trim(), false); spoken[0] = done + 1; state = "speak" }
    }

    fun ask(q: String) {
        turns += true to q; partial = ""; state = "think"; spoken[0] = 0
        scope.launch {
            val (name, ans) = runCatching { vm.runner.liveAsk(q, en) }.getOrDefault("AI" to "")
            who = name
            val text = ans.ifBlank { if (en) "Sorry, I couldn't get an answer." else "답을 받지 못했어요." }
            turns += false to text
            if (spoken[0] < text.length) speak(text.substring(spoken[0].coerceAtMost(text.length)), spoken[0] == 0)
            state = "speak"
            tts[0]?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) {}
                override fun onDone(id: String?) { if (tts[0]?.isSpeaking == false) android.os.Handler(android.os.Looper.getMainLooper()).post { state = "idle"; if (handsFree) listenRef[0]?.invoke() } }
            })
            if (!ttsReady) { state = "idle" }
            list.animateScrollToItem((turns.size - 1).coerceAtLeast(0))
        }
    }

    fun listen() {
        val r = recognizer ?: return
        tts[0]?.stop()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { state = "listen" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(e: Int) { state = "idle"; level = 0f }
            override fun onResults(b: Bundle?) { val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (t.isNotBlank()) ask(t) else state = "idle" }
            override fun onPartialResults(b: Bundle?) { partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (en) "en-US" else "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }
    listenRef[0] = { listen() }

    Column(Modifier.fillMaxSize().background(C.Ink)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).press({ recognizer?.cancel(); tts[0]?.stop(); vm.back() }), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp, C.OnInk) }
            Column(Modifier.weight(1f)) {
                Text(I18n.t("실시간 음성 대화"), style = T.title.copy(color = C.OnInk))
                Label(who.ifBlank { I18n.t("가장 빠른 AI가 답해요") }, color = C.OnInkMute)
            }
            Text(if (handsFree) I18n.t("손 안 대고 · 켬") else I18n.t("손 안 대고 · 끔"), style = T.small.copy(color = if (handsFree) C.OnInk else C.OnInkMute, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.press({ handsFree = !handsFree }).padding(8.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), state = list) {
            items(turns) { (me, t) ->
                Text(t, style = (if (me) T.body.copy(color = C.OnInkMute) else T.head.copy(color = C.OnInk, fontSize = 18.sp, lineHeight = 27.sp)), modifier = Modifier.padding(vertical = 8.dp))
            }
            if (state == "listen" && partial.isNotBlank()) item { Text(partial, style = T.body.copy(color = C.OnInkMute), modifier = Modifier.padding(vertical = 8.dp)) }
            if (state == "think" && streaming.isNotBlank()) item { Text(streaming, style = T.head.copy(color = C.OnInk, fontSize = 18.sp), modifier = Modifier.padding(vertical = 8.dp)) }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(when (state) { "listen" -> I18n.t("듣는 중"); "think" -> I18n.t("생각하는 중"); "speak" -> I18n.t("말하는 중 · 누르면 멈춤"); else -> if (granted) I18n.t("누르고 말하기") else I18n.t("마이크 허용이 필요해요") }, color = C.OnInkMute)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(C.Ink3, r * (0.62f + 0.38f * level), style = Stroke(width = 2.dp.toPx()))
                    if (state == "listen") drawCircle(C.Red, r * (0.56f + 0.3f * level), center = Offset(size.width / 2, size.height / 2), style = Stroke(width = 3.dp.toPx()))
                }
                Box(Modifier.size(84.dp).clip(CircleShape).background(if (state == "listen") C.Red else C.Paper).press({
                    when {
                        !granted -> perm.launch(Manifest.permission.RECORD_AUDIO)
                        state == "speak" -> { tts[0]?.stop(); state = "idle" }
                        state == "listen" -> recognizer?.stopListening()
                        state == "idle" -> listen()
                    }
                }), contentAlignment = Alignment.Center) {
                    Text(if (state == "listen") "■" else if (state == "speak") "Ⅱ" else "●", style = T.title.copy(color = if (state == "listen") C.OnInk else C.Ink))
                }
            }
            if (recognizer == null) Text(I18n.t("이 기기에는 음성 인식 서비스가 없어요"), style = T.small.copy(color = C.Red), modifier = Modifier.padding(top = 10.dp))
        }
    }
}
