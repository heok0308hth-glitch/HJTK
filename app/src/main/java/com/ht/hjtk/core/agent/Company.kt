package com.ht.hjtk.core.agent

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.ai.AiProfiles
import com.ht.hjtk.core.ai.AiProfiles.Skill
import com.ht.hjtk.core.ai.Difficulty
import com.ht.hjtk.core.ai.TokenLedger
import com.ht.hjtk.core.ai.Tokens
import com.ht.hjtk.core.command.CommandCall
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** 프로젝트 폴더(작업 공간). 앱은 작업 폴더에, 테스트는 메모리에 쓴다 */
interface ProjectFiles {
    fun write(path: String, text: String)
    fun read(path: String): String?
    fun list(): List<String>
}

class MemoryFiles : ProjectFiles {
    private val m = Collections.synchronizedMap(LinkedHashMap<String, String>())
    override fun write(path: String, text: String) { m[path] = text }
    override fun read(path: String) = m[path]
    override fun list() = synchronized(m) { m.keys.toList() }
}

/**
 * 회사(Company) — 3.5. AI 팀이 실제 회사처럼 30분~3시간 동안 하나의 프로젝트를 진행한다.
 *
 *  킥오프   PM(센터)이 프로젝트 헌장을 쓴다: 결과물·독자·품질 루브릭(채점 기준)·파일 계획·마일스톤·첫 스프린트 티켓.
 *  스프린트 티켓 하나 = 파일 하나. 담당자가 쓰고 → 다른 회사 AI가 루브릭으로 채점(코드 리뷰처럼) →
 *           기준(8.0) 미달이면 지적을 반영해 다시 쓴다(최대 2번). 통과한 판만 프로젝트 폴더에 합쳐진다.
 *  스탠드업 PM이 파일 현황·점수·칠판·남은 시간을 보고 다음 스프린트를 짠다(새 티켓, 개선 티켓, 결정 기록).
 *           품질이 9.0을 넘고 더 할 가치가 없을 때만 일찍 끝낸다. 아니면 시간이 다 될 때까지 다듬는다.
 *  최종 QA  다른 회사 AI 둘이 프로젝트 전체를 루브릭으로 채점. 미달이고 시간이 있으면 다듬기 스프린트 한 번 더.
 *  보고     프로젝트 보고서(00_프로젝트_보고서.md) + 실제 작업 기록(스프린트·점수·기여·시간).
 *  체크포인트: 티켓이 끝날 때마다 저장 → 앱이 꺼져도 이어서. 발열은 pace·width로 조절.
 */
