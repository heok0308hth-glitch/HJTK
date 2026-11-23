package com.ht.hjtk.core.agent

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.ai.AiProfiles
import com.ht.hjtk.core.ai.AiProfiles.Skill
import com.ht.hjtk.core.ai.Difficulty
import com.ht.hjtk.core.ai.TokenLedger
import com.ht.hjtk.core.ai.Tokens
import com.ht.hjtk.core.command.CommandCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 지휘자(센터) — 한 AI가 처음부터 끝까지 책임진다.
 *
 *  1) 설계: 센터가 목표를 결과물·합격 기준·할 일로 쪼개고, 팀원 성적표(현실 장단점 + 실제 성공률·속도·한도)를 보고 '누가'를 정한다.
 *  2) 동시 실행: 앞 일이 끝난 할 일은 바로 시작한다. AI마다 자기 창(레인)이 있어 서로 다른 AI는 동시에 일한다.
 *  3) 자동 교체: 답이 비거나 한도에 걸리면 같은 능력이 다음으로 좋은 AI에게 즉시 넘긴다. 검색 도구가 막히면 '스스로 검색하는 AI'에게 조사를 맡긴다.
 *  4) 통합·검수: 가장 글을 잘 쓰는 AI가 합치고, 센터가 합격 기준으로 검수한다. 고칠 점이 있으면 한 번 더 다듬는다.
 * 흐름은 앱이 쥐고, AI에게는 글을 받는다. 계획 JSON을 못 읽어도 작업 유형별 공정표로 끝까지 간다.
 */
