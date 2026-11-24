package com.ht.hjtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.core.I18n
import com.ht.ensemble.ai.Provider
import com.ht.hjtk.engine.AiHealth
import com.ht.hjtk.engine.Scoreboard
import kotlinx.coroutines.launch

/**
 * AI 상태 — 다섯 AI가 지금 팀에 들어올 수 있는지, 최근에 왜 실패했는지, 실제로 답하는지 한 화면에서.
 * '모두 점검'은 각 AI에 1+1을 물어 실제 경로를 시험한다. 실패하면 사람 말 이유와 화면 진단(복사해서 보낼 수 있음).
 */
@Composable
fun AiHealthScreen(vm: MainVm) {
    val scope = rememberCoroutineScope()
    val results by AiHealth.results.collectAsState()
    val running by AiHealth.running.collectAsState()
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("AI 상태", "팀에 들어올 수 있는지 · 실패 이유 · 실제로 답하는지", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(if (running.isNotEmpty()) "점검 중… ${running.size}" else I18n.t("모두 점검"), accent = true) {
                    if (running.isEmpty()) AiHealth.ALL.filter { AiHealth.route(vm.h, it) != null }.forEach { p -> scope.launch { AiHealth.check(vm.h, p) } }
                }
                GhostButton(if (copied) "복사했어요" else I18n.t("진단 복사")) { clip.setText(AnnotatedString(AiHealth.report(vm.h))); copied = true }
            }
            Text(I18n.t("점검은 각 AI에 '1+1'을 한 번 물어봐요. 실패하면 이유와 화면 진단을 남기니, 복사해서 보내 주시면 사이트에 맞춰 고칠 수 있어요."), style = T.small.copy(fontSize = 12.sp, color = C.Graphite))
            AiHealth.ALL.forEach { p -> HealthRow(vm, p, results[p], p in running) { scope.launch { AiHealth.check(vm.h, p) } } }
        }
    }
}

@Composable
private fun HealthRow(vm: MainVm, p: Provider, c: AiHealth.Check?, busy: Boolean, onCheck: () -> Unit) {
    val route = AiHealth.route(vm.h, p)
    val member = vm.h.ai.members().firstOrNull { it.provider == p }
    val st = member?.let { Scoreboard.snapshot()[it.key] }
    val resting = member?.let { Scoreboard.resting(it.key) } == true
    val (pill, pillOn) = when {
        route == null -> "연결 안 됨" to false
        busy -> "점검 중" to true
        c?.ok == false -> "문제 있음" to false
        resting -> "쉬는 중" to false
        c?.ok == true -> "정상" to true
        else -> "준비됨" to true
    }
    FoldCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(providerTint(p.name)))
            Spacer(Modifier.width(9.dp))
            Text(p.label, style = T.head.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text(pill, style = T.small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (pillOn) C.Ink else C.OnInk),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (pillOn) C.Paper2 else C.Red).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (route == null) "팀에 없어요 — 로그인하지 않았거나 풀렸어요. 허브 → 계정 하나로에서 연결하면 바로 일해요." else "팀에 들어와요 · $route", style = T.small.copy(fontSize = 12.5.sp, color = C.Ink2))
        st?.let { s ->
            Text("최근 성공 ${s.ok} · 실패 ${s.fail}" + (if (s.avgMs > 0) " · 평균 ${"%.0f".format(s.avgMs / 1000)}초" else "") +
                (s.lastVia.takeIf { it.isNotBlank() && s.lastFailAt > s.lastOkAt }?.let { " · 마지막 실패: ${Scoreboard.reason(it)}" } ?: "") +
                (if (resting) " · ${s.lastNote}" else ""), style = T.mono.copy(fontSize = 10.5.sp, color = C.Graphite), modifier = Modifier.padding(top = 4.dp))
        }
        c?.let { r ->
            Text(if (r.ok) "점검 통과 · ${"%.1f".format(r.ms / 1000.0)}초 · 답 '${r.answer}'" else "점검 실패 · ${r.reason}",
                style = T.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (r.ok) C.Ink else C.Red), modifier = Modifier.padding(top = 6.dp))
            r.probe?.let { pr -> Text(probeLine(pr), style = T.mono.copy(fontSize = 10.sp, color = C.Graphite), modifier = Modifier.padding(top = 3.dp), maxLines = 3) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (route != null) GhostButton(if (busy) "점검 중…" else I18n.t("점검")) { if (!busy) onCheck() }
            GhostButton(I18n.t(if (route == null) "로그인하러 가기" else "로그인 화면")) { vm.go(Route.AiLogin) }
        }
    }
}

/** 진단 JSON → 한 줄: 무엇을 찾았고 무엇을 못 찾았나 */
private fun probeLine(j: String): String = runCatching {
    val o = org.json.JSONObject(j)
    listOf(
        "입력창 " + if (o.optString("input").isNotBlank()) "찾음" else if (o.optBoolean("inputGeneric")) "일반 규칙으로 찾음" else "없음",
        "보내기 " + if (o.optString("send").isNotBlank()) "찾음" else "없음",
        "답 ${o.optInt("answersCfg")}/${o.optInt("answersAll")}",
        if (o.optBoolean("challenge")) "보안 확인 화면" else if (o.optBoolean("login")) "로그인 문구 보임" else "",
        o.optString("url").substringAfter("://").take(40)
    ).filter { it.isNotBlank() }.joinToString(" · ")
}.getOrDefault(j.take(120))
