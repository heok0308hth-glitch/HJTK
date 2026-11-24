package com.ht.hjtk.ui

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
import com.ht.hjtk.core.I18n
import com.ht.ensemble.ai.Difficulty
import com.ht.hjtk.engine.Routine
import com.ht.hjtk.engine.Routines
import com.ht.hjtk.engine.UsageLog
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

// ═══════════════════════════════════════════════════════════════════
//  루틴: 정해 둔 시각에 작업을 스스로 맡는다. 결과는 루틴마다 대화방 하나에 쌓인다.
// ═══════════════════════════════════════════════════════════════════

private data class Preset(val title: String, val goal: String, val repeat: String, val hour: Int, val minute: Int = 0, val day: Int = 1)

private val PRESETS = listOf(
    Preset("아침 브리핑", "오늘 일정과 날씨, 안 읽은 중요한 메일을 한 화면으로 정리해 줘. 할 일이 겹치면 순서를 제안해.", "daily", 8),
    Preset("하루 마무리", "오늘 대화와 작업에서 끝난 일과 남은 일을 나눠 정리하고, 내일 첫 할 일 하나를 골라 줘.", "weekdays", 18, 30),
    Preset("주간 계획", "이번 주 캘린더를 보고 비어 있는 시간과 마감이 가까운 일을 짚어서 주간 계획을 세워 줘.", "weekly", 9, 0, 1)
)

@Composable
fun RoutinesScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val list by Routines.list.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("daily") }
    var day by remember { mutableStateOf(1) }
    var hour by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }
    var diff by remember { mutableStateOf("STANDARD") }
    fun fill(p: Preset) { title = p.title; goal = p.goal; repeat = p.repeat; hour = p.hour; minute = p.minute; day = p.day; editing = true }
    val now = remember(list) { LocalDateTime.now() }

    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("루틴"), if (list.isEmpty()) I18n.t("정해 둔 시각에 AI가 스스로 일을 맡아요") else "${list.count { it.enabled }}/${list.size} " + I18n.t("켜짐"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            if (list.isEmpty() && !editing) {
                Spacer(Modifier.height(22.dp))
                Text(I18n.t("한 번 정하면 매번 시키지 않아도 돼요"), style = T.title)
                Spacer(Modifier.height(6.dp))
                Text(I18n.t("루틴은 화면이 꺼져 있어도 그 시각에 깨어나 작업을 맡고, 결과를 같은 대화방에 이어 붙입니다. 밖으로 보내는 일은 여전히 '실행' 버튼을 거쳐요."), style = T.small.copy(fontSize = 13.sp))
                SectionHead("01", I18n.t("이렇게 시작해 보세요"), I18n.t("직접 만들기")) { editing = true }
                PRESETS.forEachIndexed { i, p ->
                    NumRow(i + 1, I18n.t(p.title), Routine("", p.title, p.goal, p.repeat, p.day, p.hour, p.minute).label(), onClick = { fill(p) })
                    if (i < PRESETS.lastIndex) Hairline()
                }
            }

            if (list.isNotEmpty()) {
                SectionHead("01", I18n.t("시간표"), if (editing) null else I18n.t("추가")) { editing = true }
                list.sortedBy { it.next(now) }.forEachIndexed { i, r ->
                    RoutineRow(i + 1, r, now,
                        onToggle = { Routines.update(ctx, r.id) { it.copy(enabled = !it.enabled) } },
                        onRun = { Routines.runNow(ctx, r.id) },
                        onOpen = { r.convId?.let { vm.openConversation(it) } },
                        onDelete = { Routines.remove(ctx, r.id) })
                    Hairline()
                }
            }

            if (editing) {
                SectionHead(if (list.isEmpty()) "02" else "02", I18n.t("새 루틴"))
                Spacer(Modifier.height(10.dp))
                Field(title, { title = it }, I18n.t("이름 (비우면 목표 앞부분)"))
                Spacer(Modifier.height(8.dp))
                Field(goal, { goal = it }, I18n.t("맡길 일 — 예: 오늘 일정과 메일을 정리해 줘"), single = false)
                Spacer(Modifier.height(16.dp)); Label(I18n.t("반복"))
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("daily" to I18n.t("매일"), "weekdays" to I18n.t("평일"), "weekly" to I18n.t("매주")), repeat) { repeat = it }
                if (repeat == "weekly") {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (I18n.en) listOf("M", "T", "W", "T", "F", "S", "S") else "월화수목금토일".map { it.toString() }).forEachIndexed { i, d ->
                            DayDot(d, day == i + 1) { day = i + 1 }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp)); Label(I18n.t("시각"))
                Spacer(Modifier.height(6.dp))
                TimeDial(hour, minute, onHour = { hour = it }, onMinute = { minute = it })
                Spacer(Modifier.height(16.dp)); Label(I18n.t("난이도"))
                Spacer(Modifier.height(6.dp))
                Segmented(Difficulty.values().filter { it != Difficulty.PROJECT }.map { it.name to (if (I18n.en) it.en else it.label) }, diff) { diff = it }
                Text(Difficulty.valueOf(diff).desc.let { I18n.t(it) }, style = T.small.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(I18n.t("취소"), Modifier.weight(1f)) { editing = false }
                    InkButton(I18n.t("저장"), Modifier.weight(2f), accent = goal.isNotBlank()) {
                        if (goal.isNotBlank()) {
                            Routines.add(ctx, title.ifBlank { goal.take(18) }, goal.trim(), repeat, day, hour, minute, diff)
                            title = ""; goal = ""; editing = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(n: Int, r: Routine, now: LocalDateTime, onToggle: () -> Unit, onRun: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().press({ more = !more }).padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(n), style = T.num, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = T.head.copy(color = if (r.enabled) C.Ink else C.Mute), maxLines = 1)
                Text(r.label() + if (r.enabled) " · " + until(now, r.next(now)) else " · " + I18n.t("꺼짐"), style = T.small.copy(fontSize = 12.5.sp))
            }
            Toggle(r.enabled, onToggle)
        }
        if (more) {
            Text(r.goal, style = T.small.copy(color = C.Ink2), modifier = Modifier.padding(start = 34.dp, top = 8.dp))
            Row(Modifier.padding(start = 26.dp, top = 6.dp)) {
                Act(I18n.t("지금 실행"), C.Ink, onRun)
                if (r.convId != null) Act(I18n.t("결과 보기"), C.Ink, onOpen)
                Spacer(Modifier.weight(1f))
                Act(I18n.t("삭제"), C.Red, onDelete)
            }
        }
    }
}