class Conductor(
    private val ctx: AgentContext,
    private val crew: Crew,
    private val tools: ToolPort,
    human: HumanPort,
    gate: ApprovalGate,
    private val control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    autoAllowE2: Boolean = false,
    private val difficulty: Difficulty = Difficulty.STANDARD,
    val ledger: TokenLedger = TokenLedger(),
    private val catalog: String = "",
) : MissionEngine {
    data class Task(val id: String, val title: String, val who: String, val tool: String = "", val args: Map<String, String> = emptyMap(), val prompt: String = "", val after: List<String> = emptyList())
    data class Plan(val deliverable: String, val criteria: List<String>, val tasks: List<Task>, val finalWho: String, val finalHow: String)

    private val exec = CommandExec(ctx.jobId, tools, human, gate, emit, clock, autoAllowE2)
    private val outputs = LinkedHashMap<String, String>()
    private var center: CrewMember? = null
    private var stagePlan: List<PlanStep> = emptyList()
    private val times = java.util.Collections.synchronizedList(ArrayList<Long>())
    private val rr = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun laneOf(m: CrewMember) = rr.getOrPut(m.key) { java.util.concurrent.atomic.AtomicInteger() }.getAndIncrement() % m.lanes.coerceAtLeast(1)
    private var prog = 0.0
    override val plan get() = stagePlan
    override var next: String = "설계 중"; private set
    override val durations: List<Long> get() = times
    override val progress: Double? get() = prog

    private val maxTasks get() = when (difficulty) { Difficulty.QUICK -> 3; Difficulty.STANDARD -> 5; Difficulty.DEEP -> 7; Difficulty.PROJECT -> 9 }

    override suspend fun run(): LoopOutcome {
        val team = crew.members().filter { crew.available(it) }
        if (team.isEmpty()) return LoopOutcome(JobState.FAILED, "쓸 수 있는 AI가 없어요. 허브 → 계정 하나로에서 연결해 주세요.", 0, "AI 없음")
        val c = team.maxBy { crew.rate(it, Skill.PLAN) * 0.6 + crew.rate(it, Skill.REVIEW) * 0.4 }
        center = c
        StageBus.put(StageBus.Stage(ctx.jobId, ctx.goal, c.name, c.provider.name, "설계", emptyList()))
        emit(AgentEvent.Started(clock(), ctx.goal, c.name))
        emit(AgentEvent.Collab(clock(), "센터", c.name, "이 작업을 처음부터 끝까지 책임져요"))
        phase("설계", 0.05, "${c.name}가 할 일을 나누는 중")

        // 1) 설계
        val p = design(c, team)
        if (aborted()) return stop()
        stagePlan = p.tasks.map { PlanStep(it.title, 60, StepStatus.TODO) } + PlanStep("통합·검수", 90, StepStatus.TODO)
        StageBus.edit(ctx.jobId) { it.copy(phase = "실행", cards = p.tasks.map { t -> card(t) }, note = p.deliverable) }
        emit(AgentEvent.Plan(clock(), stagePlan, p.tasks.firstOrNull()?.title ?: "", 0.12))
        emit(AgentEvent.Collab(clock(), "분배", c.name, p.tasks.joinToString(" · ") { "${it.who}: ${it.title}" }))

        // 2) 동시 실행(의존 관계 순서 지키며)
        runAll(p)
        if (aborted()) return stop()

        // 3) 통합
        phase("통합", 0.82, "결과물을 하나로 합치는 중")
        val writer = pick(Skill.WRITE, prefer = p.finalWho)
        StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card("final", "통합 · ${p.deliverable.take(24)}", writer.name, writer.provider.name, StageBus.S.RUN, "합치는 중", clock())) }
        var draft = askWithFailover(writer, Skill.WRITE, "final", composePrompt(p))
        StageBus.card(ctx.jobId, "final") { it.copy(status = StageBus.S.DONE, doing = "${draft.length}자", endedAt = clock()) }

        // 4) 검수(센터)
        if (difficulty != Difficulty.QUICK && draft.isNotBlank()) {
            repeat(if (difficulty.ordinal >= Difficulty.DEEP.ordinal) 2 else 1) { round ->
                if (aborted()) return stop()
                phase("검수", 0.9 + round * 0.04, "${c.name}가 합격 기준으로 검수하는 중")
                StageBus.edit(ctx.jobId) { it.copy(cards = it.cards.filter { k -> k.id != "check" } + StageBus.Card("check", "검수 ${round + 1}회", c.name, c.provider.name, StageBus.S.CHECK, "기준과 대조하는 중", clock())) }
                val verdict = askWithFailover(c, Skill.REVIEW, "check", reviewPrompt(p, draft))
                val fixes = parseFixes(verdict)
                if (fixes.isEmpty()) { StageBus.card(ctx.jobId, "check") { it.copy(status = StageBus.S.DONE, doing = "합격", endedAt = clock()) }; emit(AgentEvent.Collab(clock(), "검수", c.name, "합격")); return@repeat }
                emit(AgentEvent.Collab(clock(), "검수", c.name, "고칠 점 ${fixes.size}개: " + fixes.take(3).joinToString(" / ")))
                StageBus.card(ctx.jobId, "check") { it.copy(doing = "고칠 점 ${fixes.size}개 → ${writer.name}가 다듬는 중") }
                val revised = askWithFailover(writer, Skill.WRITE, "final", revisePrompt(p, draft, fixes))
                if (revised.length > draft.length * 0.5) draft = revised
                StageBus.card(ctx.jobId, "check") { it.copy(status = StageBus.S.DONE, doing = "반영 완료", endedAt = clock()) }
                if (round == 0 && difficulty.ordinal < Difficulty.DEEP.ordinal) return@repeat
            }
        }
        if (draft.isBlank()) draft = outputs.values.joinToString("\n\n")
        if (draft.isBlank()) return LoopOutcome(JobState.FAILED, "팀원이 모두 답하지 못했어요. 잠시 뒤 '이어서 진행'으로 다시 해 주세요.", outputs.size, "실패")

        // 5) 완성
        val name = "결과_" + ctx.goal.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(24) + ".md"
        runCatching { tools.run(CommandCall("ship", "task.artifact", mapOf("name" to name, "content" to "# ${p.deliverable}\n\n$draft"))) }
        val credits = StageBus.stages.value[ctx.jobId]?.cards?.groupBy { it.who }?.map { (w, l) -> "$w ${l.size}" }?.joinToString(" · ").orEmpty()
        val final = draft.trim() + "\n\n---\n센터 ${c.name} · 팀 $credits · 난이도 ${difficulty.label} · 토큰 약 ${Tokens.fmt(ledger.total())}\n파일: $name"
        phase("완료", 1.0, "완료")
        emit(AgentEvent.Done(clock(), final, "완료"))
        return LoopOutcome(JobState.COMPLETED, final, outputs.size, "완료")
    }

    // ───────────── 설계 ─────────────
    private suspend fun design(c: CrewMember, team: List<CrewMember>): Plan {
        val roster = AiProfiles.roster(team.map { it.name to it.provider })
        val raw = askRaw(c, planPrompt(roster), "plan")
        parsePlan(raw, team)?.let { return it }
        emit(AgentEvent.Note(clock(), "설계 형식을 다시 요청했어요"))
        parsePlan(askRaw(c, "방금 답을 아래 JSON 하나로만 다시 보내 줘(설명 없이). 키: deliverable, criteria[], tasks[{id,title,who,tool,args,prompt,after[]}], final{who,how}", "plan"), team)?.let { return it }
        emit(AgentEvent.Collab(clock(), "설계", "HJTK", "센터의 설계를 읽지 못해 작업 유형별 공정으로 진행해요"))
        return template(team)
    }

    private fun planPrompt(roster: String) = buildString {
        appendLine("너는 AI 팀의 센터(지휘자)다. 아래 목표를 팀에게 나눠 맡기고, 끝까지 품질을 책임진다.")
        appendLine("목표: ${ctx.goal}")
        appendLine("현재: ${ctx.nowText}"); ctx.userName?.let { appendLine("사용자: $it") }
        if (ctx.memories.isNotEmpty()) appendLine("사용자에 대해 알려진 것: " + ctx.memories.take(5).joinToString(" / "))
        ctx.activeSkillGuide?.let { appendLine("작업 지침: $it") }
        appendLine(); appendLine("[팀원 — 이름 · 현실적인 강점과 약점]"); appendLine(roster)
        if (catalog.isNotBlank()) { appendLine(); appendLine("[도구 — 필요할 때만, AI가 스스로 할 수 있으면 쓰지 말 것]"); appendLine(catalog) }
        appendLine()
        appendLine("규칙:")
        appendLine("- 할 일은 ${maxTasks}개 이하. 서로 기다릴 필요 없는 일은 after를 비워 동시에 진행되게 하라(속도).")
        appendLine("- who는 위 팀원 이름 그대로. 각자의 강점에 맞게: 최신 사실 조사→조사 강한 AI, 긴 글→글 강한 AI, 검토→비판 강한 AI. 한 AI에 몰지 마라.")
        appendLine("- prompt는 그 팀원이 바로 일할 수 있게 구체적으로(분량·형식·관점). 앞 일의 결과가 필요하면 after에 id를 적어라(결과는 자동으로 붙는다).")
        appendLine("- 도구가 필요하면 tool에 명령 이름, args에 인자. 검색어는 목표 문장을 그대로 쓰지 말고 실제로 찾을 키워드로.")
        appendLine("- 글쓰기·창작처럼 사실 조사가 필요 없는 목표에는 검색을 넣지 마라.")
        appendLine("- final.who는 결과물을 합칠 팀원(글 강한 AI), final.how는 합치는 방식.")
        appendLine("JSON 하나만 출력:")
        append("{\"deliverable\":\"결과물 한 줄\",\"criteria\":[\"합격 기준\"],\"tasks\":[{\"id\":\"t1\",\"title\":\"짧은 제목\",\"who\":\"팀원\",\"tool\":\"\",\"args\":{},\"prompt\":\"구체 지시\",\"after\":[]}],\"final\":{\"who\":\"팀원\",\"how\":\"합치는 방식\"}}")
    }

    fun parsePlan(raw: String, team: List<CrewMember>): Plan? {
        val o = JsonPick.obj(raw) ?: return null
        val arr = o.optJSONArray("tasks") ?: o.optJSONArray("steps") ?: return null
        val tasks = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifBlank { t.optString("name") }.ifBlank { return@mapNotNull null }
            val args = t.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { k -> a.optString(k) } } ?: emptyMap()
            val tool = t.optString("tool").ifBlank { t.optString("cmd") }.trim().takeIf { it.isNotBlank() && tools.spec(it) != null }.orEmpty()
            Task(t.optString("id").ifBlank { "t${i + 1}" }, title.take(40), resolve(t.optString("who"), title + " " + t.optString("prompt"), team).name, tool, args,
                t.optString("prompt").ifBlank { title }, JsonPick.strings(t.optJSONArray("after")))
        }.take(maxTasks)
        if (tasks.isEmpty()) return null
        val ids = tasks.map { it.id }.toSet()
        val fixed = tasks.map { t -> t.copy(after = t.after.filter { it in ids && it != t.id }) }
        val fin = o.optJSONObject("final")
        return Plan(o.optString("deliverable").ifBlank { ctx.goal.take(50) }, JsonPick.strings(o.optJSONArray("criteria")).ifEmpty { listOf("목표의 요구를 빠짐없이 다룬다", "바로 쓸 수 있는 완성도") },
            fixed, fin?.optString("who").orEmpty(), fin?.optString("how").orEmpty())
    }

    /** 이름 → 팀원(못 찾으면 할 일에 맞는 능력이 가장 좋은 사람) */
    private fun resolve(who: String, text: String, team: List<CrewMember>): CrewMember {
        val w = who.lowercase().trim()
        team.firstOrNull { w.isNotBlank() && (it.name.lowercase().contains(w) || w.contains(it.provider.label.lowercase()) || w.contains(it.provider.name.lowercase())) }?.let { return it }
        val s = AiProfiles.skillFor(text)
        return team.maxBy { crew.rate(it, s) }
    }

    /** 작업 유형별 공정(설계를 못 읽었을 때) */
    private fun template(team: List<CrewMember>): Plan {
        val g = ctx.goal
        fun best(s: Skill, not: Set<String> = emptySet()) = (team.filter { it.name !in not }.ifEmpty { team }).maxBy { crew.rate(it, s) }.name
        val research = Regex("찾|검색|조사|최신|추천|비교|뉴스|가격|일정|공모전|채용|날씨|현황|트렌드").containsMatchIn(g)
        val code = Regex("코드|앱|프로그램|스크립트|html|웹사이트").containsMatchIn(g)
        return when {
            code -> Plan("동작하는 코드와 사용법", listOf("실행 가능", "설명 포함"), listOf(
                Task("t1", "설계", best(Skill.PLAN), prompt = "다음 목표의 구조·파일·핵심 로직을 설계해 줘: $g"),
                Task("t2", "구현", best(Skill.CODE), prompt = "설계대로 전체 코드를 작성해 줘. 생략 없이.", after = listOf("t1")),
                Task("t3", "검토", best(Skill.REVIEW, setOf(best(Skill.CODE))), prompt = "코드의 버그·빠진 처리를 찾아 고친 코드로 줘.", after = listOf("t2"))), best(Skill.CODE), "검토 반영본")
            research -> Plan(g.take(50), listOf("사실마다 출처", "최신 정보", "바로 쓸 수 있게 정리"), listOf(
                Task("t1", "최신 자료 조사", best(Skill.RESEARCH), prompt = "웹에서 직접 검색해 다음 목표에 필요한 최신 사실을 출처 링크와 함께 정리해 줘: $g"),
                Task("t2", "다른 관점 조사", best(Skill.RESEARCH, setOf(best(Skill.RESEARCH))), prompt = "같은 주제를 다른 출처·관점으로 조사해 줘(출처 링크 포함): $g"),
                Task("t3", "분석·추천", best(Skill.REASON), prompt = "두 조사 결과를 비교·검증하고 핵심 결론과 추천을 정리해 줘.", after = listOf("t1", "t2"))), best(Skill.WRITE), "결론 먼저, 근거·출처 뒤에")
            else -> Plan(g.take(50), listOf("요구를 빠짐없이", "독창적이고 구체적", "완성된 글"), listOf(
                Task("t1", "콘셉트·구성", best(Skill.CREATIVE), prompt = "다음 목표를 위한 독창적인 콘셉트 3개와 가장 좋은 1개의 상세 목차를 제안해 줘: $g"),
                Task("t2", "관점·근거 보강", best(Skill.REASON, setOf(best(Skill.CREATIVE))), prompt = "같은 목표에 필요한 핵심 논점·근거·반론을 정리해 줘: $g"),
                Task("t3", "본문 작성", best(Skill.WRITE), prompt = "콘셉트와 논점을 바탕으로 완성된 본문을 써 줘.", after = listOf("t1", "t2"))), best(Skill.WRITE), "본문을 다듬어 완성")
        }
    }

    // ───────────── 실행 ─────────────
    private suspend fun runAll(p: Plan) = coroutineScope {
        val lanes = HashMap<String, Semaphore>()
        fun lane(m: CrewMember) = synchronized(lanes) { lanes.getOrPut(m.key) { Semaphore(m.lanes.coerceAtLeast(1)) } }
        val jobs = HashMap<String, Deferred<Unit>>()
        val n = p.tasks.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        for (t in p.tasks) {
            jobs[t.id] = async {
                t.after.forEach { jobs[it]?.await() }
                if (aborted()) return@async
                val m = crew.members().firstOrNull { it.name == t.who } ?: pick(AiProfiles.skillFor(t.title + t.prompt))
                val t0 = clock()
                val out = if (t.tool.isNotBlank()) toolTask(t, m) else lane(m).withPermit { aiTask(t, m) }
                synchronized(outputs) { outputs[t.id] = out }
                times += clock() - t0
                prog = 0.12 + 0.68 * done.incrementAndGet() / n
                stagePlan = stagePlan.mapIndexed { i, s -> if (i < p.tasks.size && p.tasks[i].id == t.id) s.copy(status = StepStatus.DONE) else s }
                emit(AgentEvent.Plan(clock(), stagePlan, p.tasks.firstOrNull { it.id !in outputs }?.title ?: "통합", prog))
                StageBus.edit(ctx.jobId) { it.copy(progress = prog) }
            }
        }
        jobs.values.forEach { it.await() }
    }

    private suspend fun aiTask(t: Task, m0: CrewMember): String {
        StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.RUN, doing = doingText(t), startedAt = clock()) }
        val out = askWithFailover(m0, AiProfiles.skillFor(t.title + " " + t.prompt), t.id, taskPrompt(t))
        StageBus.card(ctx.jobId, t.id) { it.copy(status = if (out.isBlank()) StageBus.S.FAIL else StageBus.S.DONE, doing = if (out.isBlank()) "답 없음" else "${out.length}자 완료", endedAt = clock()) }
        return out
    }

    /** 도구 할 일: 결과가 비거나 실패하면 '스스로 검색하는 AI'에게 같은 조사를 맡긴다 */
    private suspend fun toolTask(t: Task, m: CrewMember): String {
        StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.RUN, doing = "${t.tool} 실행 중", startedAt = clock()) }
        val args = t.args.mapValues { (_, v) -> fill(v) }
        val r = exec.run(CommandCall("c-${t.id}", t.tool, args))
        if (r.ok && r.text.length > 40) {
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.DONE, doing = "도구 결과 ${r.text.length}자", endedAt = clock()) }
            return r.text.take(6000)
        }
        val alt = pick(Skill.RESEARCH)
        emit(AgentEvent.Collab(clock(), "교체", alt.name, "'${t.tool}'가 막혀서 ${alt.name}가 직접 찾아요"))
        StageBus.card(ctx.jobId, t.id) { it.copy(who = alt.name, provider = alt.provider.name, status = StageBus.S.SWAP, doing = "${alt.name}가 직접 검색하는 중") }
        val out = askWithFailover(alt, Skill.RESEARCH, t.id, "웹에서 직접 검색해서 다음을 조사하고, 사실마다 출처 링크를 붙여 정리해 줘.\n할 일: ${t.title}\n" +
            args.entries.joinToString(" · ") { "${it.key}=${it.value}" } + "\n전체 목표: ${ctx.goal}")
        StageBus.card(ctx.jobId, t.id) { it.copy(status = if (out.isBlank()) StageBus.S.FAIL else StageBus.S.DONE, doing = if (out.isBlank()) "실패" else "${out.length}자 완료", endedAt = clock()) }
        return out
    }

    private fun doingText(t: Task) = when (AiProfiles.skillFor(t.title + t.prompt)) {
        Skill.RESEARCH -> "자료를 찾는 중"; Skill.WRITE -> "쓰는 중"; Skill.CODE -> "코드 짜는 중"; Skill.REVIEW -> "검토하는 중"
        Skill.PLAN -> "구조를 짜는 중"; Skill.CREATIVE -> "아이디어 내는 중"; Skill.REASON -> "분석하는 중"; Skill.LONG -> "자료를 읽는 중"; else -> "일하는 중"
    } + " · " + t.title

    /** {t1} 같은 자리표시를 앞 결과로(URL이 필요하면 URL만) */
    private fun fill(v: String): String = Regex("""\{(t\d+)\}""").replace(v) { m ->
        val o = outputs[m.groupValues[1]].orEmpty()
        val urls = Regex("""https?://[^\s)\]>"']+""").findAll(o).map { it.value }.distinct().take(6).toList()
        if (v.trim() == m.value && urls.isNotEmpty()) urls.joinToString(",") else o.take(300)
    }

    /** 고른 AI가 못 하면(빈 답·한도·오류) 같은 능력의 다음 AI로. 최대 3명까지 */
    private suspend fun askWithFailover(first: CrewMember, skill: Skill, cardId: String, prompt: String): String {
        val tried = HashSet<String>()
        var m: CrewMember? = first
        while (m != null && tried.size < 3) {
            tried += m.key
            if (!crew.available(m)) { m = next(skill, tried); continue }
            val t0 = clock()
            val r = runCatching { crew.port(m, laneOf(m)).send(prompt, ctx.jobId, 1, true) }.getOrElse { e -> if (e is CancellationException) throw e; LlmReply("", "ERR:${e.message}", false, 0) }
            val text = clean(r.text)
            val ok = text.length >= 20 && !refusal(text) && !AiProfiles.limitHit(r.text, r.via)
            crew.report(m, ok, clock() - t0, r.text, r.via)
            if (ok) { ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(text)); return text }
            val alt = next(skill, tried) ?: break
            val why = if (!crew.available(m)) "사용량 한도" else if (text.isBlank()) "답이 없어서" else "답이 부실해서"
            emit(AgentEvent.Collab(clock(), "교체", alt.name, "${m.name} $why → ${alt.name}가 이어받아요"))
            StageBus.card(ctx.jobId, cardId) { it.copy(who = alt.name, provider = alt.provider.name, status = StageBus.S.SWAP, doing = "$why · ${alt.name}로 교체") }
            delay(300)
            StageBus.card(ctx.jobId, cardId) { it.copy(status = StageBus.S.RUN) }
            m = alt
        }
        return ""
    }

    private suspend fun askRaw(m: CrewMember, prompt: String, cardId: String): String {
        val t0 = clock()
        val r = runCatching { crew.port(m, 0).send(prompt, ctx.jobId, 1, true) }.getOrElse { e -> if (e is CancellationException) throw e; LlmReply("", "ERR", false, 0) }
        crew.report(m, r.text.isNotBlank(), clock() - t0, r.text, r.via)
        ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(r.text))
        if (r.text.isBlank()) { val alt = next(Skill.PLAN, setOf(m.key)) ?: return ""; return askRaw(alt, prompt, cardId) }
        return r.text
    }

    private fun next(skill: Skill, tried: Set<String>): CrewMember? = crew.members().filter { it.key !in tried && crew.available(it) }.maxByOrNull { crew.rate(it, skill) }
    private fun pick(skill: Skill, prefer: String = ""): CrewMember {
        val team = crew.members().filter { crew.available(it) }.ifEmpty { crew.members() }
        return team.firstOrNull { prefer.isNotBlank() && it.name.contains(prefer, true) } ?: team.maxBy { crew.rate(it, skill) }
    }

    private fun clean(s: String) = s.trim()
    private fun refusal(s: String) = s.length < 120 && Regex("(죄송하지만|도와드릴 수 없|I can't|I cannot|unable to)", RegexOption.IGNORE_CASE).containsMatchIn(s)

    // ───────────── 프롬프트 ─────────────
    private fun materials(ids: List<String>, budget: Int): String {
        if (ids.isEmpty()) return ""
        val each = budget / ids.size
        return ids.mapNotNull { id -> synchronized(outputs) { outputs[id] }?.let { "[$id 결과]\n" + it.take(each) } }.joinToString("\n\n")
    }

    private fun taskPrompt(t: Task) = buildString {
        appendLine("[팀 작업 · ${center?.name ?: "센터"}가 배정] 전체 목표: ${ctx.goal}")
        appendLine("네 할 일: ${t.title}")
        appendLine(t.prompt)
        val m = materials(t.after, difficulty.budgetChars)
        if (m.isNotBlank()) { appendLine(); appendLine("앞 팀원의 결과:"); appendLine(m) }
        appendLine(); append("완성된 결과물만 써라(인사·설명·되묻기 없이). 한국어.")
    }

    private fun composePrompt(p: Plan) = buildString {
        appendLine("[최종 통합] 목표: ${ctx.goal}")
        appendLine("결과물: ${p.deliverable}")
        appendLine("합격 기준: " + p.criteria.joinToString(" / "))
        if (p.finalHow.isNotBlank()) appendLine("합치는 방식: ${p.finalHow}")
        appendLine(); appendLine("팀원 결과:"); appendLine(materials(synchronized(outputs) { outputs.keys.toList() }, difficulty.budgetChars + 4000))
        appendLine(); append("위 결과를 하나의 완성된 결과물로 다듬어 써라. 중복은 합치고, 모순은 더 근거 있는 쪽으로, 출처는 살려라. 제목부터 바로 시작.")
    }

    private fun reviewPrompt(p: Plan, draft: String) = buildString {
        appendLine("[센터 검수] 목표: ${ctx.goal}")
        appendLine("합격 기준: " + p.criteria.joinToString(" / "))
        appendLine("결과물:"); appendLine(draft.take(difficulty.budgetChars + 6000))
        appendLine(); append("기준에 못 미친 점만 JSON으로: {\"pass\":true/false,\"fixes\":[\"구체적으로 고칠 점\"]}. 충분하면 pass:true, fixes:[].")
    }

    private fun revisePrompt(p: Plan, draft: String, fixes: List<String>) = buildString {
        appendLine("[다듬기] 목표: ${ctx.goal}"); appendLine("센터가 지적한 점:"); fixes.forEach { appendLine("- $it") }
        appendLine("원본:"); appendLine(draft)
        append("지적만 반영한 완성본 전체를 다시 써라.")
    }

    private fun parseFixes(v: String): List<String> {
        val o = JsonPick.obj(v) ?: return if (Regex("통과|합격|pass", RegexOption.IGNORE_CASE).containsMatchIn(v.take(80))) emptyList() else emptyList()
        if (o.optBoolean("pass", false)) return emptyList()
        return JsonPick.strings(o.optJSONArray("fixes")).take(6)
    }

    // ───────────── 상태 ─────────────
    private fun card(t: Task): StageBus.Card {
        val m = crew.members().firstOrNull { it.name == t.who }
        return StageBus.Card(t.id, t.title, t.who, m?.provider?.name ?: "", StageBus.S.WAIT, if (t.after.isEmpty()) "곧 시작" else "앞 일 기다리는 중", after = t.after, tool = t.tool.isNotBlank())
    }
    private fun phase(name: String, p: Double, nxt: String) { prog = p; next = nxt; StageBus.edit(ctx.jobId) { it.copy(phase = name, progress = p) } }
    private fun aborted() = control.signal.value == JobControl.Signal.ABORT
    private fun stop() = LoopOutcome(JobState.ABORTED, "중지됨", outputs.size, "중지")
}

/** AI 답에서 JSON을 안전하게 꺼낸다: 코드블록·앞뒤 말·따옴표 속 중괄호를 견디는 짝 맞추기(정규식 없음) */
object JsonPick {
    fun obj(text: String): JSONObject? {
        var i = text.indexOf('{')
        while (i >= 0) {
            val end = close(text, i)
            if (end > i) runCatching { return JSONObject(text.substring(i, end + 1)) }
            i = text.indexOf('{', i + 1)
        }
        return null
    }
    private fun close(s: String, from: Int): Int {
        var depth = 0; var inStr = false; var esc = false
        for (k in from until s.length) {
            val c = s[k]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return k } }
        }
        return -1
    }
    fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).mapNotNull { i -> it.optString(i).trim().takeIf { s -> s.isNotBlank() } } }.orEmpty()
}
