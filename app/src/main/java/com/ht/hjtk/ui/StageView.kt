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
import com.ht.hjtk.core.agent.StageBus
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
                Text("센터 · ${st.center}", style = T.head.copy(color = fg, fontSize = 14.5.sp))
                Text(phaseLine(st), style = T.small.copy(color = sub, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PhasePill(st.phase, dark)
        }
        // 팀원
        val cards = if (compact) st.cards.sortedBy { order(it.status) }.take(4) else st.cards
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
            Text(name.take(1).uppercase(), style = T.small.copy(color = if (dark) C.OnInk else C.Ink, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp))
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
