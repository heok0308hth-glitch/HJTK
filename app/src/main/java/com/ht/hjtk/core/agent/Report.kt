package com.ht.hjtk.core.agent

import com.ht.ensemble.agent.*
import com.ht.ensemble.command.*

import kotlin.math.roundToInt

/** ETA는 약속이 아니라 구간이다. */
data class Eta(val p50Sec: Int, val p90Sec: Int, val basis: String) {
    fun label(): String = when {
        p90Sec <= 0 -> "곧 끝남"
        p90Sec < 90 -> "약 ${p50Sec.coerceAtLeast(10)}초~${p90Sec}초"
        else -> "약 ${(p50Sec / 60.0).roundToInt().coerceAtLeast(1)}~${(p90Sec / 60.0).roundToInt().coerceAtLeast(1)}분"
    }
}

object EtaEstimator {
    /** 남은 계획 단계의 예상치 합 + 실제 턴 속도로 보정. 표본이 적으면 구간을 넓힌다. */
    fun estimate(plan: List<PlanStep>, turnDurationsMs: List<Long>, progress: Double?): Eta {
        val remaining = plan.filter { it.status != StepStatus.DONE }
        val planned = remaining.sumOf { it.etaSec.coerceIn(5, 3600) }
        val avgTurn = if (turnDurationsMs.isEmpty()) 45.0 else turnDurationsMs.takeLast(8).average() / 1000.0
        val byTurns = (remaining.size.coerceAtLeast(1) * avgTurn * 1.5).roundToInt()
        val base = when {
            planned > 0 && turnDurationsMs.size >= 3 -> ((planned + byTurns) / 2.0).roundToInt()
            planned > 0 -> planned
            progress != null && progress > 0.05 && turnDurationsMs.isNotEmpty() -> {
                val elapsed = turnDurationsMs.sum() / 1000.0
                (elapsed / progress * (1 - progress)).roundToInt()
            }
            else -> byTurns
        }
        val spread = if (turnDurationsMs.size >= 6) 1.4 else 2.0
        return Eta(base, (base * spread).roundToInt(), if (turnDurationsMs.size >= 6) "실측 ${turnDurationsMs.size}턴" else "계획 기반·표본 적음")
    }
}

data class Report(val at: Long, val headline: String, val lines: List<String>, val late: Boolean) {
    fun text() = headline + "\n" + lines.joinToString("\n")
}

/**
 * 5분 보고는 시간이 아니라 '델타'다. 변화가 없으면 "변화 없음"도 보고한다(살아 있다는 신호).
 * 늦었으면 늦었다고 쓴다.
 */
class ReportComposer(private val intervalMs: Long = 5 * 60_000) {
    private var lastAt = 0L
    private var cursor = 0

    fun reset(now: Long) { lastAt = now; cursor = 0 }
    fun due(now: Long) = now - lastAt >= intervalMs

    fun compose(now: Long, events: List<AgentEvent>, plan: List<PlanStep>, next: String, progress: Double?, eta: Eta?, state: JobState, startedAt: Long): Report {
        val delta = events.drop(cursor.coerceAtMost(events.size))
        cursor = events.size
        val cmds = delta.filterIsInstance<AgentEvent.CmdEnd>()
        val ok = cmds.count { it.result.ok }
        val failed = cmds.size - ok
        val arts = delta.filterIsInstance<AgentEvent.Artifact>().map { it.name }
        val doing = plan.firstOrNull { it.status == StepStatus.DOING } ?: plan.firstOrNull { it.status == StepStatus.TODO }
        val doneSteps = plan.count { it.status == StepStatus.DONE }
        val gapMin = if (lastAt == 0L) 0 else ((now - lastAt - intervalMs) / 60_000).toInt()
        val elapsedMin = ((now - startedAt) / 60_000).toInt()
        val pct = progress?.let { (it * 100).roundToInt() } ?: if (plan.isNotEmpty()) doneSteps * 100 / plan.size else null

        val headline = when {
            state == JobState.PAUSED_APPROVAL -> "허락을 기다리고 있어요"
            state == JobState.PAUSED_INPUT -> "답을 기다리고 있어요"
            state == JobState.PAUSED -> "일시정지 중"
            cmds.isEmpty() && arts.isEmpty() -> "생각 중 · 새로 끝난 일은 없어요"
            else -> buildString {
                append("${cmds.size}개 실행")
                if (failed > 0) append(" (실패 $failed)")
                if (arts.isNotEmpty()) append(" · 결과물 ${arts.size}")
                doing?.let { append(" · 지금: ${it.title}") }
            }
        }
        val lines = ArrayList<String>()
        pct?.let { lines += "진행 $it% · 경과 ${elapsedMin}분" + (eta?.let { e -> " · 남은 시간 ${e.label()}" } ?: "") }
        if (cmds.isNotEmpty()) lines += "한 일: " + cmds.groupingBy { it.spec?.title ?: it.call.cmd }.eachCount().entries.take(4).joinToString(", ") { if (it.value > 1) "${it.key}×${it.value}" else it.key }
        if (arts.isNotEmpty()) lines += "결과물: " + arts.joinToString(", ")
        if (next.isNotBlank()) lines += "다음: $next"
        val upcoming = plan.filter { it.status == StepStatus.TODO }.take(3).map { it.title }
        if (upcoming.isNotEmpty()) lines += "예정: " + upcoming.joinToString(" → ")
        if (gapMin > 0) lines += "보고가 ${gapMin}분 늦었어요(기기 절전 영향)"
        lastAt = now
        return Report(now, headline, lines, gapMin > 0)
    }
}