@Composable
private fun Act(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) =
    Text(text, style = T.small.copy(color = color, fontWeight = FontWeight.Bold), modifier = Modifier.press(onClick).padding(8.dp))

private fun until(now: LocalDateTime, at: LocalDateTime): String {
    val m = Duration.between(now, at).toMinutes().coerceAtLeast(0)
    return when {
        m < 60 -> if (I18n.en) "in ${m}m" else "${m}분 뒤"
        m < 24 * 60 -> if (I18n.en) "in ${m / 60}h ${m % 60}m" else "${m / 60}시간 ${m % 60}분 뒤"
        else -> if (I18n.en) "in ${m / 1440}d" else "${m / 1440}일 뒤"
    }
}

@Composable
private fun DayDot(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(if (on) C.Ink else C.Paper2).press(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = T.small.copy(color = if (on) C.OnInk else C.Ink2, fontWeight = FontWeight.SemiBold))
    }
}

/** 시각: 큰 숫자 + 양옆 조절. 분은 15분 단위(루틴에는 그 이상 정밀할 필요가 없다) */
@Composable
private fun TimeDial(hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Paper2).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Step("−") { onHour((hour + 23) % 24) }
        Text("%02d".format(hour), style = T.display.copy(fontSize = 34.sp), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Step("+") { onHour((hour + 1) % 24) }
        Text(":", style = T.display.copy(fontSize = 30.sp, color = C.Mute))
        Step("−") { onMinute((minute + 45) % 60) }
        Text("%02d".format(minute), style = T.display.copy(fontSize = 34.sp), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Step("+") { onMinute((minute / 15 * 15 + 15) % 60) }
    }
}

@Composable
private fun Step(t: String, onClick: () -> Unit) =
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).press(onClick), contentAlignment = Alignment.Center) { Text(t, style = T.title.copy(color = C.Ink2)) }

// ═══════════════════════════════════════════════════════════════════
//  사용량: 누가 얼마나 썼나. API는 실제 토큰, 웹·앱은 글자 수로 추정(≈)
// ═══════════════════════════════════════════════════════════════════

