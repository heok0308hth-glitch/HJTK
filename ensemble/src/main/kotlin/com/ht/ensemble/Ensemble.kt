package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.agent.*
import com.ht.ensemble.ai.AiProfiles
import com.ht.ensemble.ai.AiProfiles.Skill
import com.ht.ensemble.ai.Difficulty
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.ai.TokenLedger
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandResult
import com.ht.ensemble.command.CommandSpec
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * HJTK Ensemble — 여러 AI를 하나의 팀으로 일하게 하는 Kotlin SDK.
 *
 * ```kotlin
 * val team = Ensemble.team {
 *     member(Provider.CLAUDE, Ports.anthropic(System.getenv("ANTHROPIC_API_KEY")))
 *     member(Provider.GPT, Ports.openAi(System.getenv("OPENAI_API_KEY")))
 *     member(Provider.DEEPSEEK, Ports.deepSeek(System.getenv("DEEPSEEK_API_KEY")), lanes = 2)
 *     onEvent { println(it) }
 * }
 * val quick = team.conduct("2026 디자인 공모전 비교표 만들어줘")              // 몇 분: 센터가 나누고 동시에 진행
 * val big = team.project("카페 창업 사업계획서", minutes = 60, dir = File("out")) // 1시간: PM·스프린트·리뷰·QA
 * ```
 * 안드로이드 의존이 없다. 서버·데스크톱·안드로이드 어디서나 같은 코드로 돈다.
 */
