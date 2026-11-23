package com.ht.hjtk.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.call.AiCaller
import com.ht.hjtk.call.CallCenter
import com.ht.hjtk.call.Ear
import com.ht.hjtk.core.I18n
import com.ht.hjtk.engine.Perms
import com.ht.hjtk.voice.VoiceHub
import kotlinx.coroutines.launch

/**
 * AI 전화(3.1) — 한 화면, 버튼 하나.
 * 위: 아직 안 된 준비만 보인다(다 되면 한 줄로 접힘). 가운데: 번호·목적. 아래: 'AI에게 전화 맡기기'.
 */
@Composable
fun PhoneScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val v by vm.h.voice.state.collectAsState()
    val s by vm.h.caller.session.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var number by remember { mutableStateOf(vm.phonePrefill.value) }
    var goal by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf(vm.store.settings.value.userName.takeIf { it.isNotBlank() }?.let { "이름 $it" } ?: "") }
    var more by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    LaunchedEffect(v.pack) { if (v.pack == VoiceHub.Pack.READY) vm.h.caller.warmUp() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) { val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }; lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) } }

    key(tick) {
        val dialer = CallCenter.isDefaultDialer(ctx)
        val canCall = Perms.granted(ctx, Manifest.permission.CALL_PHONE) && Perms.granted(ctx, Manifest.permission.RECORD_AUDIO)
        val ear = Ear.isOn(ctx)
        val voiceOk = v.pack == VoiceHub.Pack.READY
        val allOk = dialer && canCall && voiceOk
        Column(Modifier.fillMaxSize().background(C.Paper)) {
            ScreenHead(vm, I18n.t("AI 전화"), if (allOk) I18n.t("준비 완료 · 무료 · 내 목소리") else I18n.t("처음 한 번만 준비해요"))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp)) {
                // ── 준비: 안 된 것만 ──
                if (!voiceOk) when (v.pack) {
                    VoiceHub.Pack.DOWNLOADING -> Step(I18n.t("음성 엔진 받는 중"), "${(v.progress * 100).toInt()}% · ${v.note}", progress = v.progress, action = I18n.t("멈춤")) { vm.h.voice.cancelDownload() }
                    else -> Step(I18n.t("음성 엔진 받기"), if (v.pack == VoiceHub.Pack.ERROR) v.note else I18n.t("약 960MB · 한 번만 · 내 목소리는 이미 들어 있어요"), action = I18n.t("받기"), primary = true) { vm.h.voice.download(); vm.runner.keepAlive() }
                }
                if (!dialer) Step(I18n.t("기본 전화 앱으로 지정"), I18n.t("통화가 HJTK 안에서 열려요"), action = I18n.t("지정")) { CallCenter.roleIntent(ctx)?.let { role.launch(it) } }
                if (!canCall) Step(I18n.t("전화·마이크 권한"), I18n.t("AI가 걸고 들을 수 있게"), action = I18n.t("허용")) { perm.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)) }
                if (!ear) Step(I18n.t("통화 귀 (선택)"), if (Ear.installed(ctx)) I18n.t("켜면 AI가 상대 말을 직접 들어요 · 마이크 허용 → 접근성 켜기") else I18n.t("함께 받은 'HJTK 통화 귀' 작은 앱을 설치하면 AI가 직접 들어요"),
                    action = if (Ear.installed(ctx)) I18n.t("켜기") else I18n.t("안내")) { Ear.setupIntent(ctx)?.let { ctx.startActivity(it) } ?: run { err = "HJTK-통화귀.apk를 설치해 주세요. Play 프로텍트가 막아도 이 작은 앱만 막히고 HJTK는 그대로예요." } }
                if (allOk) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.Red)); Spacer(Modifier.width(10.dp))
                    Text(if (ear) I18n.t("AI가 직접 듣고 말해요") else I18n.t("AI가 말하고, 상대 말은 한 줄로 적어 주면 돼요"), style = T.small.copy(color = C.Ink2), modifier = Modifier.weight(1f))
                    Text(I18n.t("내 목소리 듣기"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ scope.launch { runCatching { vm.h.voice.speak("안녕하세요. 이 목소리로 대신 통화해 드릴게요.") } } }).padding(8.dp))
                }

                // ── 통화 ──
                Spacer(Modifier.height(10.dp))
                when (s.phase) {
                    AiCaller.Phase.IDLE, AiCaller.Phase.ERROR, AiCaller.Phase.ENDED, AiCaller.Phase.READY -> {
                        if (s.phase == AiCaller.Phase.ERROR || s.phase == AiCaller.Phase.ENDED) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.note, style = T.small.copy(color = if (s.phase == AiCaller.Phase.ERROR) C.Red else C.Graphite), modifier = Modifier.weight(1f))
                            s.convId?.let { id -> Text(I18n.t("대화록"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.openConversation(id) }).padding(8.dp)) }
                        }
                        Big(number, { number = it }, I18n.t("누구에게 · 번호"))
                        Spacer(Modifier.height(10.dp))
                        Big(goal, { goal = it }, I18n.t("무엇을 · 예: 토요일 7시 4명 예약되는지 묻고 되면 예약"), lines = 3)
                        Text(if (more) I18n.t("− 알려줘도 되는 정보") else I18n.t("+ 알려줘도 되는 정보 (이름·연락처 등)"), style = T.small.copy(color = C.Graphite), modifier = Modifier.press({ more = !more }).padding(vertical = 10.dp))
                        if (more) Big(facts, { facts = it }, I18n.t("예: 이름 허태혁, 연락처는 이 번호"), lines = 2)
                        err?.let { Text(it, style = T.small.copy(color = C.Red), modifier = Modifier.padding(top = 8.dp)) }
                    }
                    else -> Live(vm, s)
                }
                Text(I18n.t("첫 마디는 늘 'AI 비서'라고 밝혀요. 결제·계약은 동의하지 않고, 카드·주민번호는 말하지 않아요. 긴급번호 금지 · 하루 5통 · 같은 번호 10분 간격."),
                    style = T.small.copy(fontSize = 11.5.sp, color = C.Mute), modifier = Modifier.padding(top = 18.dp))
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                when (s.phase) {
                    AiCaller.Phase.PLANNING, AiCaller.Phase.RENDERING -> GhostButton(I18n.t("취소"), Modifier.fillMaxWidth()) { vm.h.caller.reset() }
                    AiCaller.Phase.CALLING, AiCaller.Phase.LIVE -> InkButton(I18n.t("통화 화면 열기"), Modifier.fillMaxWidth(), accent = true) {
                        ctx.startActivity(android.content.Intent(ctx, com.ht.hjtk.call.CallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else -> InkButton(I18n.t("AI에게 전화 맡기기"), Modifier.fillMaxWidth(), accent = allOk && number.count { it.isDigit() } >= 7 && goal.isNotBlank()) {
                        err = when {
                            !allOk -> "위의 준비를 먼저 끝내 주세요"
                            number.count { it.isDigit() } < 7 || goal.isBlank() -> "번호와 할 일을 적어 주세요"
                            else -> vm.h.caller.start(number.trim(), goal.trim(), facts.trim())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, sub: String, progress: Float? = null, action: String, primary: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.Paper2).press(onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, style = T.head.copy(fontSize = 15.sp)); Text(sub, style = T.small.copy(fontSize = 12.5.sp)) }
            Text(action, style = T.small.copy(color = if (primary) C.Red else C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 10.dp))
        }
        progress?.let { p -> Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).background(C.Paper3)) { Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.01f, 1f)).background(C.Ink)) } }
    }
}

