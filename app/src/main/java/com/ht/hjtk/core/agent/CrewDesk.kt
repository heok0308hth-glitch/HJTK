package com.ht.hjtk.core.agent

import com.ht.hjtk.core.ai.AiProfiles
import com.ht.hjtk.core.ai.AiProfiles.Skill
import com.ht.hjtk.core.ai.TokenLedger
import com.ht.hjtk.core.ai.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 팀 호출 창구 — 지휘자(Conductor)와 회사(Company)가 함께 쓴다.
 * 누가 지금 바쁜지, 누가 몇 건 끝냈는지, AI마다 동시에 몇 개까지 보낼지, 실패하면 누구에게 넘길지,
 * 일시정지·중지를 어떻게 지킬지, 칠판 메모를 어떻게 떼어 올릴지를 한곳에서 관리한다.
 */
class CrewDesk(
    val jobId: String,
    val crew: Crew,
    private val control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long,
    val ledger: TokenLedger,
) {
    /** 보통 예외: 자식이 던지면 같은 무리(동시 작업)가 모두 취소되고 엔진의 run()까지 올라온다 */
    class Aborted : Exception("작업 중지")

    var team: List<CrewMember> = emptyList()
    var center: CrewMember? = null
    val board = Board()
    val busy = ConcurrentHashMap<String, AtomicInteger>()
    val credit = ConcurrentHashMap<String, AtomicInteger>()
    private val rr = ConcurrentHashMap<String, AtomicInteger>()
    private val lanes = ConcurrentHashMap<String, Semaphore>()

    fun laneOf(m: CrewMember) = rr.getOrPut(m.key) { AtomicInteger() }.getAndIncrement() % m.lanes.coerceAtLeast(1)
    fun lane(m: CrewMember): Semaphore = lanes.getOrPut(m.key) { Semaphore(m.lanes.coerceAtLeast(1)) }
    fun busyOf(m: CrewMember) = busy[m.key]?.get() ?: 0
    fun hold(m: CrewMember) { busy.getOrPut(m.key) { AtomicInteger() }.incrementAndGet() }
    fun release(m: CrewMember) { busy[m.key]?.decrementAndGet() }
    fun thank(m: CrewMember) { credit.getOrPut(m.key) { AtomicInteger() }.incrementAndGet() }
    fun creditOf(m: CrewMember) = credit[m.key]?.get() ?: 0
    fun credits(): String = credit.entries.sortedByDescending { it.value.get() }.joinToString(" · ") { (k, v) -> "${team.firstOrNull { it.key == k }?.name ?: k} ${v.get()}" }
    fun member(name: String) = team.firstOrNull { it.name == name }

    /** 중지 감시를 붙여 실행: 누르는 순간 안의 모든 요청을 끊는다 */
    suspend fun <T> guarded(block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val watch = launch { control.signal.first { it == JobControl.Signal.ABORT }; throw Aborted() }
        try { block() } finally { watch.cancel() }
    }

    /** 일시정지: 새 요청을 보내지 않고 기다린다. 중지: 즉시 끊는다 */
    suspend fun pauseGate() {
        when (control.signal.value) {
            JobControl.Signal.ABORT -> throw Aborted()
            JobControl.Signal.PAUSE -> {
                val was = StageBus.stages.value[jobId]?.phase ?: ""
                StageBus.edit(jobId) { it.copy(phase = "일시정지") }
                val s = control.signal.first { it != JobControl.Signal.PAUSE }
                if (s == JobControl.Signal.ABORT) throw Aborted()
                StageBus.edit(jobId) { it.copy(phase = was.ifBlank { "실행" }) }
            }
            else -> Unit
        }
    }

    /** 고른 AI가 못 하면(빈 답·한도·오류) → 같은 능력에서 지금 한가한 다음 AI로. 최대 3명. 센터는 마지막 수단 */
    suspend fun ask(first: CrewMember, skill: Skill, cardId: String, prompt: String, minLen: Int = 20): String {
        val tried = HashSet<String>()
        var m: CrewMember? = first
        while (tried.size < 3) {
            val cur = m ?: break
            pauseGate()
            tried += cur.key
            if (!crew.available(cur)) { m = next(skill, tried); continue }
            val t0 = clock()
            hold(cur)
            val r = try { send(cur, prompt) } finally { release(cur) }
            val text = r.text.trim()
            val ok = text.length >= minLen && !refusal(text) && !AiProfiles.limitHit(r.text, r.via)
            crew.report(cur, ok, clock() - t0, r.text, r.via)
            if (ok) { ledger.add(cur.name, Tokens.estimate(prompt), Tokens.estimate(text)); thank(cur); return text }
            val alt = next(skill, tried) ?: break
            val why = when { AiProfiles.limitHit(r.text, r.via) || !crew.available(cur) -> "사용량 한도"; r.via.contains("LOGIN") -> "로그인 필요"; text.isBlank() -> "답이 없어서"; else -> "답이 부실해서" }
            emit(AgentEvent.Collab(clock(), "교체", alt.name, "${cur.name} $why → ${alt.name}가 이어받아요"))
            StageBus.card(jobId, cardId) { it.copy(who = alt.name, provider = alt.provider.name, status = StageBus.S.SWAP, doing = "${cur.name} $why → ${alt.name}") }
            delay(250)
            StageBus.card(jobId, cardId) { it.copy(status = StageBus.S.RUN) }
            m = alt
        }
        return ""
    }

    /** 센터 전용(설계·판단): 실패하면 계획에 강한 다음 AI가 센터를 이어받는다 */
    suspend fun askCenter(m: CrewMember, prompt: String, takeOver: Boolean): String {
        pauseGate()
        val t0 = clock()
        val r = send(m, prompt, lane = 0)
        val ok = r.text.isNotBlank() && !AiProfiles.limitHit(r.text, r.via)
        crew.report(m, ok, clock() - t0, r.text, r.via)
        ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(r.text))
        if (ok) return r.text
        val alt = next(Skill.PLAN, setOf(m.key), allowCenter = true) ?: return ""
        if (takeOver) { center = alt; StageBus.edit(jobId) { it.copy(center = alt.name, centerProvider = alt.provider.name) }; emit(AgentEvent.Collab(clock(), "센터", alt.name, "${m.name} 대신 센터를 맡아요")) }
        return askCenter(alt, prompt, takeOver)
    }

    /** 한 번 보내기(교체 없음) — 교차 확인·리뷰처럼 실패해도 흐름이 계속되는 일 */
    suspend fun once(m: CrewMember, prompt: String): LlmReply {
        pauseGate()
        val t0 = clock()
        hold(m)
        val r = try { send(m, prompt) } finally { release(m) }
        crew.report(m, r.text.isNotBlank() && !AiProfiles.limitHit(r.text, r.via), clock() - t0, r.text, r.via)
        ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(r.text))
        return r
    }

    private suspend fun send(m: CrewMember, prompt: String, lane: Int = laneOf(m)): LlmReply =
        runCatching { crew.port(m, lane).send(prompt, jobId, 1, true) }.getOrElse { e -> if (e is CancellationException) throw e; LlmReply("", "ERR:${e.message}", false, 0) }

    /** 다음 사람: 능력(성적) − 지금 하는 일 − 센터 벌점 */
    fun next(skill: Skill, tried: Set<String>, allowCenter: Boolean = false): CrewMember? =
        team.filter { it.key !in tried && crew.available(it) }.maxByOrNull { Dispatch.bid(crew.rate(it, skill), busyOf(it), it.lanes, !allowCenter && it.key == center?.key, team.size, false) }

    fun bestFree(skill: Skill, avoidCenter: Boolean, hint: String = "", exclude: Set<String> = emptySet()): CrewMember {
        val pool = team.filter { crew.available(it) && it.key !in exclude }.ifEmpty { team.filter { it.key !in exclude } }.ifEmpty { team }
        return pool.maxBy { Dispatch.bid(crew.rate(it, skill), busyOf(it), it.lanes, avoidCenter && it.key == center?.key, pool.size, Dispatch.matches(hint, it)) }
    }

    /** 칠판 메모를 떼어 칠판에 올리고 본문만 돌려준다 */
    fun absorb(raw: String, who: String, loud: Boolean = true): String {
        val (body, notes) = Board.split(raw)
        var added = 0
        notes.forEach { (k, v) -> if (board.add(who, k, v)) added++ }
        if (added > 0) {
            StageBus.edit(jobId) { it.copy(board = recentBoard()) }
            if (loud) emit(AgentEvent.Collab(clock(), "칠판", who, notes.joinToString(" / ") { "${it.first}: ${it.second.take(40)}" }))
            else emit(AgentEvent.Note(clock(), "칠판 · $who · " + notes.joinToString(" / ") { "${it.first}: ${it.second.take(40)}" }))
        }
        return body
    }
    fun recentBoard() = board.all().takeLast(6).map { "${it.who} · ${it.kind} · ${it.text.take(60)}" }

    fun refusal(s: String) = s.length < 120 && Regex("(죄송하지만|도와드릴 수 없|I can't|I cannot|unable to)", RegexOption.IGNORE_CASE).containsMatchIn(s)
}