class Ensemble private constructor(
    private val members: List<CrewMember>,
    private val ports: Map<String, List<LlmPort>>,
    private val tools: ToolPort,
    private val approver: suspend (ApprovalRequest) -> Boolean,
    private val asker: suspend (String, List<String>) -> String?,
    private val events: (AgentEvent) -> Unit,
    private val user: String?,
    private val memories: List<String>,
    private val guide: String?,
    private val toolCatalog: String,
) {
    /** 결과: 상태 · 최종 답(요약) · 프로젝트 파일 · 토큰 */
    data class Result(val state: JobState, val text: String, val files: ProjectFiles, val tokens: Int, val jobId: String) { val ok get() = state == JobState.COMPLETED }

    /** UI에 그릴 실시간 무대(누가 · 무엇을 · 진행률 · 칠판) */
    val stage: StateFlow<Map<String, StageBus.Stage>> get() = StageBus.stages

    private val gate = ApprovalGate()
    val score = Score()

    /** 몇 분짜리 일: 센터가 나누고 → 배정 시장 → 동시 실행·칠판·교차 확인 → 통합·검수 */
    suspend fun conduct(goal: String, difficulty: Difficulty = Difficulty.STANDARD, files: ProjectFiles = MemoryFiles(), control: JobControl = JobControl(), jobId: String = newId()): Result {
        val ledger = TokenLedger()
        val out = Conductor(context(jobId, goal), crew(), tools, human(), gate, control, events, difficulty = difficulty, ledger = ledger, catalog = catalog(), files = files).run()
        return Result(out.state, out.final, files, ledger.total(), jobId)
    }

    /**
     * 30분~3시간짜리 프로젝트: PM 헌장 → 스프린트(작성 → 다른 회사 AI 채점 → 반려·수정) → 스탠드업 → 최종 QA → 보고서.
     * [dir]를 주면 파일이 그 폴더에 쌓이고 진행 상황이 `.company.json`에 저장돼, 같은 [jobId]로 다시 부르면 이어서 한다.
     */
    suspend fun project(goal: String, minutes: Int = 60, dir: File? = null, control: JobControl = JobControl(), jobId: String = newId(),
                        width: () -> Int = { 3 }, pace: suspend () -> Unit = {}, deliver: suspend (Company.Delivery) -> List<String> = { emptyList() },
                        clock: () -> Long = System::currentTimeMillis): Result {
        val files: ProjectFiles = dir?.let { DirFiles(it) } ?: MemoryFiles()
        val state = dir?.let { File(it, ".company.json") }
        val ledger = TokenLedger()
        val out = Company(context(jobId, goal), crew(), tools, human(), gate, control, events, clock = clock, minutes = minutes, ledger = ledger, catalog = catalog(), files = files,
            save = { s -> state?.writeText(s) }, load = { state?.takeIf { it.exists() }?.readText() }, pace = pace, width = width, deliver = deliver).run()
        return Result(out.state, out.final, files, ledger.total(), jobId)
    }

    private fun context(jobId: String, goal: String) = AgentContext(jobId, goal, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date()), memories, userName = user, activeSkillGuide = guide)
    private fun catalog() = toolCatalog
    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(11).uppercase()

    private fun crew() = object : Crew {
        override fun members() = members
        override fun port(m: CrewMember, lane: Int): LlmPort = ports.getValue(m.key).let { it[lane % it.size] }
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) = score.record(m.key, ok, ms, text, via)
        override fun available(m: CrewMember) = !score.resting(m.key)
        override fun rate(m: CrewMember, s: Skill) = score.adjust(AiProfiles.score(m.provider, s).toDouble(), m.key)
    }

    private fun human() = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest): ApprovalAnswer =
            if (approver(req)) ApprovalAnswer.Approved(gate.issue(req.digest, System.currentTimeMillis())) else ApprovalAnswer.Rejected("승인하지 않음")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = asker(question, choices)
        override suspend fun takeover(jobId: String, what: String, url: String?): String? = null
    }

    /**
     * 메모리 성적표: 성공률·속도로 배정 점수를 조정하고, '사용량 한도'나 로그인 실패면 잠시 쉬게 한다.
     * 안드로이드 앱은 같은 규칙을 영구 저장판으로 쓴다.
     */
    class Score(private val restMs: Long = 15 * 60_000L) {
        private class S(var ok: Int = 0, var fail: Int = 0, var avgMs: Double = 0.0, var restUntil: Long = 0)
        private val m = ConcurrentHashMap<String, S>()
        fun record(key: String, ok: Boolean, ms: Long, text: String, via: String) {
            val s = m.getOrPut(key) { S() }
            synchronized(s) {
                if (ok) s.ok++ else s.fail++
                if (ms > 0) s.avgMs = if (s.avgMs == 0.0) ms.toDouble() else s.avgMs * 0.7 + ms * 0.3
                if (!ok && (AiProfiles.limitHit(text, via) || listOf("LOGIN", "CHALLENGE", "HTTP401", "HTTP403").any { via.contains(it) })) s.restUntil = System.currentTimeMillis() + restMs
            }
        }
        fun resting(key: String) = (m[key]?.restUntil ?: 0) > System.currentTimeMillis()
        fun adjust(base: Double, key: String): Double {
            val s = m[key] ?: return base
            val n = s.ok + s.fail
            val rate = if (n == 0) 1.0 else s.ok.toDouble() / n
            return base * (0.6 + 0.4 * rate) - (if (s.avgMs > 180_000) 1.0 else 0.0)
        }
    }

    class Builder internal constructor() {
        private val members = ArrayList<CrewMember>()
        private val ports = LinkedHashMap<String, MutableList<LlmPort>>()
        private var tools: ToolPort = NoTools
        private var catalog = ""
        private var approver: suspend (ApprovalRequest) -> Boolean = { false }
        private var asker: suspend (String, List<String>) -> String? = { _, c -> c.firstOrNull() }
        private var events: (AgentEvent) -> Unit = {}
        private var user: String? = null; private var memories: List<String> = emptyList(); private var guide: String? = null

        /** 팀원 추가. [lanes]>1이면 같은 AI에 동시에 그만큼 보낸다(같은 포트를 공유하거나 [extra] 포트를 돌려 쓴다) */
        fun member(provider: Provider, port: LlmPort, name: String = provider.label, lanes: Int = 1, vararg extra: LlmPort) {
            val key = "${provider.name}:${members.count { it.provider == provider }}"
            members += CrewMember(key, name, provider, lanes.coerceAtLeast(1))
            ports[key] = (listOf(port) + extra).toMutableList()
        }
        /** 도구(웹 검색·파일·메일…)와 AI에게 보여 줄 도구 목록(한 줄에 하나: `web.search(q) 웹 검색`). 없으면 AI가 스스로 조사한다 */
        fun tools(port: ToolPort, catalog: String = "") { tools = port; this.catalog = catalog }
        /** 밖으로 나가는 일(Effect.EXTERNAL·DESTRUCTIVE)의 승인. 기본은 모두 거절(안전) */
        fun approve(block: suspend (ApprovalRequest) -> Boolean) { approver = block }
        fun ask(block: suspend (question: String, choices: List<String>) -> String?) { asker = block }
        fun onEvent(block: (AgentEvent) -> Unit) { events = block }
        fun client(name: String? = null, memories: List<String> = emptyList(), guide: String? = null) { user = name; this.memories = memories; this.guide = guide }
        internal fun build(): Ensemble {
            require(members.isNotEmpty()) { "팀원이 한 명은 있어야 해요: member(Provider.X, port)" }
            return Ensemble(members.toList(), ports.mapValues { it.value.toList() }, tools, approver, asker, events, user, memories, guide, catalog)
        }
    }

    /** 도구 없음: 도구가 필요한 일은 AI가 직접 조사하도록 넘어간다 */
    object NoTools : ToolPort {
        override fun spec(id: String): CommandSpec? = null
        override suspend fun run(call: CommandCall) = CommandResult.fail("도구 없음")
    }

    companion object {
        fun team(block: Builder.() -> Unit): Ensemble = Builder().apply(block).build()
    }
}