class Company(
    private val ctx: AgentContext,
    crew: Crew,
    private val tools: ToolPort,
    human: HumanPort,
    gate: ApprovalGate,
    control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    autoAllowE2: Boolean = false,
    val minutes: Int = 60,
    val ledger: TokenLedger = TokenLedger(),
    private val catalog: String = "",
    private val files: ProjectFiles = MemoryFiles(),
    private val save: (String) -> Unit = {},
    private val load: () -> String? = { null },
    private val pace: suspend () -> Unit = {},
    private val width: () -> Int = { 3 },
) : MissionEngine {

    data class Criterion(val name: String, val desc: String)
    data class FilePlan(val path: String, val purpose: String)
    data class Charter(val title: String, val summary: String, val audience: String, val rubric: List<Criterion>, val plan: List<FilePlan>, val milestones: List<String>)
    class Ticket(val id: String, val title: String, val file: String, val brief: String, val inputs: List<String>, val after: List<String>, val sprint: Int) {
        @Volatile var status = "todo"     // todo · doing · review · done · failed
        @Volatile var who = ""
        @Volatile var score = 0.0
        @Volatile var attempts = 0
        @Volatile var issues: List<String> = emptyList()
        @Volatile var reviewer = ""
    }

    private val desk = CrewDesk(ctx.jobId, crew, control, emit, clock, ledger)
    private val exec = CommandExec(ctx.jobId, tools, human, gate, emit, clock, autoAllowE2)
    private var charter: Charter? = null
    private val tickets = Collections.synchronizedList(ArrayList<Ticket>())
    private val decisions = Collections.synchronizedList(ArrayList<String>())
    private val history = Collections.synchronizedList(ArrayList<Pair<Int, Double>>())  // 스프린트 → 평균 점수
    private var sprint = 0
    private var startedAt = 0L
    private var elapsedBefore = 0L
    private var qa: Double = 0.0
    private var qaIssues: List<String> = emptyList()
    private var stagePlan: List<PlanStep> = emptyList()
    private val times = Collections.synchronizedList(ArrayList<Long>())
    private var prog = 0.0
    override val plan get() = stagePlan
    override var next: String = "킥오프"; private set
    override val durations: List<Long> get() = times
    override val progress: Double? get() = prog

    private val budget get() = minutes * 60_000L
    private fun elapsed() = elapsedBefore + (clock() - startedAt)
    private fun left() = budget - elapsed()
    /** 마무리(최종 QA·보고서)를 위해 남겨 둘 시간 */
    private val reserve get() = maxOf(6 * 60_000L, (budget * 0.15).toLong())
    private val bar = 8.0
    private val excellent = 9.0
    private val maxSprints = 14
    private val chars = Difficulty.PROJECT.budgetChars
    private fun w() = width().coerceIn(1, 4)

    override suspend fun run(): LoopOutcome {
        startedAt = clock()
        desk.team = desk.crew.members().filter { desk.crew.available(it) }
        if (desk.team.isEmpty()) return LoopOutcome(JobState.FAILED, "쓸 수 있는 AI가 없어요. 허브 → 계정 하나로에서 연결해 주세요.", 0, "AI 없음")
        val restored = restore()
        val pm = desk.member(restoredCenter ?: "") ?: desk.team.maxBy { desk.crew.rate(it, Skill.PLAN) * 0.6 + desk.crew.rate(it, Skill.REVIEW) * 0.4 }
        desk.center = pm
        StageBus.put(StageBus.Stage(ctx.jobId, ctx.goal, pm.name, pm.provider.name, if (restored) "재개" else "킥오프", emptyList(), budgetMin = minutes))
        if (restored) { StageBus.edit(ctx.jobId) { it.copy(cards = tickets.map { t -> card(t) }) }; status() }
        emit(AgentEvent.Started(clock(), ctx.goal, pm.name))
        emit(AgentEvent.Collab(clock(), "PM", pm.name, if (restored) "멈췄던 곳(스프린트 $sprint)부터 이어서 진행해요 · 남은 시간 ${mins(left())}분"
            else "프로젝트를 맡았어요 · 시간 ${minutes}분 · 팀원 ${desk.team.size - 1}명 · 스프린트로 나눠 진행하고 매번 다른 회사 AI가 채점해요"))
        return try {
            desk.guarded { work() }
        } catch (e: CrewDesk.Aborted) {
            checkpoint()
            StageBus.edit(ctx.jobId) { st -> st.copy(phase = "중지", cards = st.cards.map { if (it.status in setOf(StageBus.S.RUN, StageBus.S.CHECK, StageBus.S.SWAP)) it.copy(status = StageBus.S.WAIT, doing = "중지됨 · 이어하기 가능") else it }) }
            LoopOutcome(JobState.ABORTED, "중지됨 — 스프린트 $sprint, 완료 티켓 ${tickets.count { it.status == "done" }}개는 폴더에 남아 있어요. '이어서 진행'으로 계속할 수 있어요.", tickets.size, "중지")
        }
    }

    private suspend fun work(): LoopOutcome {
        val resumed = charter != null
        if (!resumed) kickoff()
        // 이어하기(앱이 꺼졌다 켜진 경우만): 끝나지 않은 티켓부터
        if (resumed && sprint > 0) tickets.filter { it.status == "todo" }.takeIf { it.isNotEmpty() }?.let { runSprint(it); retro() }
        while (sprint < maxSprints && left() > reserve) {
            if (sprint == 0) { sprint = 1; runSprint(tickets.filter { it.sprint == 1 && it.status != "done" }); retro(); continue }
            val nextTickets = standup() ?: break
            sprint++
            nextTickets.forEach { tickets += it }
            StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + nextTickets.map { t -> card(t) }) }
            runSprint(nextTickets)
            retro()
        }
        return finish()
    }

    // ───────────── 킥오프 ─────────────
    private suspend fun kickoff() {
        val pm = desk.center!!
        phase("킥오프", "${pm.name}가 프로젝트 헌장을 쓰는 중")
        StageBus.edit(ctx.jobId) { it.copy(cards = listOf(StageBus.Card("kickoff", "프로젝트 헌장", pm.name, pm.provider.name, StageBus.S.RUN, "목표·루브릭·파일 계획·첫 스프린트", clock()))) }
        val raw = desk.askCenter(pm, kickoffPrompt(), takeOver = true)
        val parsed = parseCharter(raw) ?: parseCharter(desk.askCenter(desk.center!!, "방금 답을 설명 없이 JSON 하나로만 다시 보내 줘. 키: title, summary, audience, rubric[{name,desc}], files[{path,purpose}], milestones[], backlog[{id,title,file,brief,inputs[],after[]}]", takeOver = true))
        val (c, first) = parsed ?: template()
        charter = c
        val assigned = assign(first.map { it }, 1)
        tickets += assigned
        stagePlan = c.milestones.map { PlanStep(it, 600, StepStatus.TODO) } + PlanStep("최종 QA·보고서", 600, StepStatus.TODO)
        emit(AgentEvent.Plan(clock(), stagePlan, c.milestones.firstOrNull() ?: "", 0.05))
        StageBus.edit(ctx.jobId) { it.copy(note = c.title, cards = listOf(StageBus.Card("kickoff", "프로젝트 헌장", desk.center!!.name, desk.center!!.provider.name, StageBus.S.DONE, "파일 ${c.plan.size} · 루브릭 ${c.rubric.size} · 티켓 ${assigned.size}", clock(), clock())) + assigned.map { t -> card(t) }) }
        emit(AgentEvent.Collab(clock(), "킥오프", desk.center!!.name, "「${c.title}」 · 결과물 ${c.plan.size}개 파일 · 채점 기준: " + c.rubric.joinToString(", ") { it.name }))
        emit(AgentEvent.Collab(clock(), "배정", desk.center!!.name, "스프린트 1: " + assigned.joinToString(" · ") { "${it.who} → ${it.title}" }))
        checkpoint()
    }

    private fun kickoffPrompt() = buildString {
        appendLine("너는 이 AI 회사의 PM이다. 아래 목표로 ${minutes}분짜리 프로젝트를 시작한다. 팀은 실제 회사처럼 스프린트로 일하고, 모든 결과는 다른 회사 AI가 채점한다.")
        appendLine("목표: ${ctx.goal}")
        appendLine("현재: ${ctx.nowText}"); ctx.userName?.let { appendLine("의뢰인: $it") }
        if (ctx.memories.isNotEmpty()) appendLine("의뢰인에 대해 알려진 것: " + ctx.memories.take(5).joinToString(" / "))
        ctx.activeSkillGuide?.let { appendLine("작업 지침: $it") }
        appendLine(); appendLine("[팀원 — 현실적인 강점·약점]"); appendLine(AiProfiles.roster(desk.team.filter { it.key != desk.center?.key || desk.team.size < 3 }.map { it.name to it.provider }))
        appendLine()
        appendLine("헌장 규칙:")
        appendLine("- files: 실제로 남길 결과물 파일 3~8개. 큰 문서는 장(章)별 파일로 나눠라(예: 01_시장조사.md, 02_전략.md). 코드는 실제 경로(src/…).")
        appendLine("- rubric: 이 프로젝트만의 채점 기준 4~5개(이름과 '10점이면 어떤 상태인지'). 일반론 말고 구체적으로.")
        appendLine("- milestones: 3~4개.")
        appendLine("- backlog: 스프린트 1 티켓 ${w()}~${w() * 2}개. 티켓 하나 = 파일 하나. 기초(조사·구조·핵심 설계)부터.")
        appendLine("  inputs에는 참고할 다른 파일 경로, after에는 먼저 끝나야 할 티켓 id. brief는 담당자가 바로 일할 수 있게 분량·관점·형식까지.")
        appendLine("- 팀원에게 고르게 나눠라. who는 비워도 된다(배정 시스템이 강점·부하로 정한다).")
        if (Conductor.GITHUB.containsMatchIn(ctx.goal)) appendLine("- 목표에 GitHub 업로드가 있다: 티켓으로 만들지 말 것(완성 뒤 자동으로 올린다).")
        appendLine("JSON 하나만:")
        append("{\"title\":\"프로젝트 이름\",\"summary\":\"한 문단\",\"audience\":\"누가 쓰나\",\"rubric\":[{\"name\":\"\",\"desc\":\"\"}],\"files\":[{\"path\":\"\",\"purpose\":\"\"}],\"milestones\":[\"\"],\"backlog\":[{\"id\":\"T1\",\"title\":\"\",\"file\":\"\",\"brief\":\"\",\"inputs\":[],\"after\":[]}]}")
    }

    private fun parseCharter(raw: String): Pair<Charter, List<Ticket>>? {
        val o = JsonPick.obj(raw) ?: return null
        val rubric = o.optJSONArray("rubric")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { r -> Criterion(r.optString("name").ifBlank { return@mapNotNull null }.take(20), r.optString("desc").take(160)) } } }.orEmpty()
        val plan = o.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { f -> FilePlan(cleanPath(f.optString("path")).ifBlank { return@mapNotNull null }, f.optString("purpose").take(160)) } } }.orEmpty()
        if (plan.isEmpty()) return null
        val c = Charter(o.optString("title").ifBlank { ctx.goal.take(40) }, o.optString("summary"), o.optString("audience"),
            rubric.ifEmpty { defaultRubric() }.take(5), plan.take(10), JsonPick.strings(o.optJSONArray("milestones")).ifEmpty { listOf("기초", "본 작업", "다듬기") }.take(4))
        val first = parseTickets(o.optJSONArray("backlog"), 1, c).ifEmpty { c.plan.take(w() * 2).mapIndexed { i, f -> Ticket("T${i + 1}", f.purpose.take(30).ifBlank { f.path }, f.path, f.purpose, emptyList(), emptyList(), 1) } }
        return c to first
    }

    private fun parseTickets(a: JSONArray?, sp: Int, c: Charter): List<Ticket> {
        a ?: return emptyList()
        val used = tickets.map { it.id }.toMutableSet()
        val out = (0 until minOf(a.length(), w() * 2 + 1)).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val file = cleanPath(t.optString("file")).ifBlank { return@mapNotNull null }
            var id = t.optString("id").ifBlank { "S$sp-${i + 1}" }
            if (id in used) id = "S$sp-${i + 1}"
            used += id
            Ticket(id, t.optString("title").ifBlank { file }.take(40), file, t.optString("brief").ifBlank { t.optString("title") },
                JsonPick.strings(t.optJSONArray("inputs")).map { cleanPath(it) }, JsonPick.strings(t.optJSONArray("after")), sp)
        }
        // 같은 파일을 한 스프린트에서 둘이 쓰면 충돌 → 뒤 티켓이 앞 티켓을 기다리게
        val ids = out.map { it.id }.toSet()
        return out.mapIndexed { i, t ->
            val prevSame = out.take(i).lastOrNull { it.file == t.file }?.id
            Ticket(t.id, t.title, t.file, t.brief, t.inputs, (t.after.filter { it in ids && it != t.id } + listOfNotNull(prevSame)).distinct(), sp)
        }
    }

    private fun template(): Pair<Charter, List<Ticket>> {
        val g = ctx.goal
        val code = Regex("코드|앱|프로그램|스크립트|html|웹사이트|게임|봇").containsMatchIn(g)
        val plan = if (code) listOf(FilePlan("01_요구사항.md", "기능·화면·제약 정리"), FilePlan("02_설계.md", "구조·데이터·흐름"), FilePlan("src/main.py", "핵심 구현"), FilePlan("03_테스트.md", "검증 방법과 결과"), FilePlan("README.md", "사용법"))
            else listOf(FilePlan("01_리서치.md", "근거 자료(출처 포함)"), FilePlan("02_분석.md", "핵심 분석과 인사이트"), FilePlan("03_본문.md", "완성 결과물"), FilePlan("04_검토.md", "반론·리스크·보완"))
        val c = Charter(g.take(40), g, "의뢰인", defaultRubric(), plan, listOf("기초 조사", "본 작업", "다듬기"))
        val first = if (code) listOf(Ticket("T1", "요구사항 정리", plan[0].path, "목표를 기능·화면·제약으로 구체화: $g", emptyList(), emptyList(), 1),
            Ticket("T2", "구조 설계", plan[1].path, "요구사항을 바탕으로 구조·데이터·흐름 설계", listOf(plan[0].path), listOf("T1"), 1))
        else listOf(Ticket("T1", "자료 조사", plan[0].path, "웹에서 직접 검색해 최신 근거를 출처와 함께: $g", emptyList(), emptyList(), 1),
            Ticket("T2", "관점·반론 조사", plan[3].path, "같은 주제의 반대 근거·리스크·흔한 오해: $g", emptyList(), emptyList(), 1))
        emit(AgentEvent.Collab(clock(), "킥오프", "HJTK", "헌장을 읽지 못해 기본 구성으로 시작해요"))
        return c to assign(first, 1)
    }

    private fun defaultRubric() = listOf(Criterion("정확성", "사실·코드가 맞고 근거·출처가 있다"), Criterion("완성도", "요구를 빠짐없이, 바로 쓸 수 있게"),
        Criterion("깊이", "뻔한 말이 아니라 구체적 사례·수치·이유"), Criterion("독창성", "흔한 결과와 다른 관점·해법"), Criterion("명료성", "구조가 분명하고 읽기 쉽다"))

    // ───────────── 스프린트 ─────────────
    private fun assign(list: List<Ticket>, sp: Int): List<Ticket> {
        val tasks = list.map { Conductor.Task(it.id, it.title, it.who, prompt = it.brief + " " + it.file) }
        val done = Dispatch.assign(tasks, desk.team, desk.center, { m, s -> desk.crew.rate(m, s) }) { skillOf(it.title, it.prompt) }
        return list.mapIndexed { i, t -> t.also { it.who = done[i].who } }
    }

    private fun skillOf(title: String, rest: String): Skill =
        if (Regex("""\.(kt|java|py|js|ts|html|css|swift|dart|go|rs|c|cpp|sh)\b""").containsMatchIn(rest)) Skill.CODE else AiProfiles.skillFor("$title $rest")

    private suspend fun runSprint(all: List<Ticket>) {
        val list = all.distinctBy { it.id }
        if (list.isEmpty()) return
        phase("스프린트 $sprint", "${list.size}개 티켓 진행 중")
        val sem = Semaphore(w())
        coroutineScope {
            val jobs = HashMap<String, Deferred<Unit>>()
            for (t in list) jobs[t.id] = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                t.after.forEach { jobs[it]?.await() }
                sem.withPermit { desk.pauseGate(); pace(); ticket(t) }
            }
            // 지도(jobs)를 다 채운 뒤에 출발 — 채우는 도중 뒤 일이 앞 일을 못 찾고 먼저 달려가던 경쟁 상태 방지
            jobs.values.forEach { it.start() }
            jobs.values.forEach { it.await() }
        }
    }

    private suspend fun ticket(t: Ticket) {
        val t0 = clock()
        val skill = skillOf(t.title, t.brief + " " + t.file)
        var author = desk.member(t.who)?.takeIf { desk.crew.available(it) } ?: desk.bestFree(skill, avoidCenter = true)
        t.status = "doing"; t.who = author.name
        StageBus.card(ctx.jobId, t.id) { it.copy(who = author.name, provider = author.provider.name, status = StageBus.S.RUN, doing = "${verb(skill)} · ${t.file}", startedAt = clock()) }
        var best: String? = null; var bestScore = -1.0; var issues: List<String> = emptyList()
        val maxTry = if (left() < reserve * 2) 1 else 3
        for (attempt in 0 until maxTry) {
            val prompt = if (attempt == 0) writePrompt(t) else revisePrompt(t, best.orEmpty(), issues)
            val raw = desk.lane(author).withPermit { desk.ask(author, skill, t.id, prompt, minLen = 80) }
            if (raw.isBlank()) break
            StageBus.stages.value[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.who?.let { n -> desk.member(n)?.let { author = it; t.who = it.name } }
            val content = shape(desk.absorb(raw, author.name, loud = false), t.file)
            // 리뷰: 다른 회사 AI가 루브릭으로 채점
            t.status = "review"
            val rv = reviewerFor(author)
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.CHECK, doing = "${rv.name}가 채점하는 중", peer = "") }
            val (score, iss) = review(rv, t, content)
            t.reviewer = rv.name
            if (score > bestScore) { best = content; bestScore = score }
            val pass = score >= bar
            StageBus.card(ctx.jobId, t.id) { it.copy(peer = "${rv.name} 리뷰 ${fmt(score)} · " + if (pass) "통과" else "수정 ${iss.size}") }
            if (pass || attempt == maxTry - 1) break
            issues = iss; t.attempts++
            t.status = "doing"
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.RUN, doing = "리뷰 반영해 다시 쓰는 중 (${t.attempts})") }
        }
        val out = best
        times += clock() - t0
        if (out == null) {
            t.status = "failed"
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.FAIL, doing = "답을 받지 못함 · 스탠드업에서 다시 배정", endedAt = clock()) }
        } else {
            files.write(t.file, out)
            t.score = bestScore; t.issues = issues; t.status = "done"
            StageBus.card(ctx.jobId, t.id) { it.copy(status = StageBus.S.DONE, doing = "${t.file} · ${out.length}자 · ${fmt(bestScore)}점", endedAt = clock()) }
            emit(AgentEvent.Note(clock(), "${t.who} · ${t.title} → ${t.file} (${fmt(bestScore)}점, 리뷰 ${t.reviewer})"))
        }
        status()
        checkpoint()
    }

    private fun reviewerFor(author: CrewMember): CrewMember {
        val pool = desk.team.filter { it.provider != author.provider && desk.crew.available(it) }
        val nonCenter = pool.filter { it.key != desk.center?.key }.ifEmpty { pool }
        return nonCenter.maxByOrNull { Dispatch.bid(desk.crew.rate(it, Skill.REVIEW), desk.busyOf(it), it.lanes, false, desk.team.size, false) }
            ?: desk.center ?: author
    }

    /** 채점 → (평균 점수, 고칠 점). 형식을 못 읽으면 통과선으로 본다(흐름은 멈추지 않게) */
    private suspend fun review(rv: CrewMember, t: Ticket, content: String): Pair<Double, List<String>> {
        val r = desk.once(rv, reviewPrompt(t, content))
        if (r.text.isBlank() || AiProfiles.limitHit(r.text, r.via)) return bar to emptyList()
        desk.thank(rv)
        return parseReview(r.text) ?: (bar to emptyList())
    }

    fun parseReview(text: String): Pair<Double, List<String>>? {
        val o = JsonPick.obj(text)
        if (o != null) {
            val sc = o.optJSONObject("scores")
            val nums = sc?.keys()?.asSequence()?.mapNotNull { k -> sc.optDouble(k).takeIf { !it.isNaN() } }?.toList().orEmpty()
            val avg = if (nums.isNotEmpty()) nums.average() else o.optDouble("score").takeIf { !it.isNaN() } ?: return null
            return avg.coerceIn(0.0, 10.0) to (JsonPick.strings(o.optJSONArray("must")) + JsonPick.strings(o.optJSONArray("issues"))).distinct().take(6)
        }
        return Regex("""(\d+(?:\.\d)?)\s*/\s*10""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it.coerceIn(0.0, 10.0) to emptyList() }
    }

    // ───────────── 스탠드업·회고 ─────────────
    private suspend fun standup(): List<Ticket>? {
        val pm = desk.center!!
        desk.pauseGate()
        phase("스탠드업", "${pm.name}가 다음 스프린트를 짜는 중")
        val id = "standup${sprint + 1}"
        StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card(id, "스탠드업 · 스프린트 ${sprint + 1} 계획", pm.name, pm.provider.name, StageBus.S.CHECK, "현황·점수·칠판을 보는 중", clock(), round = sprint + 1)) }
        val raw = desk.askCenter(pm, standupPrompt(), takeOver = true)
        val o = JsonPick.obj(raw)
        val c = charter!!
        o?.optJSONArray("decisions")?.let { JsonPick.strings(it).take(3).forEach { d -> decisions += "S${sprint + 1} · $d" } }
        o?.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.forEach { f -> val p = cleanPath(f.optString("path")); if (p.isNotBlank() && c.plan.none { it.path == p }) charter = charter!!.copy(plan = charter!!.plan + FilePlan(p, f.optString("purpose"))) } }
        var next = parseTickets(o?.optJSONArray("tickets"), sprint + 1, charter!!)
        // 실패한 티켓은 다른 AI에게 다시
        val failed = tickets.filter { it.status == "failed" && it.sprint == sprint }
        next = next + failed.map { f -> Ticket("${f.id}r", "${f.title} (다시)", f.file, f.brief, f.inputs, emptyList(), sprint + 1).also { it.who = desk.team.filter { m -> m.name != f.who && m.key != pm.key }.maxByOrNull { m -> desk.crew.rate(m, skillOf(f.title, f.file)) }?.name ?: "" } }
        val done = o?.optBoolean("done", false) == true
        val q = quality()
        val summary = o?.optString("summary").orEmpty().ifBlank { "현황 점검" }
        StageBus.card(ctx.jobId, id) { it.copy(status = StageBus.S.DONE, doing = if (next.isEmpty()) "추가할 일 없음" else "티켓 ${next.size}개 · " + summary.take(40), endedAt = clock()) }
        emit(AgentEvent.Collab(clock(), "스탠드업", pm.name, "$summary · 남은 시간 ${mins(left())}분 · 품질 ${fmt(q)}"))
        // 일찍 끝내는 조건: 모든 계획 파일 + 품질 9.0 이상 + PM이 끝났다고 판단. 아니면 시간이 있는 한 계속 다듬는다
        val allFiles = charter!!.plan.all { files.read(it.path) != null }
        if (next.isEmpty() || (done && allFiles && q >= excellent)) return null
        // 미리 나눠 둔다: assign()이 담당자를 채우므로 나중에 다시 거르면 같은 티켓이 두 번 들어간다(→ 같은 id 두 개 → 영원히 기다림)
        val (open, preset) = next.partition { it.who.isBlank() }
        val assigned = (assign(open, sprint + 1) + preset).distinctBy { it.id }
        emit(AgentEvent.Collab(clock(), "배정", pm.name, "스프린트 ${sprint + 1}: " + assigned.joinToString(" · ") { "${it.who} → ${it.title}" }))
        return assigned
    }

    private fun standupPrompt() = buildString {
        val c = charter!!
        appendLine("[스탠드업] 너는 PM이다. 프로젝트 「${c.title}」 — 스프린트 $sprint 이 끝났다. 남은 시간 약 ${mins(left() - reserve)}분(마무리 시간 제외).")
        appendLine("목표: ${ctx.goal}")
        appendLine("채점 기준: " + c.rubric.joinToString(" / ") { "${it.name}(${it.desc})" })
        appendLine(); appendLine("[파일 현황]"); appendLine(fileStatus())
        if (qaIssues.isNotEmpty()) { appendLine("[최종 QA 지적 — 반드시 반영]"); qaIssues.forEach { appendLine("- $it") } }
        val d = desk.board.digest(1600); if (d.isNotBlank()) { appendLine("[팀 칠판]"); appendLine(d) }
        if (decisions.isNotEmpty()) { appendLine("[지금까지 결정]"); decisions.takeLast(6).forEach { appendLine("- $it") } }
        appendLine("[팀원] " + desk.team.filter { it.key != desk.center?.key }.joinToString(", ") { "${it.name}(완료 ${desk.creditOf(it)})" })
        appendLine()
        appendLine("다음 스프린트 티켓을 ${w()}~${w() * 2}개 짜라. 우선순위: ① 아직 없는 계획 파일 ② 점수 낮은 파일 개선(리뷰 지적 반영) ③ 깊이 더하기(사례·수치·반론·예시·테스트).")
        appendLine("개선 티켓은 같은 file로 — 담당자는 기존 내용을 바탕으로 더 나은 전체 판을 쓴다. 칠판의 '질문'에 답하는 티켓도 좋다.")
        appendLine("시간이 남았는데 품질이 9.0 미만이면 done=false로 계속 다듬어라. 모든 파일이 9.0 이상이고 더 할 가치가 없을 때만 done=true.")
        appendLine("새 파일이 꼭 필요하면 files에 추가. JSON 하나만:")
        append("{\"summary\":\"한 줄 현황\",\"done\":false,\"decisions\":[\"정한 것과 이유\"],\"files\":[{\"path\":\"\",\"purpose\":\"\"}],\"tickets\":[{\"id\":\"\",\"title\":\"\",\"file\":\"\",\"brief\":\"\",\"inputs\":[],\"after\":[]}]}")
    }

    private fun retro() {
        val mine = tickets.filter { it.sprint == sprint && it.status == "done" }
        val avg = if (mine.isEmpty()) 0.0 else mine.map { it.score }.average()
        history += sprint to avg
        val allSprint = tickets.count { it.sprint == sprint }
        emit(AgentEvent.Collab(clock(), "회고", desk.center!!.name, "스프린트 $sprint · 티켓 ${mine.size}/$allSprint 완료 · 평균 ${fmt(avg)}점 · 남은 시간 ${mins(left())}분"))
        stagePlan = stagePlan.mapIndexed { i, s -> if (i < (sprint * stagePlan.size / 4.0).toInt().coerceAtMost(stagePlan.size - 1)) s.copy(status = StepStatus.DONE) else s }
        emit(AgentEvent.Plan(clock(), stagePlan, "스프린트 ${sprint + 1}", prog))
        checkpoint()
    }

    // ───────────── 최종 QA · 보고 ─────────────
    private suspend fun finish(): LoopOutcome {
        val c = charter ?: return LoopOutcome(JobState.FAILED, "헌장을 만들지 못했어요", 0, "실패")
        if (files.list().isEmpty()) return LoopOutcome(JobState.FAILED, "팀원이 결과물을 만들지 못했어요. AI 연결을 확인하고 '이어서 진행'해 주세요.", tickets.size, "실패")
        var polished = false
        while (true) {
            phase("최종 QA", "다른 회사 AI 둘이 프로젝트 전체를 채점하는 중")
            finalQa()
            if (qa >= bar || polished || left() < reserve / 2 || sprint >= maxSprints) break
            // 미달 + 시간 있음 → 다듬기 스프린트 한 번
            polished = true
            val nextTickets = standup() ?: break
            sprint++
            nextTickets.forEach { tickets += it }
            StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + nextTickets.map { t -> card(t) }) }
            runSprint(nextTickets); retro()
        }
        phase("보고서", "프로젝트 보고서를 쓰는 중")
        val writer = desk.bestFree(Skill.WRITE, avoidCenter = true)
        StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card("report", "프로젝트 보고서", writer.name, writer.provider.name, StageBus.S.RUN, "결과물 안내·결정·품질표", clock())) }
        val body = desk.ask(writer, Skill.WRITE, "report", reportPrompt(), minLen = 80).let { desk.absorb(it, writer.name, loud = false) }
        val report = (body.ifBlank { "# ${c.title}\n\n${c.summary}" }).trim() + "\n\n" + workLog()
        files.write(REPORT, report)
        StageBus.card(ctx.jobId, "report") { it.copy(status = StageBus.S.DONE, doing = "$REPORT · ${report.length}자", endedAt = clock()) }
        var pushNote = ""
        if (Conductor.GITHUB.containsMatchIn(ctx.goal)) {
            phase("올리기", "GitHub에 올리는 중")
            val r = exec.run(CommandCall("push", "github.push", mapOf("repo" to (Conductor.repoFromGoal(ctx.goal) ?: slug(c.title)), "message" to "HJTK 프로젝트: ${c.title}")))
            pushNote = "\nGitHub: " + if (r.ok) r.text.lineSequence().first() else "올리지 못함 — ${r.text.take(120)}"
        }
        prog = 1.0
        val final = buildString {
            appendLine("# ${c.title}")
            appendLine(); appendLine(c.summary.ifBlank { ctx.goal })
            appendLine(); appendLine("결과물 (${files.list().size}개 파일)")
            files.list().sorted().forEach { p -> appendLine("- $p · ${files.read(p)?.length ?: 0}자" + (lastScore(p)?.let { " · ${fmt(it)}점" } ?: "")) }
            appendLine(); append("---\nPM ${desk.center?.name} · 스프린트 $sprint · 티켓 ${tickets.count { it.status == "done" }}/${tickets.size} · 최종 QA ${fmt(qa)}/10 · ${mins(elapsed())}분 · 일한 AI ${desk.credits()} · 토큰 약 ${Tokens.fmt(ledger.total())}")
            append(pushNote)
        }
        StageBus.edit(ctx.jobId) { it.copy(phase = "완료", progress = 1.0, quality = qa) }
        emit(AgentEvent.Done(clock(), final, "완료"))
        checkpoint()
        return LoopOutcome(JobState.COMPLETED, final, tickets.size, "완료")
    }

    private suspend fun finalQa() {
        val c = charter!!
        val center = desk.center
        val pool = desk.team.filter { desk.crew.available(it) }
        val picks = pool.filter { it.key != center?.key }.sortedByDescending { desk.crew.rate(it, Skill.REVIEW) }
            .fold(ArrayList<CrewMember>()) { acc, m -> if (acc.none { it.provider == m.provider } && acc.size < 2) acc += m; acc }
            .ifEmpty { listOfNotNull(center) }
        val scores = ArrayList<Double>(); val issues = ArrayList<String>()
        for ((i, rv) in picks.withIndex()) {
            val id = "qa$i"
            StageBus.edit(ctx.jobId) { it.copy(cards = it.cards + StageBus.Card(id, "최종 QA", rv.name, rv.provider.name, StageBus.S.CHECK, "전체를 루브릭으로 채점하는 중", clock())) }
            val r = desk.once(rv, qaPrompt())
            val parsed = parseReview(r.text)
            parsed?.let { scores += it.first; issues += it.second; desk.thank(rv) }
            StageBus.card(ctx.jobId, id) { it.copy(status = if (parsed == null) StageBus.S.FAIL else StageBus.S.DONE, doing = parsed?.let { p -> "${fmt(p.first)}점 · 지적 ${p.second.size}" } ?: "채점 실패", endedAt = clock()) }
        }
        qa = if (scores.isEmpty()) quality() else scores.average()
        qaIssues = issues.distinct().take(8)
        StageBus.edit(ctx.jobId) { it.copy(quality = qa) }
        emit(AgentEvent.Collab(clock(), "QA", picks.joinToString("·") { it.name }, "최종 QA ${fmt(qa)}/10" + if (qaIssues.isNotEmpty()) " · 지적: " + qaIssues.take(3).joinToString(" / ") else ""))
        if (c.rubric.isEmpty()) Unit
    }

    // ───────────── 프롬프트 ─────────────
    private fun charterBrief() = charter!!.let { c ->
        "프로젝트 「${c.title}」: ${c.summary}\n독자: ${c.audience}\n채점 기준(각 10점): " + c.rubric.joinToString(" / ") { "${it.name} — ${it.desc}" } +
            "\n파일 계획: " + c.plan.joinToString(", ") { "${it.path}(${it.purpose})" }
    }

    private fun writePrompt(t: Ticket) = buildString {
        appendLine("[회사 프로젝트 · 티켓 ${t.id}] PM ${desk.center?.name}가 너에게 배정했다. 이 결과는 다른 회사 AI가 아래 기준으로 채점한다(8점 미만이면 반려).")
        appendLine(charterBrief())
        appendLine(); appendLine("네 티켓: ${t.title}"); appendLine("파일: ${t.file}"); appendLine(t.brief)
        val cur = files.read(t.file)
        if (cur != null) { appendLine(); appendLine("이 파일의 현재 판(이걸 바탕으로 더 나은 전체 판을 써라. 좋은 부분은 살리고, 빈 곳을 채우고, 약한 곳을 고쳐라):"); appendLine(cur.take(chars / 2)) }
        val ins = inputs(t, if (cur != null) chars / 3 else chars * 2 / 3)
        if (ins.isNotBlank()) { appendLine(); appendLine("참고 파일:"); appendLine(ins) }
        val d = desk.board.digest(1400); if (d.isNotBlank()) { appendLine(); appendLine("[팀 칠판]"); appendLine(d) }
        appendLine()
        append(if (isCode(t.file)) "파일 내용 전체만 출력(코드 블록 하나, 생략 없이, 주석은 필요한 곳만)." else "파일에 들어갈 완성된 내용만 출력(마크다운, 제목부터). 인사·설명·되묻기 금지. 일반론 대신 구체적 사실·수치·예시·출처.")
        append("\n\n끝에 팀에 알릴 것이 있으면(없으면 생략):\n[칠판]\n사실: …\n결정: …\n질문: …")
    }

    private fun revisePrompt(t: Ticket, draft: String, issues: List<String>) = buildString {
        appendLine("[티켓 ${t.id} · 리뷰 반려] 리뷰어가 기준 미달로 돌려보냈다. 지적을 모두 반영한 전체 판을 다시 써라.")
        appendLine(charterBrief())
        appendLine("파일: ${t.file} · 티켓: ${t.title}")
        appendLine("리뷰 지적:"); issues.ifEmpty { listOf("기준 대비 깊이·구체성이 부족함") }.forEach { appendLine("- $it") }
        appendLine("이전 판:"); appendLine(draft.take(chars))
        append(if (isCode(t.file)) "고친 파일 전체만 출력(코드 블록 하나)." else "고친 완성본 전체만 출력.")
    }

    private fun reviewPrompt(t: Ticket, content: String) = buildString {
        appendLine("[코드 리뷰 방식 채점] 너는 이 프로젝트의 리뷰어다. 깐깐하게, 그러나 공정하게. 9~10점은 그대로 의뢰인에게 내놓을 수준일 때만.")
        appendLine(charterBrief())
        appendLine("티켓: ${t.title} · 파일: ${t.file} · 요구: ${t.brief}")
        appendLine(); appendLine("제출물:"); appendLine(content.take(chars))
        appendLine()
        append("JSON 하나만: {\"scores\":{" + charter!!.rubric.joinToString(",") { "\"${it.name}\":0" } + "},\"must\":[\"반드시 고칠 점(구체적으로, 어디를 어떻게)\"],\"issues\":[\"더 좋아질 점\"]}")
    }

    private fun qaPrompt() = buildString {
        appendLine("[최종 QA] 의뢰인에게 넘기기 직전이다. 프로젝트 전체를 채점해라. 파일 사이의 모순·빠진 요구·중복도 본다.")
        appendLine("목표: ${ctx.goal}"); appendLine(charterBrief())
        val ps = files.list().sorted()
        val each = (chars + 6000) / ps.size.coerceAtLeast(1)
        ps.forEach { p -> appendLine(); appendLine("=== $p ==="); appendLine(files.read(p).orEmpty().take(each)) }
        appendLine()
        append("JSON 하나만: {\"scores\":{" + charter!!.rubric.joinToString(",") { "\"${it.name}\":0" } + "},\"must\":[\"넘기기 전 꼭 고칠 점(파일 이름과 함께)\"],\"issues\":[]}")
    }

    private fun reportPrompt() = buildString {
        appendLine("[프로젝트 보고서] 의뢰인이 가장 먼저 읽을 문서(00_프로젝트_보고서.md)를 써라.")
        appendLine("목표: ${ctx.goal}"); appendLine(charterBrief())
        appendLine("[파일 현황]"); appendLine(fileStatus())
        appendLine("[각 파일 앞부분]"); files.list().sorted().filter { it != REPORT }.forEach { p -> appendLine("--- $p"); appendLine(files.read(p).orEmpty().take(900)) }
        if (decisions.isNotEmpty()) { appendLine("[결정 기록]"); decisions.forEach { appendLine("- $it") } }
        val d = desk.board.digest(1200); if (d.isNotBlank()) { appendLine("[팀 칠판]"); appendLine(d) }
        appendLine("[최종 QA] ${fmt(qa)}/10" + if (qaIssues.isNotEmpty()) " · 남은 지적: " + qaIssues.joinToString(" / ") else "")
        append("구성: 한 문단 요약 → 결과물 안내(파일별 무엇이 있고 어떻게 쓰는지) → 핵심 결정과 이유 → 품질 평가(기준별) → 남은 과제와 다음 단계. 사실만, 과장 금지. 제목부터 바로.")
    }

    private fun inputs(t: Ticket, budget: Int): String {
        val paths = (t.inputs + tickets.filter { it.id in t.after }.map { it.file }).distinct().filter { it != t.file }
        if (paths.isEmpty()) return ""
        val each = budget / paths.size
        return paths.mapNotNull { p -> files.read(p)?.let { "=== $p ===\n" + it.take(each) } }.joinToString("\n\n")
    }

    private fun fileStatus() = charter!!.plan.joinToString("\n") { f ->
        val txt = files.read(f.path)
        val last = tickets.lastOrNull { it.file == f.path && it.status == "done" }
        "- ${f.path} (${f.purpose}): " + if (txt == null) "아직 없음" else "${txt.length}자 · ${last?.let { "${fmt(it.score)}점(${it.who}, 리뷰 ${it.reviewer})" + if (it.issues.isNotEmpty()) " · 지적: " + it.issues.take(2).joinToString(" / ") else "" } ?: "점수 없음"}"
    }

    private fun workLog() = buildString {
        appendLine("---"); appendLine("## 작업 기록 (HJTK 자동 기록)")
        appendLine("| 항목 | 값 |"); appendLine("|---|---|")
        appendLine("| PM | ${desk.center?.name} |")
        appendLine("| 진행 시간 | ${mins(elapsed())}분 / 예산 ${minutes}분 |")
        appendLine("| 스프린트 | $sprint |")
        appendLine("| 티켓 | 완료 ${tickets.count { it.status == "done" }} · 반려 후 수정 ${tickets.sumOf { it.attempts }}회 · 실패 ${tickets.count { it.status == "failed" }} |")
        appendLine("| 최종 QA | ${fmt(qa)} / 10 |")
        appendLine("| 일한 AI(완료 건수) | ${desk.credits()} |")
        appendLine("| 토큰(추정) | ${Tokens.fmt(ledger.total())} |")
        if (history.isNotEmpty()) { appendLine(); appendLine("스프린트별 평균 점수: " + history.joinToString(" → ") { "S${it.first} ${fmt(it.second)}" }) }
        appendLine(); appendLine("| 티켓 | 파일 | 담당 | 리뷰 | 점수 |"); appendLine("|---|---|---|---|---|")
        tickets.filter { it.status == "done" }.forEach { appendLine("| ${it.id} ${it.title.replace("|", "/")} | ${it.file} | ${it.who} | ${it.reviewer} | ${fmt(it.score)} |") }
    }

    // ───────────── 상태·저장 ─────────────
    private fun quality(): Double {
        val per = charter?.plan?.mapNotNull { lastScore(it.path) }.orEmpty()
        return if (per.isEmpty()) 0.0 else per.average()
    }
    private fun lastScore(path: String) = tickets.lastOrNull { it.file == path && it.status == "done" }?.score

    private fun status() {
        prog = (0.03 + 0.92 * elapsed().toDouble() / budget).coerceIn(prog, 0.96)
        val fl = files.list().sorted().map { p -> "$p|${files.read(p)?.length ?: 0}|${lastScore(p)?.let { fmt(it) } ?: ""}" }
        StageBus.edit(ctx.jobId) { it.copy(progress = prog, sprint = sprint, deadline = clock() + left(), quality = quality(), files = fl, budgetMin = minutes) }
    }

    private fun phase(name: String, nxt: String) { next = nxt; status(); StageBus.edit(ctx.jobId) { it.copy(phase = name) } }

    private fun card(t: Ticket) = StageBus.Card(t.id, t.title, t.who.ifBlank { "배정 중" }, desk.member(t.who)?.provider?.name ?: "",
        when (t.status) { "done" -> StageBus.S.DONE; "failed" -> StageBus.S.FAIL; else -> StageBus.S.WAIT },
        if (t.status == "done") "${t.file} · ${fmt(t.score)}점" else if (t.after.isEmpty()) "곧 시작 · ${t.file}" else "앞 티켓 기다리는 중 · ${t.file}",
        after = t.after, round = t.sprint)

    // 동시에 끝난 티켓들이 저장을 겹쳐 하면 옛 상태가 새 상태를 덮을 수 있다 → 한 줄로 세워 늘 최신만 남긴다
    private val saveLock = Any()
    private fun checkpoint() = synchronized(saveLock) { runCatching { save(snapshot().toString()) } }

    fun snapshot(): JSONObject = JSONObject().apply {
        put("v", 1); put("minutes", minutes); put("sprint", sprint); put("elapsed", elapsed()); put("qa", qa)
        put("center", desk.center?.name ?: "")
        charter?.let { c -> put("charter", JSONObject().put("title", c.title).put("summary", c.summary).put("audience", c.audience)
            .put("rubric", JSONArray(c.rubric.map { JSONObject().put("name", it.name).put("desc", it.desc) }))
            .put("files", JSONArray(c.plan.map { JSONObject().put("path", it.path).put("purpose", it.purpose) }))
            .put("milestones", JSONArray(c.milestones))) }
        put("tickets", JSONArray(synchronized(tickets) { tickets.map { t -> JSONObject().put("id", t.id).put("title", t.title).put("file", t.file).put("brief", t.brief)
            .put("inputs", JSONArray(t.inputs)).put("after", JSONArray(t.after)).put("sprint", t.sprint).put("status", t.status).put("who", t.who)
            .put("score", t.score).put("attempts", t.attempts).put("reviewer", t.reviewer).put("issues", JSONArray(t.issues)) } }))
        put("decisions", JSONArray(decisions.toList()))
        put("history", JSONArray(history.map { JSONArray().put(it.first).put(it.second) }))
        put("board", JSONArray(desk.board.all().map { JSONObject().put("who", it.who).put("kind", it.kind).put("text", it.text) }))
    }

    private var restoredCenter: String? = null
    private fun restore(): Boolean {
        val o = runCatching { load()?.let { JSONObject(it) } }.getOrNull() ?: return false
        val c = o.optJSONObject("charter") ?: return false
        charter = Charter(c.optString("title"), c.optString("summary"), c.optString("audience"),
            c.optJSONArray("rubric")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { r -> Criterion(r.optString("name"), r.optString("desc")) } } }.orEmpty().ifEmpty { defaultRubric() },
            c.optJSONArray("files")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { f -> FilePlan(f.optString("path"), f.optString("purpose")) } } }.orEmpty(),
            JsonPick.strings(c.optJSONArray("milestones")))
        sprint = o.optInt("sprint"); elapsedBefore = o.optLong("elapsed"); qa = o.optDouble("qa", 0.0)
        restoredCenter = o.optString("center").ifBlank { null }
        o.optJSONArray("tickets")?.let { a -> for (i in 0 until a.length()) { val t = a.getJSONObject(i)
            tickets += Ticket(t.optString("id"), t.optString("title"), t.optString("file"), t.optString("brief"), JsonPick.strings(t.optJSONArray("inputs")), JsonPick.strings(t.optJSONArray("after")), t.optInt("sprint")).also { k ->
                k.status = t.optString("status").let { s -> if (s == "doing" || s == "review") "todo" else s }   // 하다 만 티켓은 처음부터
                k.who = t.optString("who"); k.score = t.optDouble("score", 0.0); k.attempts = t.optInt("attempts"); k.reviewer = t.optString("reviewer"); k.issues = JsonPick.strings(t.optJSONArray("issues"))
            } } }
        JsonPick.strings(o.optJSONArray("decisions")).forEach { decisions += it }
        o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { p -> history += p.optInt(0) to p.optDouble(1) } }
        o.optJSONArray("board")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { n -> desk.board.add(n.optString("who"), n.optString("kind"), n.optString("text")) } }
        stagePlan = charter!!.milestones.map { PlanStep(it, 600, StepStatus.TODO) } + PlanStep("최종 QA·보고서", 600, StepStatus.TODO)
        return true
    }

    // ───────────── 작은 도구 ─────────────
    private fun shape(text: String, path: String): String {
        if (!isCode(path)) return text.trim()
        val blocks = Regex("""```[\w+#.-]*[^\n]*\n([\s\S]*?)```""").findAll(text).map { it.groupValues[1] }.toList()
        return (blocks.maxByOrNull { it.length } ?: text).trimEnd() + "\n"
    }
    private fun isCode(p: String) = Regex("""\.(kt|java|py|js|ts|tsx|jsx|html|css|swift|dart|go|rs|c|cpp|h|sh|json|yml|yaml|xml|gradle|toml|sql)$""").containsMatchIn(p)
    private fun verb(s: Skill) = when (s) { Skill.RESEARCH -> "조사하는 중"; Skill.CODE -> "코드 쓰는 중"; Skill.REVIEW -> "검토하는 중"; Skill.PLAN -> "설계하는 중"; Skill.CREATIVE -> "발상하는 중"; Skill.REASON -> "분석하는 중"; else -> "쓰는 중" }
    private fun mins(ms: Long) = (ms.coerceAtLeast(0) / 60_000).toInt()
    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "hjtk-project-" + ctx.jobId.take(6).lowercase() }

    companion object {
        const val REPORT = "00_프로젝트_보고서.md"
        /** 파일 경로 정리: 하위 폴더는 허용, 상위(..)·절대경로·이상한 글자는 막는다 */
        fun cleanPath(p: String): String = p.trim().replace('\\', '/').trimStart('/').split('/').filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/") { it.replace(Regex("""[:*?"<>|]"""), "_") }.take(120)
    }
}
