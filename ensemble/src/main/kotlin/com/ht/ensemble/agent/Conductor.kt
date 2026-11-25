package com.ht.ensemble.agent

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.ai.AiProfiles
import com.ht.ensemble.ai.AiProfiles.Skill
import com.ht.ensemble.ai.Difficulty
import com.ht.ensemble.ai.TokenLedger
import com.ht.ensemble.ai.Tokens
import com.ht.ensemble.command.CommandCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 지휘자(센터) + 합주(Ensemble) — 3.4.
 *
 *  1) 설계: 센터가 목표를 결과물·합격 기준·할 일로 쪼갠다. 센터 자신은 할 일을 맡지 않는다(팀이 3명 이상일 때).
 *  2) 배정 시장: 할 일마다 팀원이 입찰(능력·실적 − 지금 맡은 일 − 센터 벌점). 잘하는 AI가 여럿이면 고르게 퍼진다.
 *  3) 동시 실행 + 칠판: 앞 일이 끝난 할 일은 바로 시작. 팀원은 결과 끝에 [칠판] 메모를 남기고, 이후 팀원은 칠판을 읽고 일한다.
 *  4) 교차 확인: 결과가 나오면 놀고 있는 '다른 회사' AI가 짧게 확인한다(기다리지 않고 병렬). 문제는 칠판에 '주의'로.
 *  5) 보강(자율): 센터가 결과와 칠판을 보고 빠진 일을 스스로 추가한다. 실패한 일은 다른 AI에게 다시 맡긴다.
 *  6) 통합·검수: 센터가 아닌 글 잘 쓰는 AI가 합치고, 센터가 검수. 깊게 이상이면 다른 회사 AI가 한 번 더 본다.
 *  멈춤: 일시정지면 새 요청을 보내지 않고, 중지면 진행 중인 요청까지 즉시 끊는다(사이트의 '중지'도 누른다).
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
    private val files: ProjectFiles? = null,
    private val deliver: suspend (Company.Delivery) -> List<String> = { emptyList() },
) : MissionEngine {
    data class Task(val id: String, val title: String, val who: String, val tool: String = "", val args: Map<String, String> = emptyMap(), val prompt: String = "", val after: List<String> = emptyList(), val round: Int = 0)
    data class Plan(val deliverable: String, val criteria: List<String>, val tasks: List<Task>, val finalWho: String, val finalHow: String, val push: Map<String, String>? = null)

    private val desk = CrewDesk(ctx.jobId, crew, control, emit, clock, ledger)
    private val exec = CommandExec(ctx.jobId, tools, human, gate, emit, clock, autoAllowE2)
    private val outputs = LinkedHashMap<String, String>()
    private val checks = ConcurrentHashMap<String, String>()
    val board get() = desk.board
    private var center: CrewMember? get() = desk.center; set(v) { desk.center = v }
    private var team: List<CrewMember> get() = desk.team; set(v) { desk.team = v }
    private var stagePlan: List<PlanStep> = emptyList()
    private val times = java.util.Collections.synchronizedList(ArrayList<Long>())
    private fun laneOf(m: CrewMember) = desk.laneOf(m)
    private fun lane(m: CrewMember) = desk.lane(m)
    private fun busyOf(m: CrewMember) = desk.busyOf(m)
    private var prog = 0.0
    private var totalTasks = 1
    private val finished = AtomicInteger()
    override val plan get() = stagePlan
    override var next: String = "설계 중"; private set
    override val durations: List<Long> get() = times
    override val progress: Double? get() = prog

    private val maxTasks get() = when (difficulty) { Difficulty.QUICK -> 3; Difficulty.STANDARD -> 5; Difficulty.DEEP -> 7; Difficulty.PROJECT -> 9 }
    private val extraRounds get() = when (difficulty) { Difficulty.QUICK -> 0; Difficulty.STANDARD -> 1; Difficulty.DEEP -> 1; Difficulty.PROJECT -> 2 }

    override suspend fun run(): LoopOutcome {
        if (uploadOnly(ctx.goal)) return upload()
        team = crew.members().filter { crew.available(it) }
        if (team.isEmpty()) return LoopOutcome(JobState.FAILED, "쓸 수 있는 AI가 없어요. 허브 → 계정 하나로에서 연결해 주세요.", 0, "AI 없음")
        val c = team.maxBy { crew.rate(it, Skill.PLAN) * 0.6 + crew.rate(it, Skill.REVIEW) * 0.4 }
        center = c
        StageBus.put(StageBus.Stage(ctx.jobId, ctx.goal, c.name, c.provider.name, "설계", emptyList()))
        emit(AgentEvent.Started(clock(), ctx.goal, c.name))
        emit(AgentEvent.Collab(clock(), "센터", c.name, "설계·검수를 맡고, 할 일은 팀원 ${team.size - 1}명에게 나눠요"))
        return try {
            desk.guarded { conduct(c) }
        } catch (e: CrewDesk.Aborted) {
            StageBus.edit(ctx.jobId) { st -> st.copy(phase = "중지", cards = st.cards.map { if (it.status == StageBus.S.RUN || it.status == StageBus.S.WAIT || it.status == StageBus.S.SWAP || it.status == StageBus.S.CHECK) it.copy(status = StageBus.S.FAIL, doing = "중지됨") else it }) }
            stop()
        }
    }


    /** '이거 깃허브에 올려줘'처럼 올리기만 하는 부탁: 팀을 돌리지 않고 바로 올린다(이 대화의 최근 결과물) */
    private suspend fun upload(): LoopOutcome {
        StageBus.put(StageBus.Stage(ctx.jobId, ctx.goal, "HJTK", "", "올리기", emptyList()))
        phase("올리기", 0.5, "GitHub에 올리는 중")
        StageBus.edit(ctx.jobId) { it.copy(cards = listOf(StageBus.Card("push", "GitHub에 올리기", "HJTK", "", StageBus.S.RUN, "최근 결과물을 커밋 한 번으로", clock()))) }
        val repo = repoFromGoal(ctx.goal) ?: "hjtk-results"
        val r = exec.run(CommandCall("push", "github.push", mapOf("repo" to repo, "message" to "HJTK: ${ctx.goal.take(60)}")))
        StageBus.card(ctx.jobId, "push") { it.copy(status = if (r.ok) StageBus.S.DONE else StageBus.S.FAIL, doing = r.text.lineSequence().first().take(80), endedAt = clock()) }
        phase("완료", 1.0, "완료")
        emit(AgentEvent.Done(clock(), r.text, if (r.ok) "완료" else "실패"))
        return LoopOutcome(if (r.ok) JobState.COMPLETED else JobState.FAILED, r.text, 1, if (r.ok) "완료" else "실패")
    }

    private suspend fun conduct(c: CrewMember): LoopOutcome {
        phase("설계", 0.05, "${c.name}가 할 일을 나누는 중")
        // 1) 설계 → 2) 배정 시장
        val raw = design(c)
        val p = raw.copy(tasks = Dispatch.assign(raw.tasks, team, c, { m, s -> crew.rate(m, s) }) { AiProfiles.skillFor(it.title + " " + it.prompt) })
        totalTasks = p.tasks.size + 2
        stagePlan = p.tasks.map { PlanStep(it.title, 60, StepStatus.TODO) } + PlanStep("보강", 60, StepStatus.TODO) + PlanStep("통합·검수", 90, StepStatus.TODO)
        StageBus.edit(ctx.jobId) { it.copy(phase = "실행", cards = p.tasks.map { t -> card(t) }, note = p.deliverable) }
        emit(AgentEvent.Plan(clock(), stagePlan, p.tasks.firstOrNull()?.title ?: "", 0.12))
        emit(AgentEvent.Collab(clock(), "분배", c.name, p.tasks.groupBy { it.who }.entries.joinToString(" · ") { (w, l) -> "$w ${l.size}" }))

        // 3) 동시 실행 + 칠판 + 교차 확인
        runAll(p.tasks, p)

        // 5) 보강(자율): 실패한 일 재배정 + 센터가 빠진 일을 추가
        var all = p.tasks
        for (round in 1..extraRounds) {
            pauseGate()
            val more = reinforce(c, p, all, round)
            if (more.isEmpty()) break
            val assigned = Dispatch.assign(more, team, c, { m, s -> crew.rate(m, s) }) { AiProfiles.skillFor(it.title + " " + it.prompt) }
            totalTasks += assigned.size
            StageBus.edit(ctx.jobId) { it.copy(phase = "보강", cards = it.cards + assigned.map { t -> card(t) }) }
            emit(AgentEvent.Collab(clock(), "보강", c.name, assigned.joinToString(" · ") { "${it.who}: ${it.title}" }))
            runAll(assigned, p)
            all = all + assigned
        }

        // 6) 통합(센터가 아닌 글 잘 쓰는 AI)
        pauseGate()
        phase("통합", 0.84, "결과물을 하나로 합치는 중")
        val writer = bestFree(Skill.WRITE, avoidCenter = true, hint = p.finalWho)
        StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card("final", "통합 · ${p.deliverable.take(24)}", writer.name, writer.provider.name, StageBus.S.RUN, "칠판과 결과를 합치는 중", clock())) }
        // 통합본에 칠판 메모가 섞여 나오면 떼어 낸다(메모는 칠판으로)
        var draft = absorb(askWithFailover(writer, Skill.WRITE, "final", composePrompt(p)), writer.name)
        StageBus.card(ctx.jobId, "final") { it.copy(status = if (draft.isBlank()) StageBus.S.FAIL else StageBus.S.DONE, doing = "${draft.length}자", endedAt = clock()) }

        // 검수: 센터(+ 깊게 이상이면 다른 회사 AI 한 번 더)
        if (difficulty != Difficulty.QUICK && draft.isNotBlank()) {
            val reviewers = listOfNotNull(c, if (difficulty.ordinal >= Difficulty.DEEP.ordinal) team.filter { it.provider != c.provider && it.provider != writer.provider }.maxByOrNull { crew.rate(it, Skill.REVIEW) } else null)
            for ((round, rv) in reviewers.withIndex()) {
                pauseGate()
                phase("검수", 0.9 + round * 0.04, "${rv.name}가 합격 기준으로 검수하는 중")
                val id = "check$round"
                StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card(id, if (round == 0) "센터 검수" else "교차 검수", rv.name, rv.provider.name, StageBus.S.CHECK, "기준과 대조하는 중", clock())) }
                // 검수 답은 짧은 JSON일 수 있다({"pass":true}) → 짧다고 '부실'로 보지 않는다
                val fixes = parseFixes(askWithFailover(rv, Skill.REVIEW, id, reviewPrompt(p, draft), minLen = 8))
                if (fixes.isEmpty()) { StageBus.card(ctx.jobId, id) { it.copy(status = StageBus.S.DONE, doing = "합격", endedAt = clock()) }; emit(AgentEvent.Collab(clock(), "검수", rv.name, "합격")); continue }
                emit(AgentEvent.Collab(clock(), "검수", rv.name, "고칠 점 ${fixes.size}개: " + fixes.take(3).joinToString(" / ")))
                StageBus.card(ctx.jobId, id) { it.copy(doing = "고칠 점 ${fixes.size}개 → ${writer.name}가 다듬는 중") }
                val revised = absorb(askWithFailover(writer, Skill.WRITE, id, revisePrompt(p, draft, fixes)), writer.name)
                if (revised.length > draft.length * 0.5) draft = revised
                StageBus.card(ctx.jobId, id) { it.copy(status = StageBus.S.DONE, doing = "반영 완료", endedAt = clock()) }
            }
        }
        if (draft.isBlank()) draft = synchronized(outputs) { outputs.values.filter { it.isNotBlank() }.joinToString("\n\n") }
        if (draft.isBlank()) return LoopOutcome(JobState.FAILED, "팀원이 모두 답하지 못했어요. 잠시 뒤 '이어서 진행'으로 다시 해 주세요.", outputs.size, "실패")

        // 완성 → (요청했으면) GitHub
        val name = "결과_" + ctx.goal.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(24) + ".md"
        val shipped = runCatching { tools.run(CommandCall("ship", "task.artifact", mapOf("name" to name, "content" to "# ${p.deliverable}\n\n$draft"))).ok }.getOrDefault(false)
        if (!shipped) files?.write(name, "# ${p.deliverable}\n\n$draft")      // 도구가 없으면(SDK) 프로젝트 폴더에 직접
        // 결과물 전달: 코드 블록은 실제 파일로(웹 파일은 app/ — 그대로 APK가 된다), 글은 완성본으로
        var app = false
        files?.let { fs ->
            val code = com.ht.ensemble.util.CodeFiles.extract(draft)
            val web = code.filter { it.first.substringAfterLast('.') in setOf("html", "css", "js") }
            if (web.any { it.first.endsWith(".html") }) {
                app = true
                val entry = web.firstOrNull { it.first.endsWith("index.html") } ?: web.first { it.first.endsWith(".html") }
                web.forEach { (f, body) -> fs.write("app/" + (if (f == entry.first) "index.html" else f.substringAfterLast('/')), body) }
            }
            code.filter { it !in web }.forEach { (f, body) -> fs.write(f, body) }
        }
        val delivered = runCatching { deliver(Company.Delivery(p.deliverable, "", listOf(name), "센터 ${c.name} · HJTK", app)) }.getOrElse { emptyList() }
        var pushNote = if (delivered.isEmpty()) "" else "\n받은 결과물: " + delivered.joinToString(" · ")
        if (p.push != null || GITHUB.containsMatchIn(ctx.goal)) {
            pauseGate()
            phase("올리기", 0.97, "GitHub에 올리는 중")
            // 결과 글 속 코드 블록을 실제 파일로 풀어 둔다 → 저장소가 바로 쓸 수 있는 모양이 된다
            if (files == null) com.ht.ensemble.util.CodeFiles.extract(draft).forEach { (f, code) -> runCatching { tools.run(CommandCall("file", "task.artifact", mapOf("name" to f, "content" to code))) } }
            val args = (p.push ?: emptyMap()).toMutableMap()
            args.putIfAbsent("repo", repoName(p)); args.putIfAbsent("message", "HJTK: ${p.deliverable.take(60)}")
            val r = exec.run(CommandCall("push", "github.push", args))
            pushNote += "\nGitHub: " + (if (r.ok) r.text.lineSequence().first() else "올리지 못함 — ${r.text.take(120)}")
        }
        val credits = desk.credits()
        val final = draft.trim() + "\n\n---\n센터 ${c.name} · 일한 AI $credits · 칠판 ${board.all().size} · 난이도 ${difficulty.label} · 토큰 약 ${Tokens.fmt(ledger.total())}\n파일: $name$pushNote"
        phase("완료", 1.0, "완료")
        emit(AgentEvent.Done(clock(), final, "완료"))
        return LoopOutcome(JobState.COMPLETED, final, outputs.size, "완료")
    }

    // ───────────── 설계 ─────────────
    private suspend fun design(c: CrewMember): Plan {
        val roster = AiProfiles.roster(team.filter { it.key != c.key || team.size < 3 }.map { it.name to it.provider })
        val raw = askRaw(c, planPrompt(roster), "plan")
        parsePlan(raw, team)?.let { return it }
        emit(AgentEvent.Note(clock(), "설계 형식을 다시 요청했어요"))
        parsePlan(askRaw(c, "방금 답을 아래 JSON 하나로만 다시 보내 줘(설명 없이). 키: deliverable, criteria[], tasks[{id,title,who,tool,args,prompt,after[]}], final{who,how}", "plan"), team)?.let { return it }
        emit(AgentEvent.Collab(clock(), "설계", "HJTK", "센터의 설계를 읽지 못해 작업 유형별 공정으로 진행해요"))
        return template()
    }

    private fun planPrompt(roster: String) = buildString {
        appendLine("너는 AI 팀의 센터(지휘자)다. 아래 목표를 팀원에게 나눠 맡기고, 끝까지 품질을 책임진다. 너 자신은 설계와 검수만 한다.")
        appendLine("목표: ${ctx.goal}")
        appendLine("현재: ${ctx.nowText}"); ctx.userName?.let { appendLine("사용자: $it") }
        if (ctx.memories.isNotEmpty()) appendLine("사용자에 대해 알려진 것: " + ctx.memories.take(5).joinToString(" / "))
        ctx.activeSkillGuide?.let { appendLine("작업 지침: $it") }
        appendLine(); appendLine("[팀원 — 이름 · 현실적인 강점과 약점]"); appendLine(roster)
        if (catalog.isNotBlank()) { appendLine(); appendLine("[도구 — 필요할 때만, AI가 스스로 할 수 있으면 쓰지 말 것]"); appendLine(catalog) }
        appendLine()
        appendLine("규칙:")
        appendLine("- 할 일은 ${maxTasks}개 이하. 서로 기다릴 필요 없는 일은 after를 비워 동시에 진행되게 하라(속도).")
        appendLine("- 팀원 모두에게 일을 나눠라. 한 팀원에게 2개 넘게 몰지 마라. who는 위 팀원 이름 그대로.")
        appendLine("- 강점에 맞게: 최신 사실 조사→조사 강한 AI, 긴 글→글 강한 AI, 코드→코드 강한 AI, 반론·검증→비판 강한 AI.")
        appendLine("- 같은 일을 두 명에게 시킬 땐 관점을 다르게(예: 국내/해외, 찬성/반대) 적어 결과가 겹치지 않게.")
        appendLine("- prompt는 그 팀원이 바로 일할 수 있게 구체적으로(분량·형식·관점). 앞 일의 결과가 필요하면 after에 id를 적어라.")
        appendLine("- 도구가 필요하면 tool에 명령 이름, args에 인자. 검색어는 목표 문장을 그대로 쓰지 말고 실제로 찾을 키워드로.")
        appendLine("- 글쓰기·창작처럼 사실 조사가 필요 없는 목표에는 검색을 넣지 마라.")
        appendLine("- 목표에 GitHub(깃허브)에 올리라는 말이 있으면 tasks에 넣지 말고 \"push\":{\"repo\":\"영문-소문자-이름\",\"private\":\"true\"}만 적어라(완성 뒤 자동으로 올린다).")
        appendLine("- final.who는 결과물을 합칠 팀원(너 말고 글 강한 AI), final.how는 합치는 방식.")
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
            val tool = t.optString("tool").ifBlank { t.optString("cmd") }.trim().takeIf { it.isNotBlank() && it != "github.push" && tools.spec(it) != null }.orEmpty()
            if (t.optString("tool") == "github.push") return@mapNotNull null
            Task(t.optString("id").ifBlank { "t${i + 1}" }, title.take(40), t.optString("who"), tool, args, t.optString("prompt").ifBlank { title }, JsonPick.strings(t.optJSONArray("after")))
        }.take(maxTasks)
        if (tasks.isEmpty()) return null
        val ids = tasks.map { it.id }.toSet()
        val fixed = tasks.map { t -> t.copy(after = t.after.filter { it in ids && it != t.id }) }
        val fin = o.optJSONObject("final")
        val push = o.optJSONObject("push")?.let { pj -> pj.keys().asSequence().associateWith { k -> pj.optString(k) } }
        return Plan(o.optString("deliverable").ifBlank { ctx.goal.take(50) }, JsonPick.strings(o.optJSONArray("criteria")).ifEmpty { listOf("목표의 요구를 빠짐없이 다룬다", "바로 쓸 수 있는 완성도") },
            fixed, fin?.optString("who").orEmpty(), fin?.optString("how").orEmpty(), push)
    }

    /** 작업 유형별 공정(설계를 못 읽었을 때). 누가 할지는 배정 시장이 정한다 */
    private fun template(): Plan {
        val g = ctx.goal
        val research = Regex("찾|검색|조사|최신|추천|비교|뉴스|가격|일정|공모전|채용|날씨|현황|트렌드").containsMatchIn(g)
        val code = Regex("코드|앱|프로그램|스크립트|html|웹사이트").containsMatchIn(g)
        return when {
            code -> Plan("동작하는 코드와 사용법", listOf("실행 가능", "설명 포함"), listOf(
                Task("t1", "구조 설계", "", prompt = "다음 목표의 구조·파일·핵심 로직을 설계해 줘: $g"),
                Task("t2", "예외·엣지 케이스 목록", "", prompt = "다음 목표에서 놓치기 쉬운 예외·엣지 케이스·보안 문제를 목록으로: $g"),
                Task("t3", "구현", "", prompt = "설계대로 전체 코드를 작성해 줘. 생략 없이. 엣지 케이스 처리 포함.", after = listOf("t1", "t2")),
                Task("t4", "코드 검토", "", prompt = "코드의 버그·빠진 처리를 찾아 고친 코드로 줘.", after = listOf("t3"))), "", "검토 반영본")
            research -> Plan(g.take(50), listOf("사실마다 출처", "최신 정보", "바로 쓸 수 있게 정리"), listOf(
                Task("t1", "최신 자료 조사", "", prompt = "웹에서 직접 검색해 다음 목표에 필요한 최신 사실을 출처 링크와 함께 정리해 줘: $g"),
                Task("t2", "다른 관점 조사", "", prompt = "같은 주제를 다른 출처·해외 관점으로 조사해 줘(출처 링크 포함): $g"),
                Task("t3", "반론·함정 찾기", "", prompt = "이 주제에서 흔한 오해·주의할 점·반대 근거를 정리해 줘: $g"),
                Task("t4", "분석·추천", "", prompt = "조사 결과를 비교·검증하고 핵심 결론과 추천을 정리해 줘.", after = listOf("t1", "t2", "t3"))), "", "결론 먼저, 근거·출처 뒤에")
            else -> Plan(g.take(50), listOf("요구를 빠짐없이", "독창적이고 구체적", "완성된 글"), listOf(
                Task("t1", "콘셉트 발상", "", prompt = "다음 목표를 위한 서로 다른 콘셉트 3개와 가장 좋은 1개의 상세 목차를 제안해 줘: $g"),
                Task("t2", "논점·근거 보강", "", prompt = "같은 목표에 필요한 핵심 논점·근거·반론을 정리해 줘: $g"),
                Task("t3", "본문 작성", "", prompt = "콘셉트와 논점을 바탕으로 완성된 본문을 써 줘.", after = listOf("t1", "t2"))), "", "본문을 다듬어 완성")
        }
    }

    // ───────────── 실행 ─────────────
    private suspend fun runAll(tasks: List<Task>, p: Plan) = coroutineScope {
        val jobs = HashMap<String, Deferred<Unit>>()
        val side = java.util.Collections.synchronizedList(ArrayList<Job>())
        for (t in tasks) {
            jobs[t.id] = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                t.after.forEach { jobs[it]?.await() }
                pauseGate()
                val m = team.firstOrNull { it.name == t.who } ?: bestFree(AiProfiles.skillFor(t.title + t.prompt), avoidCenter = true)
                val t0 = clock()
                val out = if (t.tool.isNotBlank()) toolTask(t, m) else lane(m).withPermit { aiTask(t, m) }
                synchronized(outputs) { outputs[t.id] = out }
                times += clock() - t0
                prog = 0.12 + 0.70 * finished.incrementAndGet() / totalTasks.coerceAtLeast(1)
                stagePlan = stagePlan.map { s -> if (s.title == t.title) s.copy(status = StepStatus.DONE) else s }
                emit(AgentEvent.Plan(clock(), stagePlan, tasks.firstOrNull { it.id !in outputs }?.title ?: "통합", prog))
                StageBus.edit(ctx.jobId) { it.copy(progress = prog) }
                if (out.length > 200 && difficulty != Difficulty.QUICK && t.tool.isBlank()) crossCheck(this@coroutineScope, t, out)?.let { side += it }
            }
        }
        // 지도(jobs)를 다 채운 뒤에 출발 — 채우는 도중 뒤 일이 앞 일을 못 찾고 먼저 달려가던 경쟁 상태 방지
        jobs.values.forEach { it.start() }
        jobs.values.forEach { it.await() }
        side.toList().forEach { it.join() }
    }

    private suspend fun aiTask(t: Task, m0: CrewMember): String {
        StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.RUN, doing = doingText(t), startedAt = clock()) }
        val raw = askWithFailover(m0, AiProfiles.skillFor(t.title + " " + t.prompt), t.id, taskPrompt(t))
        val out = absorb(raw, StageBus.stages.value[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.who ?: m0.name)
        StageBus.card(ctx.jobId, t.id) { it.copy(status = if (out.isBlank()) StageBus.S.FAIL else StageBus.S.DONE, doing = if (out.isBlank()) "답 없음 · 보강 때 다른 AI가 다시" else "${out.length}자 완료", endedAt = clock()) }
        return out
    }

    private fun absorb(raw: String, who: String) = desk.absorb(raw, who)

    /** 교차 확인: 놀고 있는 다른 회사 AI가 짧게 본다(본 흐름은 기다리지 않음) */
    private fun crossCheck(scope: CoroutineScope, t: Task, out: String): Job? {
        val author = StageBus.stages.value[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.provider
        val pick = team.filter { it.provider.name != author && it.key != center?.key && busyOf(it) == 0 && crew.available(it) }
            .maxByOrNull { crew.rate(it, Skill.REVIEW) } ?: return null
        desk.hold(pick)     // 바로 잡아 둔다(같은 AI가 동시에 두 번 뽑히지 않게)
        return scope.launch {
            try {
                pauseGate()
                StageBus.card(ctx.jobId, t.id) { it.copy(peer = "${pick.name} 확인 중") }
                val r = runCatching { crew.port(pick, laneOf(pick)).send(checkPrompt(t, out), ctx.jobId, 1, true) }.getOrElse { e -> if (e is CancellationException) throw e; LlmReply("", "ERR", false, 0) }
                crew.report(pick, r.text.isNotBlank(), r.ms, r.text, r.via)
                ledger.add(pick.name, Tokens.estimate(out) / 2, Tokens.estimate(r.text))
                val v = r.text.trim()
                val fine = v.isBlank() || Regex("^(이상 ?없|문제 ?없|OK|확인 ?완료)", RegexOption.IGNORE_CASE).containsMatchIn(v)
                if (!fine && !AiProfiles.limitHit(v, r.via)) {
                    checks[t.id] = "${pick.name}: ${v.take(600)}"
                    board.add(pick.name, "주의", "${t.title} — ${v.lineSequence().first().take(160)}")
                    StageBus.edit(ctx.jobId) { it.copy(board = desk.recentBoard()) }
                }
                StageBus.card(ctx.jobId, t.id) { it.copy(peer = if (fine) "${pick.name} 확인 · 이상 없음" else "${pick.name} 확인 · 주의 남김") }
                desk.thank(pick)
            } finally { desk.release(pick) }
        }
    }

    /** 보강: 실패한 일은 다른 AI로 다시, 센터가 결과·칠판을 보고 빠진 일을 추가(최대 2개) */
    private suspend fun reinforce(c: CrewMember, p: Plan, done: List<Task>, round: Int): List<Task> {
        val failed = done.filter { synchronized(outputs) { outputs[it.id] }.isNullOrBlank() && it.round == round - 1 }
        val retry = failed.map { t -> t.copy(id = "${t.id}r$round", title = "${t.title} (다시)", who = team.filter { it.name != t.who && it.key != c.key }.maxByOrNull { crew.rate(it, AiProfiles.skillFor(t.title)) }?.name ?: "", round = round, after = emptyList()) }
        phase("보강", 0.8, "${c.name}가 빠진 곳을 찾는 중")
        StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card("gap$round", "빠진 곳 찾기", c.name, c.provider.name, StageBus.S.CHECK, "결과와 칠판을 대조하는 중", clock())) }
        val v = askRaw(c, gapPrompt(p, done), "gap$round")
        val o = JsonPick.obj(v)
        val arr = o?.optJSONArray("more") ?: JSONArray()
        val ids = synchronized(outputs) { outputs.keys.toSet() }
        val more = (0 until minOf(arr.length(), 2)).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifBlank { return@mapNotNull null }
            Task("x$round${i + 1}", title.take(40), t.optString("who"), prompt = t.optString("prompt").ifBlank { title }, after = JsonPick.strings(t.optJSONArray("after")).filter { it in ids }, round = round)
        }
        StageBus.card(ctx.jobId, "gap$round") { it.copy(status = StageBus.S.DONE, doing = if (more.isEmpty() && retry.isEmpty()) "빠진 곳 없음" else "추가 ${more.size} · 재시도 ${retry.size}", endedAt = clock()) }
        return retry + more
    }

    private suspend fun toolTask(t: Task, m: CrewMember): String {
        StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.RUN, doing = "${t.tool} 실행 중", startedAt = clock()) }
        val args = t.args.mapValues { (_, v) -> fill(v) }
        val r = exec.run(CommandCall("c-${t.id}", t.tool, args))
        if (r.ok && r.text.length > 40) {
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.DONE, doing = "도구 결과 ${r.text.length}자", endedAt = clock()) }
            return r.text.take(6000)
        }
        pauseGate()
        val alt = bestFree(Skill.RESEARCH, avoidCenter = true)
        emit(AgentEvent.Collab(clock(), "교체", alt.name, "'${t.tool}'가 막혀서 ${alt.name}가 직접 찾아요"))
        StageBus.card(ctx.jobId, t.id) { it.copy(who = alt.name, provider = alt.provider.name, status = StageBus.S.SWAP, doing = "${alt.name}가 직접 검색하는 중") }
        val raw = lane(alt).withPermit {
            askWithFailover(alt, Skill.RESEARCH, t.id, "웹에서 직접 검색해서 다음을 조사하고, 사실마다 출처 링크를 붙여 정리해 줘.\n할 일: ${t.title}\n" +
                args.entries.joinToString(" · ") { "${it.key}=${it.value}" } + "\n전체 목표: ${ctx.goal}" + boardTail())
        }
        val out = absorb(raw, alt.name)
        StageBus.card(ctx.jobId, t.id) { it.copy(status = if (out.isBlank()) StageBus.S.FAIL else StageBus.S.DONE, doing = if (out.isBlank()) "실패" else "${out.length}자 완료", endedAt = clock()) }
        return out
    }

    private fun doingText(t: Task) = when (AiProfiles.skillFor(t.title + t.prompt)) {
        Skill.RESEARCH -> "자료를 찾는 중"; Skill.WRITE -> "쓰는 중"; Skill.CODE -> "코드 짜는 중"; Skill.REVIEW -> "검토하는 중"
        Skill.PLAN -> "구조를 짜는 중"; Skill.CREATIVE -> "아이디어 내는 중"; Skill.REASON -> "분석하는 중"; Skill.LONG -> "자료를 읽는 중"; else -> "일하는 중"
    } + " · " + t.title

    /** {t1} 같은 자리표시를 앞 결과로(URL이 필요하면 URL만) */
    private fun fill(v: String): String = Regex("""\{(t\d+)\}""").replace(v) { m ->
        val o = synchronized(outputs) { outputs[m.groupValues[1]] }.orEmpty()
        val urls = Regex("""https?://[^\s)\]>"']+""").findAll(o).map { it.value }.distinct().take(6).toList()
        if (v.trim() == m.value && urls.isNotEmpty()) urls.joinToString(",") else o.take(300)
    }

    private suspend fun askWithFailover(first: CrewMember, skill: Skill, cardId: String, prompt: String, minLen: Int = 20) = desk.ask(first, skill, cardId, prompt, minLen)
    private suspend fun askRaw(m: CrewMember, prompt: String, cardId: String) = desk.askCenter(m, prompt, takeOver = cardId == "plan")
    private fun next(skill: Skill, tried: Set<String>, allowCenter: Boolean = false) = desk.next(skill, tried, allowCenter)
    private fun bestFree(skill: Skill, avoidCenter: Boolean, hint: String = "") = desk.bestFree(skill, avoidCenter, hint)
    private suspend fun pauseGate() = desk.pauseGate()

    // ───────────── 프롬프트 ─────────────
    private fun materials(ids: List<String>, budget: Int): String {
        if (ids.isEmpty()) return ""
        val each = budget / ids.size
        return ids.mapNotNull { id -> synchronized(outputs) { outputs[id] }?.takeIf { it.isNotBlank() }?.let { "[$id 결과]\n" + it.take(each) + (checks[id]?.let { c -> "\n(교차 확인 · $c)" } ?: "") } }.joinToString("\n\n")
    }

    private fun boardTail(): String { val d = board.digest(); return if (d.isBlank()) "" else "\n\n[팀 칠판 — 다른 팀원이 알아낸 것]\n$d" }

    private val boardAsk = "\n\n끝에 다른 팀원에게 알릴 것이 있으면 마지막 줄들에 이렇게(3줄 이내, 없으면 생략):\n[칠판]\n사실: 확인한 핵심 사실(출처)\n결정: 정한 방향\n질문: 다른 팀원이 확인해 줬으면 하는 것"

    private fun taskPrompt(t: Task) = buildString {
        appendLine("[팀 작업 · 센터 ${center?.name ?: ""}가 배정] 전체 목표: ${ctx.goal}")
        appendLine("팀: " + team.joinToString(", ") { it.name } + " — 각자 다른 일을 동시에 하고 있다. 네 몫에 집중해라.")
        appendLine("네 할 일: ${t.title}")
        appendLine(t.prompt)
        val m = materials(t.after, difficulty.budgetChars)
        if (m.isNotBlank()) { appendLine(); appendLine("앞 팀원의 결과:"); appendLine(m) }
        append(boardTail())
        appendLine(); append("완성된 결과물만 써라(인사·설명·되묻기 없이). 한국어.")
        append(boardAsk)
    }

    private fun checkPrompt(t: Task, out: String) = "[교차 확인] 팀 목표: ${ctx.goal}\n할 일: ${t.title}\n아래 결과에서 사실 오류·빠진 핵심·모순만 3줄 이내로 지적해. 문제가 없으면 '이상 없음' 한 마디만.\n\n${out.take(5000)}"

    private fun gapPrompt(p: Plan, done: List<Task>) = buildString {
        appendLine("[센터 보강 판단] 목표: ${ctx.goal}")
        appendLine("결과물: ${p.deliverable} · 합격 기준: " + p.criteria.joinToString(" / "))
        appendLine("지금까지 결과(앞부분):")
        done.forEach { t -> val o = synchronized(outputs) { outputs[t.id] }.orEmpty(); appendLine("- ${t.id} ${t.title} (${t.who}): " + (if (o.isBlank()) "실패" else o.take(280).replace('\n', ' '))) }
        val d = board.digest(1600); if (d.isNotBlank()) { appendLine("팀 칠판:"); appendLine(d) }
        appendLine("팀원: " + team.filter { it.key != center?.key }.joinToString(", ") { it.name })
        append("합격 기준을 채우는 데 꼭 필요한데 빠진 일만 최대 2개. 칠판의 '질문'에 답하는 일도 좋다. 충분하면 빈 배열. JSON 하나만: {\"more\":[{\"title\":\"\",\"who\":\"팀원\",\"prompt\":\"구체 지시\",\"after\":[\"t1\"]}]}")
    }

    private fun composePrompt(p: Plan) = buildString {
        appendLine("[최종 통합] 목표: ${ctx.goal}")
        appendLine("결과물: ${p.deliverable}")
        appendLine("합격 기준: " + p.criteria.joinToString(" / "))
        if (p.finalHow.isNotBlank()) appendLine("합치는 방식: ${p.finalHow}")
        ctx.activeSkillGuide?.let { appendLine("작업 지침: $it") }
        appendLine(); appendLine("팀원 결과:"); appendLine(materials(synchronized(outputs) { outputs.keys.toList() }, difficulty.budgetChars + 4000))
        val d = board.digest(1800); if (d.isNotBlank()) { appendLine(); appendLine("팀 칠판(주의는 반드시 반영):"); appendLine(d) }
        appendLine(); append("위 결과를 하나의 완성된 결과물로 다듬어 써라. 중복은 합치고, 모순은 더 근거 있는 쪽으로, '주의'는 고쳐서, 출처는 살려라. 제목부터 바로 시작.")
    }

    private fun reviewPrompt(p: Plan, draft: String) = buildString {
        appendLine("[검수] 목표: ${ctx.goal}")
        appendLine("합격 기준: " + p.criteria.joinToString(" / "))
        appendLine("결과물:"); appendLine(draft.take(difficulty.budgetChars + 6000))
        appendLine(); append("기준에 못 미친 점만 JSON으로: {\"pass\":true/false,\"fixes\":[\"구체적으로 고칠 점\"]}. 충분하면 pass:true, fixes:[].")
    }

    private fun revisePrompt(p: Plan, draft: String, fixes: List<String>) = buildString {
        appendLine("[다듬기] 목표: ${ctx.goal}"); appendLine("검수에서 지적한 점:"); fixes.forEach { appendLine("- $it") }
        appendLine("원본:"); appendLine(draft)
        append("지적만 반영한 완성본 전체를 다시 써라.")
    }

    private fun parseFixes(v: String): List<String> {
        val o = JsonPick.obj(v) ?: return emptyList()
        if (o.optBoolean("pass", false)) return emptyList()
        return JsonPick.strings(o.optJSONArray("fixes")).take(6)
    }

    private fun repoName(p: Plan): String {
        repoFromGoal(ctx.goal)?.let { return it }
        val ascii = (p.deliverable + " " + ctx.goal).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return if (ascii.length >= 3) ascii else "hjtk-" + ctx.jobId.take(6).lowercase()
    }

    // ───────────── 상태 ─────────────
    private fun card(t: Task): StageBus.Card {
        val m = team.firstOrNull { it.name == t.who }
        return StageBus.Card(t.id, t.title, t.who.ifBlank { "배정 중" }, m?.provider?.name ?: "", StageBus.S.WAIT, if (t.after.isEmpty()) "곧 시작" else "앞 일 기다리는 중", after = t.after, tool = t.tool.isNotBlank(), round = t.round)
    }
    private fun phase(name: String, p: Double, nxt: String) { prog = maxOf(prog, p); next = nxt; StageBus.edit(ctx.jobId) { it.copy(phase = name, progress = prog) } }
    private fun stop() = LoopOutcome(JobState.ABORTED, "중지됨", outputs.size, "중지")

    companion object {
        val GITHUB = Regex("(깃허브|깃헙|github)", RegexOption.IGNORE_CASE)
        /** "my-app 저장소로", "레포 이름: x", "repo x"에서 저장소 이름 */
        fun repoFromGoal(goal: String): String? =
            Regex("""(?:레포|저장소|repo(?:sitory)?)\s*(?:이름)?\s*(?:은|는|:|=)?\s*["'`]?([A-Za-z0-9][A-Za-z0-9._/-]{0,80})""", RegexOption.IGNORE_CASE).find(goal)?.groupValues?.get(1)
                ?: Regex("""["'`]?([A-Za-z0-9][A-Za-z0-9._/-]{1,80})["'`]?\s*(?:레포|저장소|repo)""", RegexOption.IGNORE_CASE).find(goal)?.groupValues?.get(1)?.takeIf { !it.equals("github", true) }
        /** 올리기만 하는 부탁인가(무언가 새로 만들라는 말이 없으면) */
        fun uploadOnly(goal: String) = GITHUB.containsMatchIn(goal) && Regex("올려|올리|업로드|push|푸시", RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
            !Regex("만들|작성|써\\s*줘|짜\\s*줘|생성|개발|구현|조사|찾아|정리해|분석|번역|고쳐|수정").containsMatchIn(goal)
    }
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
