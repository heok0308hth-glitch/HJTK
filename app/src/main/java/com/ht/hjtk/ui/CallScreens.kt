package com.ht.hjtk.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.Hjtk
import com.ht.hjtk.MainActivity
import com.ht.hjtk.call.AiCaller
import com.ht.hjtk.call.CallCenter
import com.ht.hjtk.core.I18n
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Night = Color(0xFF111113)
private val Night2 = Color(0xFF1C1C1F)
private val Night3 = Color(0xFF2A2A2E)
private val Dim = Color(0xFF8E8E93)

/** 통화 화면: 수신 · 통화 중(+AI 대본) · 다이얼. 어두운 바탕 하나, 붉은색은 '끊기'와 'AI가 말하는 중'에만 */
@Composable
fun CallScreen(h: Hjtk, initialNumber: String, onClose: () -> Unit) {
    val lines by CallCenter.lines.collectAsState()
    val cur = lines.firstOrNull { it.ringing } ?: lines.firstOrNull { !it.ended }
    var hadCall by remember { mutableStateOf(false) }
    LaunchedEffect(cur) { if (cur != null) hadCall = true else if (hadCall) { delay(900); onClose() } }
    Box(Modifier.fillMaxSize().background(Night)) {
        when {
            cur == null -> Dialer(h, initialNumber)
            cur.ringing -> Incoming(h, cur)
            else -> InCall(h, cur)
        }
    }
}