@Composable
fun UsageScreen(vm: MainVm) {
    val ver by UsageLog.version.collectAsState()
    val days = remember(ver) { UsageLog.recent(60) }
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()).toString() }
    val byDate = days.associateBy { it.date }
    val dayTotals = week.map { d -> byDate[d]?.by?.values?.sumOf { it[0] + it[1] } ?: 0 }
    val who = LinkedHashMap<String, IntArray>()
    week.forEach { d -> byDate[d]?.by?.forEach { (w, a) -> val x = who.getOrPut(w) { intArrayOf(0, 0, 0) }; x[0] += a[0]; x[1] += a[1]; x[2] += a[2] } }
    val total = dayTotals.sum()
    val peak = (dayTotals.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("사용량"), I18n.t("최근 7일 · 이 기기에서만 집계"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(18.dp))
            Label(I18n.t("7일 합계"))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(total), style = T.display.copy(fontSize = 40.sp))
                Text(" " + I18n.t("토큰"), style = T.body.copy(color = C.Graphite), modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(if (total == 0) I18n.t("아직 기록이 없어요. 대화나 작업을 하면 여기에 쌓여요.") else "${I18n.t("하루 평균")} ${fmt(total / 7)} · ${I18n.t("호출")} ${who.values.sumOf { it[2] }}${I18n.t("회")}",
                style = T.small.copy(fontSize = 12.5.sp))

            // 막대: 오늘만 붉게. 축·격자 없이 높이로만 읽는다
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                dayTotals.forEachIndexed { i, v ->
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (v > 0) Text(short(v), style = T.mono.copy(fontSize = 9.5.sp, color = if (i == 6) C.Red else C.Mute))
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().fillMaxHeight(((v.toFloat() / peak) * 0.82f).coerceAtLeast(0.015f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (i == 6) C.Red else if (v > 0) C.Ink else C.Hair))
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                week.forEachIndexed { i, d ->
                    val ld = LocalDate.parse(d)
                    Text(if (i == 6) I18n.t("오늘") else (if (I18n.en) "${ld.dayOfMonth}" else "${ld.dayOfMonth}일"), style = T.mono.copy(fontSize = 10.sp, color = if (i == 6) C.Ink else C.Mute),
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }

            SectionHead("01", I18n.t("AI별"))
            if (who.isEmpty()) Text(I18n.t("기록 없음"), style = T.small, modifier = Modifier.padding(vertical = 12.dp))
            val maxWho = (who.values.maxOfOrNull { it[0] + it[1] } ?: 1).coerceAtLeast(1)
            who.entries.sortedByDescending { it.value[0] + it.value[1] }.forEachIndexed { i, (w, a) ->
                val est = w == "DeepSeek" || w.endsWith(" 앱")
                Column(Modifier.padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(i + 1), style = T.num, modifier = Modifier.width(34.dp))
                        Text(w, style = T.head, modifier = Modifier.weight(1f), maxLines = 1)
                        Text((if (est) "≈" else "") + fmt(a[0] + a[1]), style = T.head.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.padding(start = 34.dp).fillMaxWidth().height(3.dp).background(C.Paper2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((a[0] + a[1]).toFloat() / maxWho).background(if (i == 0) C.Ink else C.Ink3))
                    }
                    Text("${I18n.t("입력")} ${fmt(a[0])} · ${I18n.t("출력")} ${fmt(a[1])} · ${a[2]}${I18n.t("회")}", style = T.small.copy(fontSize = 11.5.sp, color = C.Mute), modifier = Modifier.padding(start = 34.dp, top = 4.dp))
                }
                Hairline()
            }

            SectionHead("02", I18n.t("아끼는 법"))
            Text(I18n.t("· 대화는 '빠르게' 난이도로, 깊은 검토는 필요한 작업에만.\n· 키가 있는 AI는 실제 토큰을, DeepSeek 웹은 글자 수로 추정해요(≈).\n· 오래된 대화 기록은 자동으로 잘라 보내고, 같은 명령은 저장해 다시 쓰지 않아요."),
                style = T.small.copy(color = C.Ink2, fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

private fun fmt(n: Int): String = "%,d".format(n)
private fun short(n: Int): String = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fk".format(n / 1e3); else -> n.toString() }
