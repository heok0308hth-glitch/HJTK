package com.ht.hjtk.agent

import kotlinx.coroutines.flow.drop

import android.content.Intent
import androidx.core.content.ContextCompat
import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.Journal
import com.ht.hjtk.core.MemoryIndex
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.PipelineCompiler
import com.ht.hjtk.core.Skill
import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.aep.JobId
import com.ht.hjtk.core.agent.*
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.data.JobRec
import com.ht.hjtk.data.Role
import com.ht.hjtk.engine.CrashLog
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.LiveBus
import com.ht.hjtk.core.ai.Role as AiRole
import com.ht.hjtk.engine.Notifier
import com.ht.hjtk.engine.openCustomTab
import com.ht.hjtk.service.AgentService
import com.ht.hjtk.tools.AndroidTools
import com.ht.hjtk.tools.TapBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 펫(오버레이)에게 보내는 신호 */

/**
 * 안드로이드 쪽 오케스트레이터. 작업을 띄우고, 사건을 대화·알림·펫으로 번역하고, 사람의 손을 기다린다.
 */
private const val COMPANY_STATE = ".company.json"

class JobRunner(private val h: Hjtk) {
    private val store = h.store
    // 어떤 코루틴의 예외도 앱을 죽이지 않는다(기록만 남기고 대화로 알린다)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashLog.handler("runner"))
    private val llmLock = Mutex()
    val gate = ApprovalGate()

    inner class Live(val jobId: String, val convId: String, val control: JobControl, val budget: TapBudget) {
        val events = ArrayList<AgentEvent>()
        var loop: MissionEngine? = null
        var task: Job? = null
        val composer = ReportComposer(store.settings.value.reportMinutes * 60_000L)
        var startedAt = System.currentTimeMillis()
        var state = JobState.RUNNING
        var lastSay = ""
        lateinit var journal: Journal
    }

    data class Pending(val id: String, val jobId: String, val convId: String, val type: String, val msgId: String, val deferred: CompletableDeferred<String?>, val approval: ApprovalRequest? = null)

    val live = ConcurrentHashMap<String, Live>()
    val liveCount = MutableStateFlow(0)
    val pending = MutableStateFlow<Map<String, Pending>>(emptyMap())
    val screenWaiting = MutableStateFlow<String?>(null)
    val busyQuick = MutableStateFlow<Set<String>>(emptySet())
    /** 지금 DeepSeek 답이 실시간으로 흘러드는 대화(빠른 대화만). 작업 답(봉투)은 화면에 흘리지 않는다 */
    val streamingConv = MutableStateFlow<String?>(null)
    private var screenDeferred: CompletableDeferred<String?>? = null
    private var pickDeferred: CompletableDeferred<String?>? = null

    init {
        // 프로세스가 죽었다 살아났다: 실행 중으로 남은 작업은 '중단됨'으로 표시하고 재개를 제안한다(재실행 금지)
        store.jobs.value.filter { it.running }.forEach { j -> store.updateJob(j.id) { it.copy(state = "SUSPENDED", label = "앱이 종료되어 멈춤 · 재개 가능") } }
        scope.launch { reportTicker() }
    }

    // ── LLM 선택 ────────────────────────────────────────────────
    private fun port(): LlmPort {
        val s = store.settings.value
        return when {
            s.dsMode == "api" && h.deepseekApi.available() -> h.deepseekApi
            else -> h.deepseek
        }
    }

    /** 작업마다 DeepSeek 대화방이 따로 있다. 보내기 전에 그 방으로 돌아간다. */
    /** 검토 AI가 DeepSeek 두 번째 창일 때 그 대화방 주소(작업별) */
    private val criticSessions = ConcurrentHashMap<String, String>()

    private inner class JobLlm(private val jobId: String, private val base: LlmPort, private val critic: Boolean = false) : LlmPort {
        override val name = if (critic) "${base.name} 검토창" else base.name
        private fun session(): String? = if (critic) criticSessions[jobId] else store.job(jobId)?.dsSession
        private fun remember(u: String) { if (critic) criticSessions[jobId] = u else store.updateJob(jobId) { it.copy(dsSession = u) } }
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
            var r = once(text, jobId, step, newSession)
            // 로그인/확인 필요: 조종석에서 끝내면 같은 단계를 자동으로 이어 보낸다(최대 10분). 사람에게 '붙여넣기'를 요구하지 않는다.
            if (base is DeepSeekWeb && (r.via == "NEEDS_LOGIN" || r.via == "CHALLENGE")) {
                val j = store.job(this.jobId)
                val m = j?.let { store.msg(it.convId, Role.SYSTEM, explain(r.via) + " 로그인을 마치면 작업이 자동으로 이어집니다.", "error", mapOf("job" to this.jobId, "via" to r.via, "waiting" to "1")) }
                store.updateJob(this.jobId) { it.copy(state = "PAUSED_INPUT", label = "DeepSeek 로그인 기다리는 중") }
                AgentService.update(h.app)
                val ok = withTimeoutOrNull(10 * 60_000L) {
                    while (base.status.value != DeepSeekWeb.Status.READY) { base.refreshState(); delay(1_500) }
                    true
                } ?: false
                if (j != null && m != null) store.updateMsg(j.convId, m.id) { it.copy(meta = it.meta + ("waiting" to "0")) }
                store.updateJob(this.jobId) { it.copy(state = "RUNNING", label = if (ok) "로그인 확인 · 재개" else "로그인 대기 시간 초과") }
                if (ok) r = once(text, jobId, step, newSession)
                if (r.text.isBlank()) whyEmpty(this.jobId, r.via)
                return r
            }
            if (r.text.isBlank()) whyEmpty(this.jobId, r.via)
            return r
        }

        private suspend fun once(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = llmLock.withLock {
            val fresh = newSession || (base is DeepSeekWeb && session() == null)
            if (base is DeepSeekWeb && !fresh) base.ensureSession(session())
            if (!critic) store.updateJob(this.jobId) { it.copy(lastPrompt = text.take(20_000)) }
            val r = if (base is DeepSeekWeb) mirror(base, this.jobId, name) { base.send(text, jobId, step, fresh) } else base.send(text, jobId, step, fresh)
            // currentUrl()은 메인 스레드에서 기록한 사본이라 어느 스레드에서 읽어도 안전하다
            if (base is DeepSeekWeb) base.currentUrl()?.takeIf { it.contains("/chat/") || it.contains("/a/") }?.let { remember(it) }
            store.updateJob(this.jobId) { it.copy(via = r.via) }
            r
        }
    }

    private fun whyEmpty(jobId: String, via: String) {
        val j = store.job(jobId) ?: return
        store.msg(j.convId, Role.SYSTEM, explain(via), "error", mapOf("job" to jobId, "via" to via))
    }

    /** 실패 경로 코드 → 사람이 할 수 있는 다음 행동 한 문장 */
    fun explain(via: String): String = when {
        via == "NEEDS_LOGIN" -> "DeepSeek 로그인이 필요해요. '조종석'을 눌러 한 번만 로그인해 주세요."
        via == "CHALLENGE" -> "DeepSeek이 '사람인지' 확인을 요청했어요. 조종석에서 한 번만 눌러 주세요. 이후에는 앱이 기억합니다."
        via == "OFFLINE" -> "DeepSeek에 연결할 수 없어요. 인터넷 연결을 확인한 뒤 다시 보내 주세요."
        via == "NOT_READY" -> "DeepSeek 화면이 아직 열리지 않았어요. 조종석에서 화면이 뜨는지 확인해 주세요."
        via == DeepSeekWeb.VIA_GONE -> "휴대폰 메모리가 부족해 DeepSeek 화면이 닫혔어요. 다시 열어 두었으니 한 번 더 보내 주세요."
        via.startsWith("INPUT_") -> "DeepSeek 입력창을 찾지 못했어요(${via.removePrefix("INPUT_")}). 조종석에서 화면을 확인해 주세요."
        via == "TIMEOUT" -> "답을 기다리다 시간이 지났어요. 답을 복사해 붙여넣거나 'HJTK로 공유' 해 주시면 이어갑니다."
        via == "EXCEPTION" -> "보내는 중 문제가 생겼어요. 다시 보내 주세요."
        else -> "답을 읽지 못했어요($via). 조종석에서 답이 보이면 복사해 붙여넣거나 'HJTK로 공유' 해 주세요."
    }

