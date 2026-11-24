package com.ht.hjtk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.ensemble.agent.StageBus
import com.ht.hjtk.engine.LiveBus
import kotlinx.coroutines.delay

/** 회사마다 아주 옅은 고유색(점·고리에만). 글자·배경은 늘 먹/종이색 */
fun providerTint(p: String): Color = when (p.uppercase()) {
    "CLAUDE" -> Color(0xFFD97757); "GPT" -> Color(0xFF19A37F); "GEMINI" -> Color(0xFF5B8DEF); "GROK" -> Color(0xFFBDBDBD); "DEEPSEEK" -> Color(0xFF5B6CFF); else -> C.Red
}

/**
 * 작업 무대: 센터가 가운데에서 지휘하고, 팀원 카드가 '누가 · 무엇을 · 지금 쓰는 글'을 보여 준다.
 * 일하는 카드는 회사색 고리가 돌고, 쓰는 글이 한 줄로 흐르며, 글자 위로 빛이 지나간다(뜨거우면 멈춘다).
 */
@Composable
fun StageView(jobId: String, dark: Boolean, compact: Boolean = false) {
    val stages by StageBus.stages.collectAsState()
    val st = stages[jobId] ?: return
    val live by LiveBus.lines.collectAsState()
    val fg = if (dark) C.OnInk else C.Ink
    val sub = if (dark) C.OnInkMute else C.Graphite
    val animate = Motion.enabled && !Motion.test
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { if (!Motion.test) while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // 센터
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(st.center, st.centerProvider, 34.dp, active = st.phase != "완료", animate = animate, dark = dark)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (st.budgetMin > 0) "PM · " else "센터 · ") + st.center, style = T.head.copy(color = fg, fontSize = 14.5.sp))
                Text(phaseLine(st), style = T.small.copy(color = sub, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PhasePill(st.phase, dark)
        }
        val project = st.budgetMin > 0
        if (project) ProjectStrip(st, now, dark)
        // 팀원(프로젝트면 티켓 보드: 진행·리뷰는 모두, 할 일은 앞 3개, 완료는 최근 4개)
        val cards = when {
            compact -> st.cards.filter { !project || it.status in setOf(StageBus.S.RUN, StageBus.S.CHECK, StageBus.S.SWAP) }.ifEmpty { st.cards.takeLast(2) }.sortedBy { order(it.status) }.take(4)
            project -> st.cards.filter { it.status in setOf(StageBus.S.RUN, StageBus.S.CHECK, StageBus.S.SWAP, StageBus.S.FAIL) } +
                st.cards.filter { it.status == StageBus.S.WAIT }.take(3) + st.cards.filter { it.status == StageBus.S.DONE }.takeLast(4).reversed()
            else -> st.cards
        }
        Column(Modifier.padding(start = 16.dp, top = 6.dp)) {
            cards.forEachIndexed { i, c ->
                key(c.id) {
                    var shown by remember { mutableStateOf(!animate) }
                    LaunchedEffect(Unit) { delay(60L * i); shown = true }
                    AnimatedVisibility(shown, enter = fadeIn(tween(260)) + expandVertically(tween(260))) {
                        CardRow(c, LiveBus.of(jobId, live.filterValues { it.who.startsWith(c.who) || it.who.contains(c.who) })?.text?.takeIf { c.status == StageBus.S.RUN || c.status == StageBus.S.SWAP },
                            now, dark, animate, last = i == cards.lastIndex)
                    }
                }
            }
            if (compact && st.cards.size > cards.size) Text("+${st.cards.size - cards.size}", style = T.small.copy(color = sub, fontSize = 12.sp), modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
        if (project && !compact && st.files.isNotEmpty()) FilesStrip(st.files, dark)
        // 칠판: AI끼리 주고받은 메모(누가 · 무엇을 알렸나)
        if (st.board.isNotEmpty()) BoardStrip(if (compact) st.board.takeLast(1) else st.board.takeLast(3), dark)
        Spacer(Modifier.height(10.dp))
        FlowBar(st.progress.toFloat(), animate, dark)
    }
}

private fun order(s: StageBus.S) = when (s) { StageBus.S.RUN, StageBus.S.SWAP, StageBus.S.CHECK -> 0; StageBus.S.WAIT -> 1; StageBus.S.FAIL -> 2; StageBus.S.DONE -> 3 }

private fun phaseLine(st: StageBus.Stage): String {
    val run = st.cards.filter { it.status == StageBus.S.RUN || it.status == StageBus.S.SWAP || it.status == StageBus.S.CHECK }
    return when {
        st.phase == "설계" -> "목표를 나누고 팀원을 고르는 중"
        st.phase == "완료" -> "${st.cards.size}개 일을 ${st.cards.map { it.who }.distinct().size}명이 나눠 끝냈어요"
        run.size > 1 -> "${run.size}명이 동시에 일하는 중 · " + run.joinToString(" · ") { it.who }
        run.size == 1 -> "${run[0].who}가 ${run[0].doing.substringBefore(" · ")}"
        else -> st.note.ifBlank { "다음 일을 배정하는 중" }
    }
}

@Composable
private fun PhasePill(phase: String, dark: Boolean) {
    val on = phase != "완료"
    Text(phase, style = T.small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else (if (dark) C.OnInk else C.Ink)),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Red else (if (dark) C.OnInk.copy(alpha = 0.12f) else C.Paper2)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun CardRow(c: StageBus.Card, live: String?, now: Long, dark: Boolean, animate: Boolean, last: Boolean) {
    val fg = if (dark) C.OnInk else C.Ink
    val sub = if (dark) C.OnInkMute else C.Graphite
    val running = c.status == StageBus.S.RUN || c.status == StageBus.S.SWAP || c.status == StageBus.S.CHECK
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 센터에서 내려오는 가는 선(일하는 중이면 점이 흐른다)
        Box(Modifier.width(1.dp).fillMaxHeight().background(if (dark) C.OnInk.copy(alpha = 0.18f) else C.Hair))
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
            Avatar(c.who, c.provider, 26.dp, active = running, animate = animate, dark = dark, badge = c.status)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = T.small.copy(color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val t = ((if (c.endedAt > 0) c.endedAt else if (c.startedAt > 0) now else 0L) - c.startedAt).coerceAtLeast(0) / 1000
                    if (c.startedAt > 0) Text(if (t >= 60) "${t / 60}분 ${t % 60}초" else "${t}초", style = T.mono.copy(color = sub, fontSize = 10.5.sp))
                }
                val line = "${c.who} · " + when (c.status) { StageBus.S.WAIT -> c.doing.ifBlank { "대기" }; StageBus.S.DONE -> "완료 · ${c.doing}"; StageBus.S.FAIL -> "답을 받지 못함"; else -> c.doing }
                Shimmer(line, running && animate, if (c.status == StageBus.S.SWAP) C.Red else sub, fg)
                if (c.peer.isNotBlank()) Text("↳ " + c.peer, style = T.small.copy(color = if (c.peer.contains("주의")) C.Red else sub, fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (live != null && live.isNotBlank()) Text(live.replace('\n', ' ').takeLast(90), style = T.small.copy(color = if (dark) C.OnInk.copy(alpha = 0.82f) else C.Ink2, fontSize = 12.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** 둥근 얼굴: 회사색 고리. 일하는 중이면 고리 한 조각이 돈다. 끝나면 작은 표시 */
@Composable
private fun Avatar(name: String, provider: String, size: Dp, active: Boolean, animate: Boolean, dark: Boolean, badge: StageBus.S? = null) {
    val tint = providerTint(provider)
    val angle = if (active && animate) rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a").value else 0f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 2.dp.toPx()
            drawCircle(tint.copy(alpha = if (active) 0.25f else 0.5f), style = Stroke(w))
            if (active) drawArc(Brush.sweepGradient(listOf(tint.copy(alpha = 0f), tint)), angle, 110f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Box(Modifier.size(size - 7.dp).clip(CircleShape).background(if (dark) C.OnInk.copy(alpha = 0.08f) else C.Paper2), contentAlignment = Alignment.Center) {
            // 같은 첫 글자(Claude·ChatGPT, Gemini·Grok)가 헷갈리지 않게 회사별 두 글자
            val mono = when (provider.uppercase()) { "CLAUDE" -> "Cl"; "GPT" -> "GP"; "GEMINI" -> "Ge"; "GROK" -> "Gk"; "DEEPSEEK" -> "DS"; else -> name.take(1).uppercase() }
            Text(mono, style = T.small.copy(color = if (dark) C.OnInk else C.Ink, fontWeight = FontWeight.Bold, fontSize = (size.value * (if (mono.length > 1) 0.34f else 0.4f)).sp, letterSpacing = 0.sp))
        }
        badge?.let { b ->
            val (bg, mark) = when (b) { StageBus.S.DONE -> tint to "✓"; StageBus.S.FAIL -> C.Red to "!"; StageBus.S.SWAP -> C.Red to "⇄"; else -> null to "" }
            if (bg != null) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Text(mark, style = T.small.copy(color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** 글자 위로 빛이 지나간다(일하는 중일 때만) */
@Composable
private fun Shimmer(text: String, on: Boolean, color: Color, glow: Color) {
    if (!on) { Text(text, style = T.small.copy(color = color, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis); return }
    val x = rememberInfiniteTransition(label = "sh").animateFloat(-300f, 900f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "x").value
    Text(text, style = T.small.copy(fontSize = 12.sp, brush = Brush.linearGradient(listOf(color, glow, color), start = Offset(x, 0f), end = Offset(x + 220f, 0f))), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 진행 막대: 채워진 부분 위로 밝은 띠가 흐른다 */
@Composable
private fun FlowBar(p: Float, animate: Boolean, dark: Boolean) {
    val x = if (animate && p < 1f) rememberInfiniteTransition(label = "fb").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "f").value else -1f
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (dark) C.OnInk.copy(alpha = 0.12f) else C.Paper2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(C.Red).drawWithContent {
            drawContent()
            if (x >= 0f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x88FFFFFF), Color.Transparent), startX = size.width * x - 80f, endX = size.width * x + 80f))
        })
    }
}

/** 칠판 띠: 가는 테두리 안에 최근 메모 몇 줄. 회사색 점으로 누가 남겼는지 */
@Composable
private fun BoardStrip(lines: List<String>, dark: Boolean) {
    val fg = if (dark) C.OnInk else C.Ink
    val sub = if (dark) C.OnInkMute else C.Graphite
    Column(Modifier.padding(start = 16.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (dark) C.OnInk.copy(alpha = 0.06f) else C.Paper2).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text("칠판 · AI끼리 남긴 메모", style = T.mono.copy(color = sub, fontSize = 10.sp))
        lines.forEach { l ->
            val who = l.substringBefore(" · ")
            val kind = l.substringAfter(" · ").substringBefore(" · ")
            val body = l.substringAfter(" · ").substringAfter(" · ")
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(providerTint(when (who) { "Claude" -> "CLAUDE"; "ChatGPT", "GPT" -> "GPT"; "Gemini" -> "GEMINI"; "Grok" -> "GROK"; else -> "DEEPSEEK" })))
                Spacer(Modifier.width(6.dp))
                Text(who, style = T.small.copy(color = fg, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp))
                Text(" $kind ", style = T.small.copy(color = if (kind == "주의") C.Red else sub, fontSize = 11.5.sp))
                Text(body, style = T.small.copy(color = fg, fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 프로젝트 띠: 스프린트 · 남은 시간(초 단위로 줄어듦) · 품질 · 티켓 보드 칸별 수 */
@Composable
private fun ProjectStrip(st: StageBus.Stage, now: Long, dark: Boolean) {
    val fg = if (dark) C.OnInk else C.Ink
    val sub = if (dark) C.OnInkMute else C.Graphite
    val left = ((if (st.deadline > 0) st.deadline else now) - now).coerceAtLeast(0) / 1000
    val time = if (st.phase == "완료") "완료" else "%d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Metric("스프린트", if (st.sprint == 0) "—" else "${st.sprint}", fg, sub, Modifier.weight(1f))
            Metric("남은 시간", time, fg, sub, Modifier.weight(1.4f))
            Metric("품질", if (st.quality <= 0) "—" else "%.1f".format(java.util.Locale.US, st.quality), if (st.quality >= 8) fg else if (st.quality > 0) C.Red else fg, sub, Modifier.weight(1f))
        }
        val n = { s: StageBus.S -> st.cards.count { it.status == s } }
        Text("할 일 ${n(StageBus.S.WAIT)} · 진행 ${n(StageBus.S.RUN) + n(StageBus.S.SWAP)} · 리뷰 ${n(StageBus.S.CHECK)} · 완료 ${n(StageBus.S.DONE)}" + (n(StageBus.S.FAIL).takeIf { it > 0 }?.let { " · 실패 $it" } ?: ""),
            style = T.mono.copy(color = sub, fontSize = 10.5.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, sub: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = T.mono.copy(color = sub, fontSize = 10.sp))
        Text(value, style = T.head.copy(color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp))
    }
}

/** 산출물 파일: 경로 · 글자 수 · 최근 리뷰 점수 */
@Composable
private fun FilesStrip(files: List<String>, dark: Boolean) {
    val fg = if (dark) C.OnInk else C.Ink
    val sub = if (dark) C.OnInkMute else C.Graphite
    Column(Modifier.padding(start = 16.dp, top = 10.dp).fillMaxWidth()) {
        Text("산출물 ${files.size}", style = T.mono.copy(color = sub, fontSize = 10.sp))
        files.take(10).forEach { f ->
            val (path, len, score) = f.split("|").let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "0" }, it.getOrElse(2) { "" }) }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(path, style = T.small.copy(color = fg, fontSize = 12.sp, fontWeight = if (path.startsWith("00_")) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("%,d자".format(len.toIntOrNull() ?: 0), style = T.mono.copy(color = sub, fontSize = 10.5.sp))
                if (score.isNotBlank()) Text("  $score", style = T.mono.copy(color = if ((score.toDoubleOrNull() ?: 0.0) >= 8) fg else C.Red, fontSize = 10.5.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