@Composable
private fun Head(status: String, title: String, sub: String, live: Boolean = false) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 36.dp, start = 28.dp, end = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(status.uppercase(), style = T.mono.copy(color = if (live) C.Red else Dim, letterSpacing = 1.5.sp, fontSize = 12.sp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = T.display.copy(color = Color.White, fontSize = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (sub.isNotBlank()) Text(sub, style = T.body.copy(color = Dim), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundKey(icon: I?, label: String, on: Boolean = false, fill: Color = Night3, size: Int = 64, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(if (on) Color.White else fill).press(onClick), contentAlignment = Alignment.Center) {
            if (icon != null) Ico(icon, (size * 0.4f).dp, if (on) Night else Color.White)
        }
        if (label.isNotBlank()) Text(label, style = T.small.copy(color = Dim, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Incoming(h: Hjtk, l: CallCenter.Line) {
    val scope = rememberCoroutineScope()
    val voiceReady = remember { h.voice.packReady() }
    Column(Modifier.fillMaxSize()) {
        Head(I18n.t("전화가 왔어요"), l.name ?: prettyNumber(l.number), if (l.name != null) prettyNumber(l.number) else "")
        Spacer(Modifier.weight(1f))
        if (voiceReady) {
            Text(I18n.t("내 목소리로 대신 받기"), style = T.mono.copy(color = Dim, fontSize = 11.sp), modifier = Modifier.padding(start = 28.dp, bottom = 8.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCaller.INCOMING.forEach { t ->
                    Text(t, style = T.body.copy(color = Color.White, fontSize = 14.sp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Night2)
                        .press({ scope.launch { CallCenter.answer(l.id); delay(700); CallCenter.speaker(true); h.voice.speak(AiCaller.INCOMING_PREFIX + t, call = true); delay(400); CallCenter.end(l.id) } }).padding(14.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(I.Close, I18n.t("거절"), fill = C.Red, size = 72) { CallCenter.reject(l.id) }
            RoundKey(I.Phone, I18n.t("받기"), fill = Color(0xFF2E7D4F), size = 72) { CallCenter.answer(l.id) }
        }
    }
}

@Composable
private fun InCall(h: Hjtk, l: CallCenter.Line) {
    val audio by CallCenter.audio.collectAsState()
    val ai by h.caller.session.collectAsState()
    var keypad by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = when {
        l.active -> "%02d:%02d".format((now - l.since) / 60000, (now - l.since) / 1000 % 60)
        l.state == android.telecom.Call.STATE_HOLDING -> I18n.t("보류 중")
        else -> I18n.t("거는 중")
    }
    val aiLive = l.ai && ai.phase in setOf(AiCaller.Phase.CALLING, AiCaller.Phase.LIVE)
    Column(Modifier.fillMaxSize()) {
        Head((if (l.ai) "AI · " else "") + status, l.name ?: prettyNumber(l.number), if (l.name != null) prettyNumber(l.number) else "", live = ai.speaking != null && aiLive)
        Spacer(Modifier.height(12.dp))
        if (aiLive) AiPanel(h, ai, Modifier.weight(1f)) else if (keypad) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Pad { c -> CallCenter.dtmf(l.id, c) } } else Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(I.Mic, I18n.t("음소거"), on = audio.muted) { CallCenter.mute(!audio.muted) }
            RoundKey(I.Speaker, I18n.t("스피커"), on = audio.speaker) { CallCenter.speaker(!audio.speaker) }
            RoundKey(I.Keypad, I18n.t("키패드"), on = keypad) { keypad = !keypad }
            RoundKey(I.Pause, I18n.t("보류"), on = l.state == android.telecom.Call.STATE_HOLDING) { CallCenter.hold(l.id, l.state != android.telecom.Call.STATE_HOLDING) }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
            RoundKey(I.Phone, "", fill = C.Red, size = 70) { CallCenter.end(l.id) }
        }
    }
}

/** AI 통화 패널: 상황 버튼 없이 — 듣기·생각·말하기 상태와 대화록, 그리고 필요할 때만 한 줄 입력 */
@Composable
private fun AiPanel(h: Hjtk, s: AiCaller.Session, modifier: Modifier) {
    var note by remember { mutableStateOf("") }
    val ear by com.ht.hjtk.call.Ear.listening.collectAsState()
    val lvl by com.ht.hjtk.call.Ear.level.collectAsState()
    val list = rememberScrollState()
    LaunchedEffect(s.transcript.size) { list.animateScrollTo(list.maxValue) }
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Night2).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val (label, col) = when {
                s.paused -> I18n.t("AI 멈춤 · 직접 말해도 돼요") to Dim
                s.mind == AiCaller.Mind.SPEAKING -> I18n.t("말하는 중") to C.Red
                s.mind == AiCaller.Mind.THINKING -> I18n.t("생각하는 중") to Color.White
                ear -> I18n.t("듣는 중") to Color.White
                else -> I18n.t("상대 말을 한 줄로 적어 주세요") to Dim
            }
            Ico(I.Wave, 18.dp, col); Spacer(Modifier.width(10.dp))
            Text(label, style = T.head.copy(color = col, fontSize = 14.sp), modifier = Modifier.weight(1f))
            if (ear && s.mind != AiCaller.Mind.SPEAKING) Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Night3)) { Box(Modifier.fillMaxHeight().fillMaxWidth(lvl.coerceIn(0.02f, 1f)).background(Color.White)) }
            Text(if (s.paused) I18n.t("재개") else I18n.t("멈춤"), style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold), modifier = Modifier.press({ h.caller.pause(!s.paused) }).padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp).verticalScroll(list), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.transcript.forEach { t ->
                val ai = t.who == "AI"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (ai) Arrangement.End else Arrangement.Start) {
                    Text(t.text, style = T.body.copy(color = if (ai) Night else Color.White, fontSize = 14.5.sp),
                        modifier = Modifier.fillMaxWidth(0.82f).clip(RoundedCornerShape(14.dp)).background(if (ai) Color(0xFFEDEAE3) else Night2).padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Night2).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                if (note.isEmpty()) Text(if (ear) I18n.t("AI 대신 할 말 (선택)") else I18n.t("상대가 한 말을 짧게"), style = T.small.copy(color = Dim))
                BasicTextField(note, { note = it }, textStyle = T.body.copy(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(C.Red), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.size(46.dp).press({ val t = note.trim(); if (t.isNotEmpty()) { if (ear) h.caller.sayText(t) else h.caller.heard(t) }; note = "" }), contentAlignment = Alignment.Center) { Ico(I.Send, 20.dp, if (note.isBlank()) Dim else Color.White) }
        }
    }
}

@Composable
private fun Pad(onKey: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("123", "456", "789", "*0#").forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                r.forEach { c ->
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Night2).press({ onKey(c) }), contentAlignment = Alignment.Center) {
                        Text(c.toString(), style = T.display.copy(color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun Dialer(h: Hjtk, initial: String) {
    val ctx = LocalContext.current
    var num by remember(initial) { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(56.dp))
        Text(prettyNumber(num).ifEmpty { " " }, style = T.display.copy(color = Color.White, fontSize = 34.sp, letterSpacing = 1.sp), maxLines = 1)
        Text(err ?: if (CallCenter.isDefaultDialer(ctx)) "" else I18n.t("통화 버튼을 누르면 기본 전화 앱 지정 창이 떠요"), style = T.small.copy(color = if (err != null) C.Red else Dim), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Pad { c -> num += c }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("AI에게"), style = T.small.copy(color = Color.White, fontWeight = FontWeight.SemiBold), modifier = Modifier.press({
                ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "phone").putExtra("number", num).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }).padding(12.dp))
            RoundKey(I.Phone, "", fill = Color(0xFF2E7D4F), size = 72) {
                if (!CallCenter.isDefaultDialer(ctx)) { CallCenter.roleIntent(ctx)?.let { (ctx as? android.app.Activity)?.startActivityForResult(it, 7) }; err = I18n.t("기본 전화 앱으로 지정하면 HJTK 안에서 바로 걸려요") }
                else if (num.isNotBlank()) err = CallCenter.place(ctx, num)
            }
            Text(I18n.t("지우기"), style = T.small.copy(color = Color.White, fontWeight = FontWeight.SemiBold), modifier = Modifier.press({ num = num.dropLast(1) }).padding(12.dp))
        }
        Spacer(Modifier.height(36.dp))
    }
}

/** 한국 번호 보기 좋게: 02-1234-5678 · 010-1234-5678 · 1588-1234 */
internal fun prettyNumber(n: String): String {
    val d = n.filter { it.isDigit() }
    if (d.length != n.length || d.length < 8) return n
    return when {
        d.startsWith("02") && d.length in 9..10 -> "02-" + d.substring(2, d.length - 4) + "-" + d.takeLast(4)
        d.length == 8 -> d.take(4) + "-" + d.takeLast(4)
        d.length in 10..11 -> d.take(3) + "-" + d.substring(3, d.length - 4) + "-" + d.takeLast(4)
        else -> n
    }
}