    // ── 작업 시작/재개 ──────────────────────────────────────────
    fun startMission(convId: String, goal: String, skillName: String? = null, difficulty: com.ht.hjtk.core.ai.Difficulty? = null, minutes: Int = 0): JobRec {
        val id = JobId.new()
        val now = System.currentTimeMillis()
        val d = difficulty ?: h.ai.state.value.difficulty
        val job = JobRec(id, convId, goal, "RUNNING", now, now, label = "시작", diff = d.name,
            minutes = if (d == com.ht.hjtk.core.ai.Difficulty.PROJECT) (minutes.takeIf { it > 0 } ?: store.settings.value.projectMinutes) else 0)
        store.putJob(job)
        launchLoop(job, resumeFrom = null, skillName = skillName)
        return job
    }

    fun resume(jobId: String) {
        val j = store.job(jobId) ?: return
        if (live.containsKey(jobId)) { live[jobId]?.control?.resume(); return }
        val plan = j.plan
        val prompt = AgentProtocol.observation(jobId, 99, emptyList(),
            "앱이 다시 시작되었다(중단 후 재개). 직전 계획: $plan / 다음 할 일: ${j.next}. 이미 끝난 일은 반복하지 말고 이어서 진행하라. 밖으로 나가는 행동(메일 등)은 이미 했는지 먼저 확인하라.")
        store.updateJob(jobId) { it.copy(state = "RUNNING", label = "재개") }
        store.msg(j.convId, Role.SYSTEM, "작업을 이어서 진행합니다.", "note", mapOf("job" to jobId))
        launchLoop(store.job(jobId)!!, resumeFrom = prompt, skillName = null)
    }

