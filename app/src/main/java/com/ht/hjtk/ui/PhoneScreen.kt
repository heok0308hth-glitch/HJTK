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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.call.AiCaller
import com.ht.hjtk.call.CallCenter
import com.ht.hjtk.core.I18n
import com.ht.hjtk.engine.Perms
import com.ht.hjtk.voice.VoiceHub
import kotlinx.coroutines.launch

/**
 * AI 전화(3.0): 기기 안에서, 무료로, 내 목소리로.
 *  01 목소리 — 모델 한 번 받기 · 들어보기 · 목소리 고르기/만들기
 *  02 전화 앱 — HJTK를 기본 전화 앱으로(통화가 HJTK 안에서 열린다)
 *  03 통화 — 목적을 적으면 대본을 짜고 전부 미리 녹음 → 걸기
 */
@Composable
fun PhoneScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val v by vm.h.voice.state.collectAsState()
    val voices by vm.h.voice.voices.collectAsState()
    val s by vm.h.caller.session.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var number by remember { mutableStateOf(vm.phonePrefill.value) }
    var goal by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf(vm.store.settings.value.userName.takeIf { it.isNotBlank() }?.let { "이름 $it" } ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var newName by remember { mutableStateOf("") }
    var making by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { making = "목소리를 분석하는 중…"; scope.launch { making = runCatching { vm.h.voice.makeVoice(u, newName); "새 목소리를 만들었어요" }.getOrElse { it.message ?: "실패" } } }
    }

    LaunchedEffect(v.pack) { if (v.pack == VoiceHub.Pack.READY) vm.h.caller.warmUp() }
    key(tick) {
    val isDialer = CallCenter.isDefaultDialer(ctx)
    val canCall = Perms.granted(ctx, Manifest.permission.CALL_PHONE)
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("AI 전화"), I18n.t("기기 안에서 · 무료 · 내 목소리로"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            SectionHead("01", I18n.t("목소리"))
            when (v.pack) {
                VoiceHub.Pack.READY -> {
                    Text(I18n.t("준비됐어요. 말하는 소리는 전부 이 폰 안에서 만들어요(인터넷·요금 없음)."), style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 8.dp))
                    voices.forEach { x ->
                        Row(Modifier.fillMaxWidth().press({ vm.h.voice.select(x.id) }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (v.voiceId == x.id) C.Red else C.Hair))
                            Spacer(Modifier.width(12.dp))
                            Text(x.name, style = T.head, modifier = Modifier.weight(1f))
                            Text(I18n.t("들어보기"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({
                                vm.h.voice.select(x.id); scope.launch { runCatching { vm.h.voice.speak("안녕하세요. 이 목소리로 대신 말해 드릴게요.") } } }).padding(8.dp))
                            if (!x.builtIn) Text(I18n.t("삭제"), style = T.small.copy(color = C.Red), modifier = Modifier.press({ vm.h.voice.deleteVoice(x.id) }).padding(8.dp))
                        }
                        Hairline()
                    }
                    if (v.busy) Label(I18n.t("소리 만드는 중…"), modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(12.dp)); Label(I18n.t("새 목소리(선택)"))
                    if (vm.h.voice.encoderReady()) {
                        Spacer(Modifier.height(6.dp))
                        Field(newName, { newName = it }, I18n.t("이름 — 본인 목소리이거나 허락받은 목소리만"))
                        Spacer(Modifier.height(8.dp))
                        GhostButton(I18n.t("녹음 파일 고르기 (10~60초)"), Modifier.fillMaxWidth()) { pick.launch(arrayOf("audio/*")) }
                    } else GhostButton(I18n.t("목소리 분석 모델 받기 · 592MB"), Modifier.fillMaxWidth().padding(top = 6.dp)) { vm.h.voice.downloadEncoder(); vm.runner.keepAlive() }
                    (making ?: v.note.takeIf { it.startsWith("목소리 분석") || it.startsWith("받기 실패") })?.let { Text(it, style = T.small.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp)) }
                }
                VoiceHub.Pack.DOWNLOADING -> {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(C.Paper2)) { Box(Modifier.fillMaxHeight().fillMaxWidth(v.progress.coerceIn(0.01f, 1f)).background(C.Ink)) }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${(v.progress * 100).toInt()}% · ${v.note}", style = T.small, modifier = Modifier.weight(1f))
                        Text(I18n.t("멈춤"), style = T.small.copy(color = C.Red, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.h.voice.cancelDownload() }).padding(8.dp))
                    }
                    Text(I18n.t("앱을 나가도 이어서 받아요. 끊기면 받은 데까지 이어받아요."), style = T.small.copy(fontSize = 12.sp, color = C.Mute))
                }
                else -> {
                    Text(I18n.t("내 목소리는 이미 학습돼 앱에 들어 있어요. 말하는 엔진만 한 번 받으면 바로 쓸 수 있어요."), style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 8.dp))
                    if (v.pack == VoiceHub.Pack.ERROR) Text(v.note, style = T.small.copy(color = C.Red, fontSize = 12.sp))
                    InkButton(I18n.t("음성 엔진 받기 · 약 960MB · 와이파이 권장"), Modifier.fillMaxWidth().padding(top = 6.dp), accent = true) { vm.h.voice.download(); vm.runner.keepAlive() }
                }
            }

            SectionHead("02", I18n.t("전화 앱"))
            NumRow(1, I18n.t("HJTK를 기본 전화 앱으로"), if (isDialer) "지정됨 · 걸기·받기·통화 화면이 HJTK 안에서 열려요" else "AI 통화 중 스피커·끊기를 HJTK가 직접 다뤄요",
                dot = if (isDialer) C.Ink else C.Red, onClick = if (isDialer) null else ({ CallCenter.roleIntent(ctx)?.let { role.launch(it) } }))
            Hairline()
            NumRow(2, I18n.t("전화 걸기 권한"), if (canCall) "허용됨" else "AI가 번호를 걸 수 있게",
                dot = if (canCall) C.Ink else C.Red, onClick = if (canCall) null else ({ perm.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)) }))

            SectionHead("03", I18n.t("AI 통화"))
            when (s.phase) {
                AiCaller.Phase.IDLE, AiCaller.Phase.ERROR, AiCaller.Phase.ENDED -> {
                    if (s.phase != AiCaller.Phase.IDLE) Text(s.note, style = T.small.copy(color = if (s.phase == AiCaller.Phase.ERROR) C.Red else C.Graphite), modifier = Modifier.padding(top = 8.dp))
                    if (s.phase == AiCaller.Phase.ENDED && s.convId != null) Text(I18n.t("대화록 보기"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.openConversation(s.convId!!) }).padding(vertical = 8.dp))
                    Spacer(Modifier.height(10.dp))
                    Field(number, { number = it }, I18n.t("번호 — 예: 02-123-4567"))
                    Spacer(Modifier.height(8.dp))
                    Field(goal, { goal = it }, I18n.t("통화 목적 — 예: 토요일 저녁 7시 4명 예약 되는지 묻고 되면 예약"), single = false)
                    Spacer(Modifier.height(8.dp))
                    Field(facts, { facts = it }, I18n.t("AI가 말해도 되는 정보(선택) — 예: 이름, 연락처"), single = false)
                    err?.let { Text(it, style = T.small.copy(color = C.Red), modifier = Modifier.padding(top = 8.dp)) }
                    Text(I18n.t("Android는 일반 앱이 통화 소리를 듣지 못하게 막아요. 그래서 상대 말은 내가 스피커로 듣고, 반응을 누르면 AI가 내 목소리로 대답해요. 대본의 모든 줄을 미리 녹음해 두어서 누르는 순간 바로 나가요."),
                        style = T.small.copy(fontSize = 12.sp, color = C.Graphite), modifier = Modifier.padding(top = 12.dp))
                }
                else -> ScriptCard(vm, s)
            }
            Text(I18n.t("첫 마디는 늘 'AI 비서'임을 밝혀요. 결제·계약에는 동의하지 않고, 카드·주민번호는 말하지 않아요. 긴급번호는 걸지 않고 하루 5통 · 같은 번호 10분 간격이에요."),
                style = T.small.copy(fontSize = 11.5.sp, color = C.Mute), modifier = Modifier.padding(top = 16.dp))
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            when (s.phase) {
                AiCaller.Phase.READY -> InkButton(I18n.t("걸기"), Modifier.fillMaxWidth(), accent = true) {
                    if (!canCall) perm.launch(arrayOf(Manifest.permission.CALL_PHONE)) else err = vm.h.caller.dial()
                }
                AiCaller.Phase.PLANNING, AiCaller.Phase.RENDERING -> GhostButton(I18n.t("취소"), Modifier.fillMaxWidth()) { vm.h.caller.reset() }
                AiCaller.Phase.CALLING, AiCaller.Phase.LIVE -> InkButton(I18n.t("통화 화면 열기"), Modifier.fillMaxWidth()) {
                    ctx.startActivity(android.content.Intent(ctx, com.ht.hjtk.call.CallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else -> InkButton(I18n.t("대본 만들고 미리 녹음"), Modifier.fillMaxWidth(), accent = number.count { it.isDigit() } >= 7 && goal.isNotBlank() && v.pack == VoiceHub.Pack.READY) {
                    err = when {
                        v.pack != VoiceHub.Pack.READY -> "01에서 음성 엔진을 먼저 받아 주세요"
                        number.count { it.isDigit() } < 7 || goal.isBlank() -> "번호와 목적을 적어 주세요"
                        else -> vm.h.caller.prepare(number.trim(), goal.trim(), facts.trim())
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ScriptCard(vm: MainVm, s: AiCaller.Session) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.number, style = T.head, modifier = Modifier.weight(1f))
            Label(when (s.phase) { AiCaller.Phase.PLANNING -> "대본 쓰는 중"; AiCaller.Phase.RENDERING -> "녹음 ${s.rendered}/${s.total}"; AiCaller.Phase.READY -> "준비 완료"; else -> s.note })
        }
        Text(s.goal, style = T.small.copy(fontSize = 12.5.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (s.total > 0) Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(3.dp).background(C.Paper2)) { Box(Modifier.fillMaxHeight().fillMaxWidth((s.rendered.toFloat() / s.total).coerceIn(0.02f, 1f)).background(if (s.phase == AiCaller.Phase.READY) C.Ink else C.Red)) }
        s.all().filter { !it.fixed }.forEachIndexed { i, x -> ScriptLine(vm, i + 1, x); Hairline() }
    }
}

@Composable
private fun ScriptLine(vm: MainVm, n: Int, x: AiCaller.Say) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(x.text) { mutableStateOf(x.text) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(n), style = T.num, modifier = Modifier.width(30.dp))
            Text(x.label, style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (!x.ready) Label(I18n.t("녹음 전"))
            else {
                Text("▶", style = T.small.copy(color = C.Ink), modifier = Modifier.press({ vm.h.caller.preview(x.key) }).padding(horizontal = 8.dp, vertical = 4.dp))
                Text(I18n.t("다시"), style = T.small.copy(color = C.Graphite), modifier = Modifier.press({ vm.h.caller.redo(x.key) }).padding(horizontal = 6.dp, vertical = 4.dp))
                Text(I18n.t("고치기"), style = T.small.copy(color = C.Graphite), modifier = Modifier.press({ editing = !editing }).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        if (editing) {
            Spacer(Modifier.height(6.dp))
            Field(text, { text = it }, "", single = false)
            Text(I18n.t("저장하고 다시 녹음"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.h.caller.edit(x.key, text.trim()); editing = false }).padding(vertical = 6.dp))
        } else Text(x.text, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.padding(start = 30.dp, top = 2.dp))
    }
}