@Composable
private fun Big(value: String, onChange: (String) -> Unit, hint: String, lines: Int = 1) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Paper2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, style = T.body.copy(color = C.Mute))
        androidx.compose.foundation.text.BasicTextField(value, onChange, textStyle = T.body.copy(fontSize = 16.sp), minLines = lines, maxLines = lines + 2,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(C.Red), modifier = Modifier.fillMaxWidth())
    }
}

/** 통화 준비·진행 상태(대화록 미리보기) */
@Composable
private fun Live(vm: MainVm, s: AiCaller.Session) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Paper2).padding(16.dp)) {
        Text(prettyNumber(s.number), style = T.title)
        Text(s.goal, style = T.small, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Text(when (s.phase) { AiCaller.Phase.PLANNING -> "첫 인사를 쓰는 중…"; AiCaller.Phase.RENDERING -> "내 목소리로 만드는 중… 끝나면 바로 걸어요"; AiCaller.Phase.CALLING -> "거는 중…"; else -> s.note },
            style = T.small.copy(color = C.Red, fontWeight = FontWeight.SemiBold))
        s.opening?.let { Text(it.text, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 8.dp)) }
        s.transcript.takeLast(4).forEach { t -> Text("${t.who} · ${t.text}", style = T.small.copy(color = if (t.who == "AI") C.Ink else C.Graphite), modifier = Modifier.padding(top = 4.dp)) }
    }
}