    /** Gemini를 검토자로: API 키가 있으면 바로 */
    private inner class GeminiApiPort : LlmPort {
        override val name = "Gemini"
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
            val t0 = System.currentTimeMillis()
            val a = runCatching { h.gemini.ask(text) }.getOrNull()
            return LlmReply(a.orEmpty(), if (a.isNullOrBlank()) "GEMINI_FAIL" else "GEMINI_API", !a.isNullOrBlank(), System.currentTimeMillis() - t0)
        }
    }

    /** Gemini 앱(키 없음): 작업당 최대 2번만 사람을 부른다. 다 쓰면 null → 공정이 DeepSeek 자체 검토로 넘긴다 */
    private inner class GeminiRelayPort(private val budget: TapBudget) : LlmPort {
        override val name = "Gemini 앱"
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
            if (budget.left <= 0) return LlmReply("", "BUDGET", false, 0)
            budget.left--
            val s = store.settings.value
            return h.bridge.ask(ExternalAiBridge.GEMINI, text, useWeb = s.geminiMode == "web", waitMs = s.geminiWaitMin * 60_000L, jobTag = jobId)
        }
    }

    private fun criticFor(jobId: String, budget: TapBudget): LlmPort? = when (store.settings.value.collab) {
        "off" -> null
        "gemini", "gemini_app" -> if (h.gemini.available()) GeminiApiPort() else JobLlm(jobId, h.deepseekChat, critic = true)
        "deepseek2" -> JobLlm(jobId, port(), critic = true)
        else -> if (h.gemini.available()) GeminiApiPort() else JobLlm(jobId, port(), critic = true)
    }

    /** 회사 모드 프로젝트 폴더: 하위 폴더(src/…) 허용, 숨김 파일(체크포인트)은 목록에서 뺀다 */
    private class DirFiles(private val root: java.io.File) : com.ht.hjtk.core.agent.ProjectFiles {
        private fun f(p: String) = java.io.File(root, com.ht.hjtk.core.agent.Company.cleanPath(p))
        override fun write(path: String, text: String) { f(path).also { it.parentFile?.mkdirs() }.writeText(text) }
        override fun read(path: String): String? = f(path).takeIf { it.isFile }?.readText()
        override fun list(): List<String> = root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "foundry.json" && !it.relativeTo(root).path.startsWith("dist") }
            .map { it.relativeTo(root).path.replace('\\', '/') }.toList()
    }

    private val deliverer by lazy { com.ht.hjtk.engine.Deliverer(h.app, store) }
    private fun kindLabel(k: String) = when (k) { "apk" -> "설치 파일(APK)"; "preview" -> "미리보기"; "html" -> "완성본(HTML)"; "zip" -> "프로젝트 ZIP"; else -> k }

    // 1~3시간 프로젝트: 화면이 꺼져도 CPU가 잠들지 않게(예산 + 30분 뒤 자동 해제)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, android.os.PowerManager.WakeLock>()
    private fun wake(jobId: String, minutes: Int) = runCatching {
        val pm = h.app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        locks[jobId] = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hjtk:project").apply { setReferenceCounted(false); acquire((minutes + 30) * 60_000L) }
    }
    private fun unwake(jobId: String) { locks.remove(jobId)?.let { runCatching { if (it.isHeld) it.release() } } }

    private fun checkpointFile(jobId: String) = java.io.File(store.jobDir(jobId), "foundry.json")

    /**
     * 지휘자에게 줄 팀: 회사마다 한 명(키가 있으면 API, 없으면 로그인한 웹). DeepSeek은 창 두 개(동시 2건).
     * 성적은 Scoreboard(실제 성공률·속도·한도)로, 능력은 AiProfiles(현실 장단점)로.
     */
    private fun crewFor(jobId: String, diff: com.ht.hjtk.core.ai.Difficulty): com.ht.hjtk.core.agent.Crew = object : com.ht.hjtk.core.agent.Crew {
        private val ports = java.util.concurrent.ConcurrentHashMap<String, LlmPort>()
        private fun all(): List<Pair<com.ht.hjtk.core.agent.CrewMember, com.ht.hjtk.core.ai.Member>> =
            h.ai.members().filter { it.transport != com.ht.hjtk.core.ai.Transport.APP }
                .groupBy { it.provider }.mapNotNull { (_, ms) -> ms.firstOrNull { it.transport == com.ht.hjtk.core.ai.Transport.API } ?: ms.firstOrNull() }
                .map { m -> com.ht.hjtk.core.agent.CrewMember(m.key, m.provider.label, m.provider, if (m.provider == com.ht.hjtk.core.ai.Provider.DEEPSEEK && m.transport == com.ht.hjtk.core.ai.Transport.WEB) 2 else 1,
                    m.transport != com.ht.hjtk.core.ai.Transport.API) to m }
        override fun members() = all().map { it.first }
        override fun port(m: com.ht.hjtk.core.agent.CrewMember, lane: Int): LlmPort = ports.getOrPut("${m.key}#$lane") {
            val mem = all().first { it.first.key == m.key }.second
            when (mem.transport) {
                com.ht.hjtk.core.ai.Transport.API -> {
                    val model = com.ht.hjtk.core.ai.ModelRouter.modelFor(mem, diff.tierFor(com.ht.hjtk.core.ai.Role.WRITE))
                    h.ai.apiPort(com.ht.hjtk.core.ai.Assignment(com.ht.hjtk.core.ai.Role.WRITE, mem, model, "센터 배정"), "$jobId:${m.name}:$lane", stateless = true) ?: JobLlm(jobId, port())
                }
                else -> if (mem.provider == com.ht.hjtk.core.ai.Provider.DEEPSEEK) (if (lane == 0) JobLlm(jobId, h.deepseek) else JobLlm(jobId, h.deepseekChat, critic = true))
                    else com.ht.hjtk.engine.WebAiHub.port(mem.provider) ?: JobLlm(jobId, port())
            }
        }
        override fun report(m: com.ht.hjtk.core.agent.CrewMember, ok: Boolean, ms: Long, text: String, via: String) { com.ht.hjtk.engine.Scoreboard.record(m.key, ok, ms, text, via) }
        override fun available(m: com.ht.hjtk.core.agent.CrewMember) = !com.ht.hjtk.engine.Scoreboard.resting(m.key)
        override fun rate(m: com.ht.hjtk.core.agent.CrewMember, s: com.ht.hjtk.core.ai.AiProfiles.Skill) = com.ht.hjtk.engine.Scoreboard.adjust(com.ht.hjtk.core.ai.AiProfiles.score(m.provider, s), m.key)
    }

    private fun launchLoop(job: JobRec, resumeFrom: String?, skillName: String?) {
        val l = Live(job.id, job.convId, JobControl(), TapBudget(2))
        l.journal = Journal { line -> runCatching { store.journalFile(job.id).appendText(line + "\n") } }
        live[job.id] = l; liveCount.value = live.size
        l.composer.reset(System.currentTimeMillis())
        ensureService()
        val s = store.settings.value
        val skills = Skill.BUILT_IN + store.skills.value
        val active = skillName?.let { n -> skills.firstOrNull { it.name == n } } ?: skills.firstOrNull { sk -> sk.builtIn && job.goal.contains(sk.name.take(3)) }
        val ctx = AgentContext(
            jobId = job.id, goal = job.goal,
            nowText = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm z", java.util.Locale.KOREA)),
            memories = MemoryIndex.search(store.memories.value, job.goal, 8).map { it.first.text }.ifEmpty { store.memories.value.takeLast(5).map { it.text } },
            hubSites = store.sites.value.map { "${it.name}(${it.url})" }, hubApps = store.apps.value.map { it.label },
            skills = skills.map { it.name }, activeSkillGuide = active?.guide, userName = s.userName.ifBlank { null } ?.let { "$it · 사는 곳 ${s.homeCity}" }
        )
        val tools = AndroidTools(h, job.id, job.convId, human, l.budget)
        // 새 작업은 '작업 공정'으로. 1.1 이전 형식으로 멈춘 작업만 옛 루프로 이어 간다.
        val cp = checkpointFile(job.id)
        val diff = com.ht.hjtk.core.ai.Difficulty.of(job.diff)
        val project = diff == com.ht.hjtk.core.ai.Difficulty.PROJECT && job.minutes > 0
        val legacy = resumeFrom != null && !cp.exists() && !project
        // 팀 구성: 역할마다 가장 알맞은 AI·모델(난이도에 따라 등급), 검토자는 작성자와 다른 회사
        val team = h.ai.team(diff)
        fun portOf(a: com.ht.hjtk.core.ai.Assignment?, role: com.ht.hjtk.core.ai.Role): LlmPort? = when (a?.member?.transport) {
            null -> null
            com.ht.hjtk.core.ai.Transport.API -> h.ai.apiPort(a, job.id + ":" + role.name, stateless = h.ai.state.value.saveTokens)
            com.ht.hjtk.core.ai.Transport.APP -> h.ai.appPort(a, l.budget, s.geminiWaitMin * 60_000L)
            com.ht.hjtk.core.ai.Transport.WEB -> if (a.member.provider == com.ht.hjtk.core.ai.Provider.DEEPSEEK) JobLlm(job.id, h.deepseek, critic = role == com.ht.hjtk.core.ai.Role.REVIEW)
                else com.ht.hjtk.engine.WebAiHub.port(a.member.provider)
        }
        val writer = portOf(team[AiRole.WRITE], AiRole.WRITE) ?: JobLlm(job.id, port())
        val roster = LinkedHashMap<String, String>().apply { team.forEach { (r, a) -> put(r.label, a.name + " · " + a.why) } }
        val ledger = com.ht.hjtk.core.ai.TokenLedger()
        store.updateJob(job.id) { it.copy(team = roster.entries.joinToString("\n") { e -> e.key + "=" + e.value }) }
        val crewNow = crewFor(job.id, diff)
        // 누가 일하고 누가 빠졌는지(이유와 함께) 처음부터 보여 준다 — 'GPT가 일을 안 하는 건지 못 하는 건지'
        val inTeam = crewNow.members().filter { crewNow.available(it) }
        val missing = com.ht.hjtk.engine.AiHealth.ALL.filter { p -> inTeam.none { it.provider == p } }.map { p ->
            val m = crewNow.members().firstOrNull { it.provider == p }
            p.label + "(" + (if (m == null) "로그인 안 됨" else com.ht.hjtk.engine.Scoreboard.snapshot()[m.key]?.lastNote?.ifBlank { null } ?: "쉬는 중") + ")"
        }
        val teamLine = "팀 " + inTeam.joinToString(" · ") { it.name } + if (missing.isEmpty()) "" else " | 빠짐 " + missing.joinToString(", ") + " → 허브 → AI 상태 점검"
        val loop: MissionEngine = if (legacy) AgentLoop(ctx, JobLlm(job.id, port()), tools, human, gate, l.control, { e -> onEvent(l, e) },
            config = LoopConfig(autoAllowE2 = s.autoAllowE2), resumeFrom = resumeFrom)
        // 3.3: 지휘자(센터) — 한 AI가 설계·분배·검수까지 책임지고, 팀원은 동시에 일한다
        // 3.5: 프로젝트 = 회사 모드 — PM·스프린트·리뷰 채점·최종 QA, 30분~3시간
        else if (project) com.ht.hjtk.core.agent.Company(ctx, crewNow, tools, human, gate, l.control, { e -> onEvent(l, e); store.updateJob(job.id) { it.copy(tokens = ledger.total()) } },
            autoAllowE2 = s.autoAllowE2, minutes = job.minutes, ledger = ledger, catalog = com.ht.hjtk.core.agent.FoundryPrompts.catalogFor(job.goal, ctx.disabledCommands),
            files = DirFiles(store.jobDir(job.id)),
            save = { json -> runCatching { java.io.File(store.jobDir(job.id), COMPANY_STATE).writeText(json) } },
            load = { runCatching { java.io.File(store.jobDir(job.id), COMPANY_STATE).takeIf { it.exists() }?.readText() }.getOrNull() },
            // 발열: 뜨거우면 티켓 사이에 쉬고(식히고), 동시에 일하는 AI 수를 줄인다
            pace = { when (com.ht.hjtk.engine.Thermal.level.value) { 3 -> delay(90_000); 2 -> delay(20_000); else -> Unit } },
            width = { when (com.ht.hjtk.engine.Thermal.level.value) { 0, 1 -> 3; 2 -> 2; else -> 1 } },
            deliver = { d -> deliverer.deliver(job.id, job.convId, d.title, d.order, d.summary, d.credit).map { o -> kindLabel(o.kind) + " — " + o.label } },
            smoke = { path -> deliverer.smoke(job.id, path) })
        else com.ht.hjtk.core.agent.Conductor(ctx, crewNow, tools, human, gate, l.control, { e -> onEvent(l, e); store.updateJob(job.id) { it.copy(tokens = ledger.total()) } },
            autoAllowE2 = s.autoAllowE2, difficulty = diff, ledger = ledger, catalog = com.ht.hjtk.core.agent.FoundryPrompts.catalogFor(job.goal, ctx.disabledCommands),
            files = DirFiles(store.jobDir(job.id)),
            deliver = { d -> deliverer.deliver(job.id, job.convId, d.title, d.order, d.summary, d.credit).map { o -> kindLabel(o.kind) + " — " + o.label } })
        l.loop = loop
        if (project) wake(job.id, job.minutes)
        store.msg(job.convId, Role.SYSTEM, (if (project) "프로젝트를 맡았어요 · ${job.minutes}분 · PM이 헌장을 쓰고 스프린트로 진행해요" else "작업을 맡았어요 · ${diff.label}") + (active?.let { " · 스킬 '${it.name}'" } ?: "") + "\n" + teamLine, "note", mapOf("job" to job.id))
        l.task = scope.launch {
            val out = try { loop.run() }
                catch (e: CancellationException) { LoopOutcome(JobState.ABORTED, "중지됨", 0, "중지") }
                catch (e: Throwable) { CrashLog.record(e, "job"); LoopOutcome(if (l.control.signal.value == JobControl.Signal.ABORT) JobState.ABORTED else JobState.FAILED, if (l.control.signal.value == JobControl.Signal.ABORT) "중지됨" else "오류: ${e.message}", 0, "오류") }
            store.updateJob(job.id) { it.copy(state = out.state.name, final = out.final, label = out.label, progress = if (out.state == JobState.COMPLETED) 1.0 else it.progress) }
            live.remove(job.id); liveCount.value = live.size
            unwake(job.id)
            Notifier.report(h.app, job.id, if (out.state == JobState.COMPLETED) "완료 · ${job.goal.take(30)}" else "중단 · ${job.goal.take(30)}", out.final.take(400))
        }
    }

    fun pause(jobId: String) { live[jobId]?.control?.pause(); store.updateJob(jobId) { it.copy(state = "PAUSED", label = "일시정지") } }
    fun unpause(jobId: String) { if (live.containsKey(jobId)) { live[jobId]?.control?.resume(); store.updateJob(jobId) { it.copy(state = "RUNNING", label = "재개") } } else resume(jobId) }
    fun abort(jobId: String) {
        live[jobId]?.let { l ->
            l.control.abort(); pending.value.values.filter { p -> p.jobId == jobId }.forEach { p -> p.deferred.complete(null) }
            store.updateJob(jobId) { it.copy(state = "ABORTING", label = "멈추는 중") }
            // 지휘자가 1.5초 안에 스스로 멈추지 않으면(옛 엔진·도구 대기 등) 작업 코루틴째 끊는다 → 진행 중인 AI 요청도 취소되고 사이트의 '중지'가 눌린다
            scope.launch { delay(1_500); if (live[jobId] === l) l.task?.cancel() }
        }
        if (!live.containsKey(jobId)) store.updateJob(jobId) { it.copy(state = "ABORTED", label = "중지") }
        // 대화 창이 이 작업 때문에 DeepSeek에 묶여 있던 답 대기도 정리
        com.ht.hjtk.core.agent.StageBus.edit(jobId) { it.copy(phase = "중지") }
    }

    /** PC 연결처럼 작업이 없어도 프로세스를 살려 둘 때 */
    fun keepAlive() = ensureService()

    private fun ensureService() {
        runCatching { ContextCompat.startForegroundService(h.app, Intent(h.app, AgentService::class.java)) }
    }

    // ── 사건 → 대화/작업/알림 ─────────────────────────────────────
    private fun onEvent(l: Live, e: AgentEvent) {
        synchronized(l.events) { l.events += e }
        l.journal.append(e.javaClass.simpleName, describe(e), e.at)
        val conv = l.convId; val jid = l.jobId
        when (e) {
            is AgentEvent.Replied -> {
                val say = com.ht.hjtk.core.stream.AepView.clean(e.turn.say).trim()
                if (say.isNotEmpty() && say != l.lastSay && !e.turn.done) { l.lastSay = say; store.msg(conv, Role.AI, say, "text", mapOf("job" to jid, "via" to e.via, "step" to e.step.toString())) }
            }
            is AgentEvent.CmdEnd -> {
                store.msg(conv, Role.SYSTEM, (e.spec?.title ?: e.call.cmd), "cmd", mapOf("job" to jid, "cmd" to e.call.cmd, "ok" to e.result.ok.toString(), "ms" to e.ms.toString(),
                    "args" to e.call.args.entries.joinToString(" ") { "${it.key}=${it.value.take(40)}" }.take(160), "out" to e.result.text.take(600)))
                store.updateJob(jid) { it.copy(cmds = it.cmds + 1) }
            }
            is AgentEvent.Plan -> {
                val eta = EtaEstimator.estimate(e.steps, l.loop?.durations ?: emptyList(), e.progress)
                val now = e.steps.firstOrNull { it.status == StepStatus.DOING }?.title ?: e.steps.firstOrNull { it.status == StepStatus.TODO }?.title ?: ""
                store.updateJob(jid) { it.copy(plan = planJson(e.steps), next = e.next, progress = e.progress ?: it.progress, eta = eta.label(), now = now) }
                AgentService.update(h.app)
            }
            is AgentEvent.Report -> postReport(l, e.text, aiWritten = true)
            is AgentEvent.Note -> store.msg(conv, Role.SYSTEM, e.text, "note", mapOf("job" to jid))
            is AgentEvent.State -> {
                l.state = e.state
                if (!e.state.terminal) store.updateJob(jid) { it.copy(state = e.state.name, label = e.why) }
                AgentService.update(h.app)
            }
            is AgentEvent.Collab -> store.msg(conv, Role.SYSTEM, e.text, "collab", mapOf("job" to jid, "role" to e.role, "who" to e.who))
            is AgentEvent.Done -> store.msg(conv, Role.AI, com.ht.hjtk.core.stream.AepView.clean(e.final), "final", mapOf("job" to jid, "label" to e.label))
            is AgentEvent.Approval -> pending.value.values.firstOrNull { it.approval?.call?.callId == e.call.callId && it.jobId == jid }?.let { p ->
                store.updateMsg(conv, p.msgId) { m -> m.copy(meta = m.meta + ("status" to if (e.approved) "approved" else "rejected") + ("note" to e.note)) }
            }
            else -> {}
        }
    }

    private fun planJson(steps: List<PlanStep>) = JSONArray(steps.map { JSONObject().put("t", it.title).put("eta", it.etaSec).put("s", it.status.name) }).toString()

    private fun describe(e: AgentEvent): String = when (e) {
        is AgentEvent.CmdEnd -> "${e.call.cmd} ok=${e.result.ok} ${e.ms}ms"
        is AgentEvent.Replied -> "step=${e.step} via=${e.via} level=${e.turn.parseLevel} cmds=${e.turn.cmds.size}"
        is AgentEvent.State -> "${e.state} ${e.why}"
        is AgentEvent.Done -> e.label
        is AgentEvent.NeedApproval -> "${e.req.call.cmd} digest=${e.req.digest.take(16)}"
        else -> e.javaClass.simpleName
    }

    // ── 5분 보고 ────────────────────────────────────────────────
    private suspend fun reportTicker() {
        while (scope.isActive) {
            delay(15_000)
            val now = System.currentTimeMillis()
            for (l in live.values) {
                if (l.composer.due(now)) {
                    val loop = l.loop ?: continue
                    val evs = synchronized(l.events) { l.events.toList() }
                    val eta = EtaEstimator.estimate(loop.plan, loop.durations, loop.progress)
                    val r = l.composer.compose(now, evs, loop.plan, loop.next, loop.progress, eta, l.state, l.startedAt)
                    postReport(l, r.text(), aiWritten = false, headline = r.headline)
                }
            }
        }
    }

    private fun postReport(l: Live, text: String, aiWritten: Boolean, headline: String? = null) {
        val j = store.job(l.jobId) ?: return
        val head = headline ?: text.lines().first().take(60)
        store.msg(l.convId, Role.SYSTEM, text, "report", mapOf("job" to l.jobId, "n" to (j.reports + 1).toString(), "ai" to aiWritten.toString()))
        store.updateJob(l.jobId) { it.copy(reports = it.reports + 1) }
        Notifier.report(h.app, l.jobId, "보고 #${j.reports + 1} · $head", text)
    }

    fun reportNow(jobId: String) {
        val l = live[jobId] ?: return; val loop = l.loop ?: return
        val evs = synchronized(l.events) { l.events.toList() }
        val r = l.composer.compose(System.currentTimeMillis(), evs, loop.plan, loop.next, loop.progress, EtaEstimator.estimate(loop.plan, loop.durations, loop.progress), l.state, l.startedAt)
        postReport(l, r.text(), false, r.headline)
    }

    // ── 사람의 손(HumanPort) ─────────────────────────────────────
    val human = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest): ApprovalAnswer {
            val j = store.job(req.jobId)
            val conv = j?.convId ?: req.jobId.takeIf { it.startsWith("manual:") }?.removePrefix("manual:") ?: return ApprovalAnswer.Rejected("작업 없음")
            val pid = "p" + UUID.randomUUID().toString().take(8)
            val m = store.msg(conv, Role.SYSTEM, req.summary, "approval", mapOf("pid" to pid, "job" to req.jobId, "cmd" to req.call.cmd, "title" to req.spec.title,
                "effect" to "E${req.spec.effect.level} ${req.spec.effect.label}", "warn" to (req.warning ?: ""), "digest" to req.digest.take(12), "status" to "pending"))
            val d = CompletableDeferred<String?>()
            pending.update { it + (pid to Pending(pid, req.jobId, conv, "approval", m.id, d, req)) }
            // 알림에서 바로 '실행'·'거절'(돈이 드는 일·삭제는 앱에서 내용을 보고 누르도록 알림 버튼을 달지 않는다)
            val acts = if (req.spec.effect.level <= 2) listOf(com.ht.hjtk.engine.QuickActions.approve(h.app, pid, true), com.ht.hjtk.engine.QuickActions.approve(h.app, pid, false)) else emptyList()
            Notifier.need(h.app, pid, "허락이 필요해요 · ${req.spec.title}", req.summary, actions = acts)
            val ans = withTimeoutOrNull(60 * 60_000L) { d.await() }
            pending.update { it - pid }; Notifier.cancel(h.app, pid)
            return if (ans == "approve") ApprovalAnswer.Approved(gate.issue(req.digest, System.currentTimeMillis()))
            else { store.updateMsg(conv, m.id) { it.copy(meta = it.meta + ("status" to "rejected")) }; ApprovalAnswer.Rejected(ans ?: "응답 없음(1시간)") }
        }

        override suspend fun ask(jobId: String, question: String, choices: List<String>): String? {
            val conv = store.job(jobId)?.convId ?: return null
            val pid = "q" + UUID.randomUUID().toString().take(8)
            val m = store.msg(conv, Role.AI, question, "ask", mapOf("pid" to pid, "job" to jobId, "choices" to choices.joinToString("|"), "status" to "pending"))
            val d = CompletableDeferred<String?>()
            pending.update { it + (pid to Pending(pid, jobId, conv, "ask", m.id, d)) }
            Notifier.need(h.app, pid, "질문이 있어요", question, actions = listOf(com.ht.hjtk.engine.QuickActions.reply(h.app, pid)))
            val a = withTimeoutOrNull(45 * 60_000L) { d.await() }
            pending.update { it - pid }; Notifier.cancel(h.app, pid)
            store.updateMsg(conv, m.id) { it.copy(meta = it.meta + ("status" to "answered") + ("answer" to (a ?: "(응답 없음)"))) }
            return a
        }

        override suspend fun takeover(jobId: String, what: String, url: String?): String? {
            val conv = store.job(jobId)?.convId ?: return null
            val pid = "t" + UUID.randomUUID().toString().take(8)
            val m = store.msg(conv, Role.AI, what, "takeover", mapOf("pid" to pid, "job" to jobId, "url" to (url ?: ""), "status" to "pending"))
            val d = CompletableDeferred<String?>()
            pending.update { it + (pid to Pending(pid, jobId, conv, "takeover", m.id, d)) }
            Notifier.need(h.app, pid, "직접 해 주실 일이 있어요", what)
            val a = withTimeoutOrNull(60 * 60_000L) { d.await() }
            pending.update { it - pid }
            store.updateMsg(conv, m.id) { it.copy(meta = it.meta + ("status" to if (a != null) "done" else "skipped")) }
            return a
        }
    }

    fun resolve(pid: String, answer: String?) { pending.value[pid]?.deferred?.complete(answer) }
    fun openTakeover(url: String) { if (url.isNotBlank()) openCustomTab(h.app, url) }

    // ── 화면 공유 요청: 알림의 '이 화면 보내기' ──────────
    suspend fun requestScreen(jobId: String, reason: String): String? {
        val conv = store.job(jobId)?.convId
        conv?.let { store.msg(it, Role.AI, "도움이 필요한 화면을 띄운 뒤, 알림의 '이 화면 보내기'를 눌러 주세요.\n이유: $reason", "screen", mapOf("job" to jobId)) }
        val d = CompletableDeferred<String?>(); screenDeferred = d; screenWaiting.value = reason
        // 찍힌 결과를 기다린다(허락 → 한 장 → 끝)
        val watcher = scope.launch { com.ht.hjtk.engine.ScreenCapture.results.collect { f -> fulfillScreen(shot = f); if (f == null) fulfillScreen() } }
        Notifier.need(h.app, "screen", "화면을 보여 주세요", "$reason\n도움이 필요한 화면을 띄우고 이 알림을 누르세요. 허락하면 지금 화면 한 장만 찍어요.", ScreenShare.pendingIntent(h.app))
        val r = withTimeoutOrNull(20 * 60_000L) { d.await() }
        watcher.cancel()
        screenWaiting.value = null; screenDeferred = null
        return r
    }

    /**
     * 화면 공유 결과(찍은 한 장). 그림을 볼 수 있는 AI가 있으면 그 AI가 화면을 글로 옮기고, 없으면 사실대로 알린다.
     * 이미지 공유(다른 앱에서 'HJTK로 공유')로 들어온 그림도 같은 길로 들어온다.
     */
    fun fulfillScreen(extraText: String? = null, shot: java.io.File? = null) {
        val d = screenDeferred ?: return
        scope.launch {
            val f = shot
            val reason = screenWaiting.value.orEmpty()
            val conv = live.values.firstOrNull()?.convId
            if (f != null && conv != null) store.msg(conv, Role.USER, "화면을 보냈어요", "image", mapOf("image" to f.absolutePath))
            val text = when {
                f != null -> screenText(f, reason, conv)
                extraText != null -> extraText
                else -> "사용자가 화면 보내기를 취소했다. 필요하면 task.ask로 상황을 글로 물어보라."
            }
            d.complete(text)
        }
    }

    private val lastMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 화면을 AI에게: 먼저 기기에서 글자를 읽어 '글자 지도'로 보낸다(토큰 70~85% 절약, 그림 못 보는 AI도 읽음).
     * 글자가 거의 없는 화면일 때만 그림을 768px로 줄여 그림 보는 AI에게 보낸다.
     */
    private suspend fun screenText(f: java.io.File, reason: String, conv: String?): String {
        val r = com.ht.hjtk.engine.ScreenReader.read(f)
        if (r != null && r.enough) {
            val key = conv ?: "screen"
            val sent = com.ht.hjtk.core.vision.ScreenMap.diff(lastMap[key], r.map); lastMap[key] = r.map
            conv?.let { store.msg(it, Role.SYSTEM, "화면을 글자 지도로 읽었어요 · 약 ${com.ht.hjtk.core.vision.ScreenMap.tokens(sent)}토큰(그림이면 약 ${com.ht.hjtk.core.vision.ScreenMap.imageTokens(r.w, r.h)})", "note") }
            return "사용자가 보낸 휴대폰 화면(이유: $reason)을 기기에서 글자로 읽은 지도다. 위치는 줄·칸 번호로 표시된다.\n$sent\n(스크린샷: ${f.name})"
        }
        val small = com.ht.hjtk.engine.ScreenReader.small(f, java.io.File(f.parentFile, "s_" + f.nameWithoutExtension + ".jpg"))
        return h.ai.see(listOf(small), "사용자가 도움을 요청한 휴대폰 화면이다. 이유: $reason\n화면에 보이는 앱, 글자, 버튼, 오류 메시지, 현재 상태를 짧게 적어라.")
            ?.let { (who, desc) -> "사용자가 보낸 화면을 $who 가 읽었다:\n$desc\n(스크린샷: ${f.name})" }
            ?: "사용자가 화면을 보냈다(스크린샷 ${f.name}). 글자가 거의 없는 화면이고 그림을 볼 수 있는 AI(Claude·GPT·Gemini 키)가 없어 내용은 읽지 못했다. 필요하면 task.ask로 화면 내용을 글로 물어보라."
    }

    suspend fun pickFile(jobId: String, hint: String): String? {
        val d = CompletableDeferred<String?>(); pickDeferred = d
        h.launcher.launch(Intent(h.app, com.ht.hjtk.PickActivity::class.java), "파일을 골라 주세요", hint)
        return withTimeoutOrNull(15 * 60_000L) { d.await() }.also { pickDeferred = null }
    }
    fun fulfillPick(text: String?) { pickDeferred?.complete(text) }

    // ── 빠른 대화(작업 아님) ──────────────────────────────────────
    fun quickAsk(convId: String, text: String, image: String? = null) {
        busyQuick.update { it + convId }
        scope.launch {
            try {
                if (image != null) { askWithImage(convId, text, java.io.File(image)); return@launch }
                val t = text.trim()
                val body = t.substringAfter(' ', "").trim()
                val direct = mentioned(t)
                when {
                    (t.startsWith("@함께") || t.startsWith("@협업") || t.startsWith("@together")) && body.isNotEmpty() -> askTogether(convId, body)
                    direct != null -> askOnce(convId, direct.second, waitForLogin = true, pinned = direct.first)
                    else -> askOnce(convId, text, waitForLogin = true)
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                CrashLog.record(e, "quickAsk")
                store.msg(convId, Role.SYSTEM, explain("EXCEPTION") + " (${e.javaClass.simpleName})", "error", mapOf("via" to "EXCEPTION"))
            } finally { busyQuick.update { it - convId } }
        }
    }

    // ── 대화: 고른 AI(또는 자동). 작업이 DeepSeek 창을 쓰는 중이면 대화용 두 번째 창 → 작업 중에도 대화가 막히지 않는다 ──
    private val chatLock = Mutex()
    private val chatPorts = ConcurrentHashMap<String, LlmPort>()

    /** "@클로드 …" 같은 지정 → 그 회사의 AI(API 계정 > 앱 연결 > DeepSeek은 웹) */
    private fun mentioned(t: String): Pair<com.ht.hjtk.core.ai.Assignment, String>? {
        val m = Regex("^@(\\S+)\\s+(.+)", RegexOption.DOT_MATCHES_ALL).find(t) ?: return null
        val tag = m.groupValues[1].lowercase()
        val p = when {
            tag.startsWith("클로드") || tag.startsWith("claude") -> com.ht.hjtk.core.ai.Provider.CLAUDE
            tag.startsWith("지피티") || tag.startsWith("챗지피티") || tag.startsWith("gpt") || tag.startsWith("chatgpt") -> com.ht.hjtk.core.ai.Provider.GPT
            tag.startsWith("제미나이") || tag.startsWith("gemini") -> com.ht.hjtk.core.ai.Provider.GEMINI
            tag.startsWith("그록") || tag.startsWith("grok") -> com.ht.hjtk.core.ai.Provider.GROK
            tag.startsWith("딥시크") || tag.startsWith("deepseek") -> com.ht.hjtk.core.ai.Provider.DEEPSEEK
            else -> return null
        }
        val pool = h.ai.members().filter { it.provider == p }
        val mem = pool.firstOrNull { it.transport == com.ht.hjtk.core.ai.Transport.API } ?: pool.firstOrNull() ?: com.ht.hjtk.core.ai.Member(p, com.ht.hjtk.core.ai.Transport.APP)
        return com.ht.hjtk.core.ai.Assignment(com.ht.hjtk.core.ai.Role.CHAT, mem, com.ht.hjtk.core.ai.ModelRouter.modelFor(mem, com.ht.hjtk.core.ai.Tier.BALANCED), "직접 지정") to m.groupValues[2].trim()
    }

    private suspend fun chatSend(convId: String, text0: String, a: com.ht.hjtk.core.ai.Assignment?): LlmReply = chatSendRaw(convId, if (com.ht.hjtk.core.I18n.en) "$text0\n\n(${com.ht.hjtk.core.I18n.answerIn()})" else text0, a)

    private suspend fun chatSendRaw(convId: String, text: String, a: com.ht.hjtk.core.ai.Assignment?): LlmReply = when (a?.member?.transport) {
        com.ht.hjtk.core.ai.Transport.API -> {
            val port = chatPorts.getOrPut(a.member.key + "|" + (a.model?.id ?: "")) { h.ai.apiPort(a, "", stateless = false) ?: return LlmReply("", "NO_KEY", false, 0) }
            streamingConv.value = convId
            try { port.send(text, convId, 1, false) } finally { streamingConv.value = null }
        }
        com.ht.hjtk.core.ai.Transport.APP -> h.ai.appPort(a, TapBudget(1), store.settings.value.geminiWaitMin * 60_000L).send(text, convId, 1, true)
        com.ht.hjtk.core.ai.Transport.WEB -> if (a.member.provider == com.ht.hjtk.core.ai.Provider.DEEPSEEK) webChat(convId, text) else webAiChat(convId, text, a.member.provider)
        else -> webChat(convId, text)
    }

    /** 로그인해 둔 AI 웹으로 대화: 대화마다 그 사이트의 대화방을 이어 쓴다 */
    private val webAiRooms = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun webAiChat(convId: String, text: String, p: com.ht.hjtk.core.ai.Provider): LlmReply {
        val port = com.ht.hjtk.engine.WebAiHub.port(p) ?: return LlmReply("", "NO_WEB", false, 0)
        val key = "$convId|${p.name}"
        val room = webAiRooms[key]
        if (room != null) port.ensureSession(room)
        streamingConv.value = convId
        port.wantModel = h.ai.state.value.chatPin.substringAfter('|', "").takeIf { it.isNotBlank() }?.let { id -> com.ht.hjtk.core.ai.ModelCatalog.of(p).firstOrNull { it.id == id }?.label?.split(' ')?.lastOrNull { w -> w.any { c -> c.isLetter() } } }
        val r = try { port.send(com.ht.hjtk.engine.ChatStyle.hint() + text, convId, 1, room == null) } finally { streamingConv.value = null }
        port.lastUrl?.takeIf { it != port.site.home && r.text.isNotBlank() && webAiRooms.none { (k, v) -> k != key && v == it } }?.let { webAiRooms[key] = it }
        return r
    }

    /** DeepSeek 웹: 작업이 주 창을 잡고 있으면 대화용 창으로 */
    private suspend fun webChat(convId: String, text: String): LlmReply {
        val lane = if (llmLock.isLocked) h.deepseekChat else h.deepseek
        val lock = if (lane === h.deepseek) llmLock else chatLock
        return withTimeoutOrNull(8 * 60_000L) {
            lock.withLock {
                val conv = store.conversations.value.firstOrNull { it.id == convId }
                val fresh = conv?.dsSession == null
                if (!fresh) lane.ensureSession(conv?.dsSession)
                streamingConv.value = convId
                val rr = try { mirror(lane, convId, "DeepSeek" + if (lane !== h.deepseek) " · 대화 창" else "") { lane.send(com.ht.hjtk.engine.ChatStyle.hint() + text, convId, 1, fresh) } } finally { streamingConv.value = null }
                // 이 대화방 주소 기억: 답을 받았고, 다른 대화가 이미 쓰는 주소가 아닐 때만(새 대화가 옛 대화방을 이어 쓰던 버그)
                lane.currentUrl()?.takeIf { (it.contains("/chat/") || it.contains("/a/")) && rr.text.isNotBlank() && store.conversations.value.none { c -> c.id != convId && c.dsSession == it } }
                    ?.let { u -> store.updateConversation(convId) { it.copy(dsSession = u) } }
                rr
            }
        } ?: LlmReply("", "TIMEOUT", false, 0)
    }

    /** DeepSeek 창의 '쓰는 중' 글자를 LiveBus로 비춘다(대화·작업 화면이 같은 방식으로 실시간 표시) */
    private suspend fun <T> mirror(web: DeepSeekWeb, owner: String, who: String, block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        // drop(1): StateFlow가 직전 답(다른 대화의 것)을 다시 흘려보내지 않게 — 새 대화에서 이전 답이 뜨던 버그
        val j = launch { web.partial.drop(1).collect { if (it.isNotEmpty()) LiveBus.put(owner, who, it) } }
        try { block() } finally { j.cancel(); LiveBus.clear(owner, who) }
    }

    /**
     * 실시간 음성: 가장 빠른 AI(음성 역할 · 빠른 등급)로 짧게. 글자는 LiveBus("live")로 흘러서 화면이 문장 단위로 읽어 준다.
     */
    private val livePorts = ConcurrentHashMap<String, LlmPort>()
    /** AI 통화용 한 번 묻기: 통화마다 같은 대화방(맥락 유지). 키 있는 빠른 AI가 있으면 그쪽, 없으면 DeepSeek 웹 */
    suspend fun callAsk(prompt: String, key: String): String {
        val a = com.ht.hjtk.core.ai.ModelRouter.pick(AiRole.LIVE, com.ht.hjtk.core.ai.Difficulty.QUICK, h.ai.members().filter { it.transport == com.ht.hjtk.core.ai.Transport.API })
        if (a != null) {
            val port = livePorts.getOrPut("call:" + a.member.key + key) { h.ai.apiPort(a, key, stateless = false) ?: return "" }
            return port.send(prompt, key, 1, false).text
        }
        val lane = if (llmLock.isLocked) h.deepseekChat else h.deepseek
        val lock = if (lane === h.deepseek) llmLock else chatLock
        val r = lock.withLock {
            val sess = criticSessions[key]
            if (sess != null) lane.ensureSession(sess)
            val rr = lane.send(prompt, key, 1, sess == null)
            lane.currentUrl()?.takeIf { it.contains("/a/") || it.contains("/chat/") }?.let { criticSessions[key] = it }
            rr
        }
        return com.ht.hjtk.core.stream.AepView.clean(r.text)
    }

    suspend fun liveAsk(text: String, en: Boolean): Pair<String, String> {
        val prompt = (if (en) "[Voice chat] Answer naturally in 2-4 short spoken sentences. No lists, tables or markdown.\n" else "[음성 대화] 말하듯이 2~4문장으로 짧게. 목록·표·마크다운 없이.\n") + text
        val a = com.ht.hjtk.core.ai.ModelRouter.pick(AiRole.LIVE, com.ht.hjtk.core.ai.Difficulty.QUICK, h.ai.members().filter { it.transport != com.ht.hjtk.core.ai.Transport.APP })
        if (a != null && a.member.transport == com.ht.hjtk.core.ai.Transport.API) {
            val port = livePorts.getOrPut(a.member.key) { h.ai.apiPort(a, "live", stateless = false) ?: return a.name to "" }
            return a.name to port.send(prompt, "live", 1, false).text
        }
        val key = "live"
        val lane = if (llmLock.isLocked) h.deepseekChat else h.deepseek
        val lock = if (lane === h.deepseek) llmLock else chatLock
        val r = lock.withLock {
            val sess = criticSessions[key]
            if (sess != null) lane.ensureSession(sess)
            val rr = mirror(lane, "live", "DeepSeek") { lane.send(prompt, key, 1, sess == null) }
            lane.currentUrl()?.takeIf { it.contains("/a/") || it.contains("/chat/") }?.let { criticSessions[key] = it }
            rr
        }
        return "DeepSeek" to com.ht.hjtk.core.stream.AepView.clean(r.text)
    }

    /** 대화용 두 번째 의견: 대화 AI와 다른 회사를 우선 */
    private suspend fun secondOpinion(convId: String, prompt: String, avoid: com.ht.hjtk.core.ai.Provider?): Pair<String, String> {
        val a = com.ht.hjtk.core.ai.ModelRouter.pick(com.ht.hjtk.core.ai.Role.REVIEW, com.ht.hjtk.core.ai.Difficulty.STANDARD, h.ai.members(), avoid = avoid)
        if (a != null && a.member.transport == com.ht.hjtk.core.ai.Transport.API) h.ai.apiPort(a, convId, stateless = true)?.send(prompt, convId, 1, true)?.text?.takeIf { it.isNotBlank() }?.let { return a.name to it }
        if (a != null && a.member.transport == com.ht.hjtk.core.ai.Transport.APP) h.ai.appPort(a, TapBudget(1), store.settings.value.geminiWaitMin * 60_000L).send(prompt, convId, 1, true).text.takeIf { it.isNotBlank() }?.let { return a.name to it }
        val key = "c:$convId"
        val lane = if (llmLock.isLocked) h.deepseekChat else h.deepseek
        val lock = if (lane === h.deepseek) llmLock else chatLock
        val r = lock.withLock {
            val sess = criticSessions[key]
            if (sess != null) lane.ensureSession(sess)
            val rr = lane.send(prompt, key, 1, sess == null)
            lane.currentUrl()?.takeIf { it.contains("/a/") || it.contains("/chat/") }?.let { criticSessions[key] = it }
            rr
        }
        return "DeepSeek 검토창" to com.ht.hjtk.core.stream.AepView.clean(r.text)
    }

    /**
     * 사진과 함께 묻기. 대화 AI가 그림을 볼 수 있으면(Claude·GPT·Gemini API) 그대로,
     * 아니면 '보는 AI'가 그림을 글로 옮기고 대화 AI(DeepSeek 등)가 답한다 — AI끼리 나눠 맡기.
     */
    private suspend fun askWithImage(convId: String, q: String, img: java.io.File) {
        val a = h.ai.chatAssignment()
        val question = q.ifBlank { if (com.ht.hjtk.core.I18n.en) "Describe this image and tell me what matters." else "이 사진에서 중요한 내용을 알려 줘." }
        // 1) 글자가 많은 사진(화면·문서·영수증)은 글자 지도로: 어느 AI든 읽고 토큰이 훨씬 적다
        val read = com.ht.hjtk.engine.ScreenReader.read(img, "사진")
        if (read != null && read.enough) {
            val r = chatSend(convId, "[사용자가 보낸 사진을 기기에서 글자로 읽은 지도 — 그림 대신 글자 위치]\n${read.map}\n\n[질문]\n$question\n\n(글자만으로 답할 수 없는 질문(색·모양·사람·물건 생김새)이면 정확히 [[사진 필요]] 한 줄만 써라)", a)
            val txt = com.ht.hjtk.core.stream.AepView.clean(r.text)
            if (txt.isNotBlank() && !txt.contains("[[사진 필요]]")) {
                store.msg(convId, Role.AI, txt, "text", mapOf("via" to r.via, "who" to (a?.name ?: "DeepSeek") + " · 글자 지도", "saved" to read.saved.toString()))
                return
            }
        }
        // 2) 그림이 꼭 필요할 때만 768px로 줄여 그림 보는 AI에게
        val img = com.ht.hjtk.engine.ScreenReader.small(img, java.io.File(img.parentFile, "s_" + img.nameWithoutExtension + ".jpg"))
        if (h.ai.canSee(a)) {
            val port = (chatPorts.getOrPut(a!!.member.key + "|" + (a.model?.id ?: "")) { h.ai.apiPort(a, "", stateless = false) ?: return }) as? com.ht.hjtk.engine.ApiPort
            if (port != null) {
                streamingConv.value = convId
                val r = try { port.sendWithImages(question, convId, false, listOf(img)) } finally { streamingConv.value = null }
                if (r.text.isNotBlank()) { store.msg(convId, Role.AI, r.text, "text", mapOf("via" to r.via, "who" to a.name)); return }
            }
        }
        val seen = h.ai.see(listOf(img), "이 사진을 자세히 글로 옮겨라: 보이는 글자(그대로), 물건, 표·숫자, 상황. 사용자의 질문: $question", convId)
        if (seen == null) {
            store.msg(convId, Role.SYSTEM, "사진을 보려면 그림을 읽을 수 있는 AI가 필요해요. 설정 → AI 모델에서 Claude·ChatGPT·Gemini 중 하나의 키를 넣어 주세요.", "error", mapOf("via" to "NO_VISION"))
            return
        }
        store.msg(convId, Role.SYSTEM, seen.second.take(700) + if (seen.second.length > 700) " …" else "", "collab", mapOf("role" to "보기", "who" to seen.first))
        val r = chatSend(convId, "[사용자가 보낸 사진 — ${seen.first}가 글로 옮긴 내용]\n${seen.second}\n\n[질문]\n$question", a)
        val name = a?.name ?: "DeepSeek"
        if (r.text.isNotBlank()) store.msg(convId, Role.AI, com.ht.hjtk.core.stream.AepView.clean(r.text), "text", mapOf("via" to r.via, "who" to "$name × ${seen.first}"))
        else store.msg(convId, Role.AI, seen.second, "text", mapOf("via" to "VISION", "who" to seen.first))
    }

    /** @함께: 초안(대화 AI) → 검토(다른 AI) → 종합(대화 AI). 각 단계가 대화에 협업 기록으로 남는다 */
    private suspend fun askTogether(convId: String, q: String) {
        val a = h.ai.chatAssignment()
        val name = a?.name ?: "DeepSeek"
        val first = chatSend(convId, q, a)
        if (first.text.isBlank()) { store.msg(convId, Role.SYSTEM, explain(first.via), "error", mapOf("via" to first.via)); return }
        val draft = com.ht.hjtk.core.stream.AepView.clean(first.text)
        store.msg(convId, Role.SYSTEM, draft.take(600) + if (draft.length > 600) " …" else "", "collab", mapOf("role" to "초안", "who" to name))
        val (who, crit) = secondOpinion(convId, "다른 AI가 아래 질문에 답했다. 냉정한 검토자로서 틀린 점·빠진 점·더 나은 관점을 3개 이내로 짚어라. 맞으면 '동의'와 이유 한 줄.\n\n[질문]\n$q\n\n[답]\n${draft.take(7000)}", a?.member?.provider)
        if (crit.isBlank()) { store.msg(convId, Role.AI, draft, "text", mapOf("via" to first.via, "who" to name)); return }
        store.msg(convId, Role.SYSTEM, crit.trim().take(900), "collab", mapOf("role" to "검토", "who" to who))
        val fin = chatSend(convId, "검토자($who)의 의견이다. 맞는 지적은 반영하고, 틀린 지적은 이유를 들어 반박하면서 최종 답을 다시 써라. 최종 답만.\n\n$crit", a)
        val out = com.ht.hjtk.core.stream.AepView.clean(fin.text).ifBlank { draft }
        store.msg(convId, Role.AI, out, "text", mapOf("via" to fin.via, "who" to "$name × $who"))
    }

    private suspend fun askOnce(convId: String, text: String, waitForLogin: Boolean, pinned: com.ht.hjtk.core.ai.Assignment? = null) {
        var a = pinned ?: h.ai.chatAssignment()
        var name = a?.name ?: "DeepSeek"
        var r = chatSend(convId, text, a)
        // 3.3: 사용량이 다 됐거나(한도 문구·429) 답이 비면 → 그 AI를 쉬게 하고, 이 질문에 가장 강한 다른 AI로 바로 이어서 묻는다(최대 2번)
        val tried = mutableSetOf(a?.member?.key ?: "DEEPSEEK|WEB")
        val skill = com.ht.hjtk.core.ai.AiProfiles.skillFor(text)
        for (hop in 0 until 2) {
            val limited = com.ht.hjtk.engine.Scoreboard.limitRest(r.text, r.via) != null
            val dead = r.text.isBlank() && r.via !in setOf("NEEDS_LOGIN", "CHALLENGE")
            if (!limited && !dead) break
            a?.let { com.ht.hjtk.engine.Scoreboard.record(it.member.key, false, 0, r.text, r.via) }
            val next = h.ai.members().filter { it.key !in tried && it.transport != com.ht.hjtk.core.ai.Transport.APP && !com.ht.hjtk.engine.Scoreboard.resting(it.key) }
                .maxByOrNull { com.ht.hjtk.engine.Scoreboard.adjust(com.ht.hjtk.core.ai.AiProfiles.score(it.provider, skill), it.key) } ?: break
            tried += next.key
            val na = com.ht.hjtk.core.ai.Assignment(com.ht.hjtk.core.ai.Role.CHAT, next, com.ht.hjtk.core.ai.ModelRouter.modelFor(next, com.ht.hjtk.core.ai.Tier.BALANCED), "자동 교체")
            store.msg(convId, Role.SYSTEM, "$name ${if (limited) "사용량이 다 됐어요" else "답이 없어요"} → ${na.name}로 이어서 답할게요", "note", mapOf("swap" to next.provider.name))
            a = na; name = na.name
            r = chatSend(convId, text, na)
        }
        if (r.text.isNotBlank()) {
            // 이 DeepSeek 대화방이 작업 형식(AEP)으로 답했다면 봉투를 벗겨 사람 말만 남긴다
            val shown = com.ht.hjtk.core.stream.AepView.clean(r.text)
            store.msg(convId, Role.AI, shown, "text", mapOf("via" to r.via, "who" to name) + (if (shown != r.text) mapOf("filtered" to "aep") else emptyMap()))
            return
        }
        val web = a == null || a.member.transport == com.ht.hjtk.core.ai.Transport.WEB
        // 로그인/확인이 필요하면: 조종석에서 끝내는 순간 같은 질문을 자동으로 이어 보낸다(최대 10분)
        if (waitForLogin && web && (r.via == "NEEDS_LOGIN" || r.via == "CHALLENGE")) {
            val base = h.deepseek
            val m = store.msg(convId, Role.SYSTEM, explain(r.via) + " 로그인을 마치면 이 질문을 자동으로 이어서 보낼게요.", "error", mapOf("via" to r.via, "waiting" to "1"))
            val ok = withTimeoutOrNull(10 * 60_000L) {
                while (base.status.value != DeepSeekWeb.Status.READY) { base.refreshState(); delay(1_500) }
                true
            } ?: false
            store.updateMsg(convId, m.id) { it.copy(meta = it.meta + ("waiting" to "0")) }
            if (ok) { store.msg(convId, Role.SYSTEM, "로그인을 확인했어요 · 이어서 보냅니다", "note"); askOnce(convId, text, waitForLogin = false, pinned) }
            else store.msg(convId, Role.SYSTEM, "로그인을 10분 기다렸어요. 로그인한 뒤 다시 보내 주세요.", "error", mapOf("via" to r.via))
            return
        }
        if (r.via == "NEEDS_LOGIN" && a?.member?.transport == com.ht.hjtk.core.ai.Transport.WEB && a.member.provider != com.ht.hjtk.core.ai.Provider.DEEPSEEK) {
            com.ht.hjtk.engine.WebAiHub.setLoggedIn(a.member.provider, false)
            store.msg(convId, Role.SYSTEM, "${a.member.provider.label} 로그인이 풀렸어요. 허브 → 계정 하나로에서 다시 연결해 주세요.", "error", mapOf("via" to r.via, "login" to a.member.provider.name)); return
        }
        val why = if (r.via == "API_ERR") "$name 연결 오류예요. 설정 → AI 모델에서 키와 모델 이름을 확인해 주세요." else if (r.via == "NO_KEY") "$name 키가 없어요. 설정 → AI 모델에서 추가하거나 다른 모델을 고르세요." else explain(r.via)
        store.msg(convId, Role.SYSTEM, why, "error", mapOf("via" to r.via))
    }

    /** 사용자가 직접 실행하는 명령(/슬래시, 명령어 사전의 '실행') */
    fun runDirect(convId: String, call: CommandCall) {
        scope.launch {
            val spec = com.ht.hjtk.core.command.CommandCatalog.find(call.cmd)
            if (spec == null) { store.msg(convId, Role.SYSTEM, "없는 명령: ${call.cmd}", "error"); return@launch }
            val tools = AndroidTools(h, "manual", convId, human)
            val t0 = System.currentTimeMillis()
            // 사용자가 직접 실행해도 밖으로 보내기·돈·삭제는 승인 카드를 거친다(작업과 같은 규칙)
            val r = runCatching {
                if (spec.effect.needsApproval) CommandExec("manual:$convId", tools, human, gate, {}, System::currentTimeMillis, false).run(call.copy(cmd = spec.id))
                else tools.run(call)
            }.getOrElse { com.ht.hjtk.core.command.CommandResult.fail(it.message ?: "오류") }
            store.msg(convId, Role.SYSTEM, spec.title, "cmd", mapOf("cmd" to spec.id, "ok" to r.ok.toString(), "ms" to (System.currentTimeMillis() - t0).toString(),
                "args" to call.args.entries.joinToString(" ") { "${it.key}=${it.value.take(40)}" }, "out" to r.text.take(4000), "manual" to "true"))
        }
    }

    /** 허브·도구 화면에서 실행하는 파이프라인(작업으로 등록되어 보고를 받는다) */
    fun runPipeline(convId: String, p: Pipeline, input: String): JobRec {
        val comp = PipelineCompiler.compile(p)
        val goal = "파이프라인 '${p.name}'을 입력 '$input'으로 실행하라. 단계:\n" + comp.steps.mapIndexed { i, s ->
            "${i + 1}. " + when (s.kind) { "cmd" -> "명령 ${s.cmd} ${s.args.entries.joinToString(" ") { "${it.key}=${PipelineCompiler.fill(it.value, input, "{prev}")}" }}"; "ai" -> "AI: ${s.prompt}"; else -> "사용자 승인: ${s.prompt}" }
        }.joinToString("\n") + "\n각 단계를 순서대로 명령으로 수행하고, {prev}는 직전 단계 결과로 바꿔라. 승인 단계는 task.approve를 써라."
        return startMission(convId, goal)
    }
}

object ScreenShare {
    fun pendingIntent(ctx: android.content.Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(ctx, 77, com.ht.hjtk.engine.ScreenCapture.intent(ctx), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
}

class ScreenShareReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) { Hjtk.get(context).runner.fulfillScreen() }
}
