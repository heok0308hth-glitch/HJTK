package com.ht.hjtk.agent

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
import com.ht.hjtk.engine.A11yBridge
import com.ht.hjtk.engine.CrashLog
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
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
data class PetCue(val text: String, val kind: Kind, val jobId: String? = null) { enum class Kind { REPORT, NEED, DONE, SCREEN, HELLO } }

/**
 * 안드로이드 쪽 오케스트레이터. 작업을 띄우고, 사건을 대화·알림·펫으로 번역하고, 사람의 손을 기다린다.
 */
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
    val pet = MutableSharedFlow<PetCue>(extraBufferCapacity = 16)
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
            s.dsMode == "app" -> h.deepseekApp
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
                pet.tryEmit(PetCue("DeepSeek 로그인이 필요해요", PetCue.Kind.NEED, this.jobId))
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
            val r = base.send(text, jobId, step, fresh)
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
    fun startMission(convId: String, goal: String, skillName: String? = null): JobRec {
        val id = JobId.new()
        val now = System.currentTimeMillis()
        val job = JobRec(id, convId, goal, "RUNNING", now, now, label = "시작")
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
        "gemini" -> if (h.gemini.available()) GeminiApiPort() else GeminiRelayPort(budget)
        "gemini_app" -> GeminiRelayPort(budget)
        "deepseek2" -> JobLlm(jobId, port(), critic = true)
        else -> if (h.gemini.available()) GeminiApiPort() else JobLlm(jobId, port(), critic = true)
    }

    private fun checkpointFile(jobId: String) = java.io.File(store.jobDir(jobId), "foundry.json")

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
        val legacy = resumeFrom != null && !cp.exists()
        val loop: MissionEngine = if (legacy) AgentLoop(ctx, JobLlm(job.id, port()), tools, human, gate, l.control, { e -> onEvent(l, e) },
            config = LoopConfig(autoAllowE2 = s.autoAllowE2), resumeFrom = resumeFrom)
        else Foundry(ctx, JobLlm(job.id, port()), criticFor(job.id, l.budget), tools, human, gate, l.control, { e -> onEvent(l, e) },
            autoAllowE2 = s.autoAllowE2, checkpoint = { json -> runCatching { cp.writeText(json) } }, resume = if (resumeFrom != null) runCatching { cp.readText() }.getOrNull() else null)
        l.loop = loop
        val crit = (loop as? Foundry)?.let { criticFor(job.id, TapBudget(0))?.name }
        store.msg(job.convId, Role.SYSTEM, "작업을 맡았어요 · 작가 ${port().name}" + (crit?.let { " · 검토 $it" } ?: "") + (active?.let { " · 스킬 '${it.name}'" } ?: ""), "note", mapOf("job" to job.id))
        l.task = scope.launch {
            val out = runCatching { loop.run() }.getOrElse { LoopOutcome(JobState.FAILED, "오류: ${it.message}", 0, "오류") }
            store.updateJob(job.id) { it.copy(state = out.state.name, final = out.final, label = out.label, progress = if (out.state == JobState.COMPLETED) 1.0 else it.progress) }
            live.remove(job.id); liveCount.value = live.size
            pet.tryEmit(PetCue(if (out.state == JobState.COMPLETED) "다 했어요! 결과를 확인해 주세요" else "작업이 멈췄어요: ${out.label}", PetCue.Kind.DONE, job.id))
            Notifier.report(h.app, job.id, if (out.state == JobState.COMPLETED) "완료 · ${job.goal.take(30)}" else "중단 · ${job.goal.take(30)}", out.final.take(400))
        }
    }

    fun pause(jobId: String) { live[jobId]?.control?.pause(); store.updateJob(jobId) { it.copy(state = "PAUSED", label = "일시정지") } }
    fun unpause(jobId: String) { if (live.containsKey(jobId)) { live[jobId]?.control?.resume(); store.updateJob(jobId) { it.copy(state = "RUNNING", label = "재개") } } else resume(jobId) }
    fun abort(jobId: String) {
        live[jobId]?.let { it.control.abort(); pending.value.values.filter { p -> p.jobId == jobId }.forEach { p -> p.deferred.complete(null) } }
        if (!live.containsKey(jobId)) store.updateJob(jobId) { it.copy(state = "ABORTED", label = "중지") }
    }

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
        pet.tryEmit(PetCue(head, PetCue.Kind.REPORT, l.jobId))
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
            val conv = j?.convId ?: return ApprovalAnswer.Rejected("작업 없음")
            val pid = "p" + UUID.randomUUID().toString().take(8)
            val m = store.msg(conv, Role.SYSTEM, req.summary, "approval", mapOf("pid" to pid, "job" to req.jobId, "cmd" to req.call.cmd, "title" to req.spec.title,
                "effect" to "E${req.spec.effect.level} ${req.spec.effect.label}", "warn" to (req.warning ?: ""), "digest" to req.digest.take(12), "status" to "pending"))
            val d = CompletableDeferred<String?>()
            pending.update { it + (pid to Pending(pid, req.jobId, conv, "approval", m.id, d, req)) }
            Notifier.need(h.app, pid, "허락이 필요해요 · ${req.spec.title}", req.summary)
            pet.tryEmit(PetCue("허락이 필요해요: ${req.spec.title}", PetCue.Kind.NEED, req.jobId))
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
            Notifier.need(h.app, pid, "질문이 있어요", question)
            pet.tryEmit(PetCue("질문이 있어요", PetCue.Kind.NEED, jobId))
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
            pet.tryEmit(PetCue("직접 해 주실 일이 있어요", PetCue.Kind.NEED, jobId))
            val a = withTimeoutOrNull(60 * 60_000L) { d.await() }
            pending.update { it - pid }
            store.updateMsg(conv, m.id) { it.copy(meta = it.meta + ("status" to if (a != null) "done" else "skipped")) }
            return a
        }
    }

    fun resolve(pid: String, answer: String?) { pending.value[pid]?.deferred?.complete(answer) }
    fun openTakeover(url: String) { if (url.isNotBlank()) openCustomTab(h.app, url) }

    // ── 화면 공유 요청: 펫을 누르거나 알림의 '이 화면 보내기' ──────────
    suspend fun requestScreen(jobId: String, reason: String): String? {
        val conv = store.job(jobId)?.convId
        conv?.let { store.msg(it, Role.AI, "도움이 필요한 화면을 띄운 뒤, 화면 위의 쥬티(펫)를 누르거나 알림의 '이 화면 보내기'를 눌러 주세요.\n이유: $reason", "screen", mapOf("job" to jobId)) }
        val d = CompletableDeferred<String?>(); screenDeferred = d; screenWaiting.value = reason
        Notifier.need(h.app, "screen", "화면을 보여 주세요", "$reason\n도움이 필요한 화면을 띄우고 이 알림을 누르세요.", ScreenShare.pendingIntent(h.app))
        pet.tryEmit(PetCue("도와줄 화면을 띄우고 저를 눌러 주세요", PetCue.Kind.SCREEN, jobId))
        val r = withTimeoutOrNull(20 * 60_000L) { d.await() }
        screenWaiting.value = null; screenDeferred = null
        return r
    }

    /** 펫 탭·알림 버튼·이미지 공유에서 호출 */
    fun fulfillScreen(extraText: String? = null) {
        val d = screenDeferred ?: return
        scope.launch {
            delay(500)
            val s = A11yBridge.readScreen(withShot = true)
            val text = when {
                s != null -> "사용자가 보낸 화면(앱 ${s.pkg})의 글자:\n${s.text.take(9000)}" + (s.shot?.let { "\n스크린샷 저장: ${it.name}" } ?: "")
                extraText != null -> extraText
                else -> "사용자가 화면 공유 버튼을 눌렀지만 화면 읽기(접근성)가 꺼져 있어 글자를 읽지 못했다. 사용자에게 상황을 글로 물어보라(task.ask)."
            }
            d.complete(text)
        }
    }

    suspend fun pickFile(jobId: String, hint: String): String? {
        val d = CompletableDeferred<String?>(); pickDeferred = d
        h.launcher.launch(Intent(h.app, com.ht.hjtk.PickActivity::class.java), "파일을 골라 주세요", hint)
        return withTimeoutOrNull(15 * 60_000L) { d.await() }.also { pickDeferred = null }
    }
    fun fulfillPick(text: String?) { pickDeferred?.complete(text) }

    // ── 빠른 대화(작업 아님) ──────────────────────────────────────
    fun quickAsk(convId: String, text: String) {
        busyQuick.update { it + convId }
        scope.launch {
            try {
                val t = text.trim()
                val body = t.substringAfter(' ', "").trim()
                when {
                    (t.startsWith("@제미나이") || t.startsWith("@gemini", true)) && body.isNotEmpty() -> askGemini(convId, body)
                    (t.startsWith("@함께") || t.startsWith("@협업")) && body.isNotEmpty() -> askTogether(convId, body)
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

    /** @제미나이: Gemini에게 직접(키가 있으면 바로, 없으면 앱으로 보내고 복사해 돌아오면 자동 수신) */
    private suspend fun askGemini(convId: String, q: String) {
        val r = if (h.gemini.available()) GeminiApiPort().send(q, convId, 1, true) else GeminiRelayPort(TapBudget(1)).send(q, convId, 1, true)
        if (r.text.isNotBlank()) store.msg(convId, Role.AI, r.text.trim(), "text", mapOf("via" to r.via, "who" to "Gemini"))
        else store.msg(convId, Role.SYSTEM, "Gemini 답을 받지 못했어요. Gemini에서 답을 복사한 뒤 HJTK로 돌아오면 자동으로 받아요. (설정 → AI 연결에서 Gemini API 키를 넣으면 바로 받습니다)", "error", mapOf("via" to "GEMINI"))
    }

    /** 대화용 두 번째 의견: Gemini(키) → 없으면 DeepSeek 별도 대화방 */
    private suspend fun secondOpinion(convId: String, prompt: String): Pair<String, String> {
        if (h.gemini.available()) GeminiApiPort().send(prompt, convId, 1, true).text.takeIf { it.isNotBlank() }?.let { return "Gemini" to it }
        val base = port()
        val key = "c:$convId"
        val r = llmLock.withLock {
            val sess = criticSessions[key]
            if (base is DeepSeekWeb && sess != null) base.ensureSession(sess)
            val rr = base.send(prompt, key, 1, sess == null || base !is DeepSeekWeb)
            if (base is DeepSeekWeb) base.currentUrl()?.takeIf { it.contains("/a/") || it.contains("/chat/") }?.let { criticSessions[key] = it }
            rr
        }
        return "${base.name} 검토창" to com.ht.hjtk.core.stream.AepView.clean(r.text)
    }

    /** @함께: 초안(DeepSeek) → 검토(두 번째 AI) → 종합(DeepSeek). 각 단계가 대화에 협업 기록으로 남는다 */
    private suspend fun askTogether(convId: String, q: String) {
        val first = dsAnswer(convId, q)
        if (first.text.isBlank()) { store.msg(convId, Role.SYSTEM, explain(first.via), "error", mapOf("via" to first.via)); return }
        val draft = com.ht.hjtk.core.stream.AepView.clean(first.text)
        store.msg(convId, Role.SYSTEM, draft.take(600) + if (draft.length > 600) " …" else "", "collab", mapOf("role" to "초안", "who" to port().name))
        val (who, crit) = secondOpinion(convId, "다른 AI가 아래 질문에 답했다. 냉정한 검토자로서 틀린 점·빠진 점·더 나은 관점을 3개 이내로 짚어라. 맞으면 '동의'와 이유 한 줄.\n\n[질문]\n$q\n\n[답]\n${draft.take(7000)}")
        if (crit.isBlank()) { store.msg(convId, Role.AI, draft, "text", mapOf("via" to first.via)); return }
        store.msg(convId, Role.SYSTEM, crit.trim().take(900), "collab", mapOf("role" to "검토", "who" to who))
        val fin = dsAnswer(convId, "검토자($who)의 의견이다. 맞는 지적은 반영하고, 틀린 지적은 이유를 들어 반박하면서 최종 답을 다시 써라. 최종 답만.\n\n$crit")
        val out = com.ht.hjtk.core.stream.AepView.clean(fin.text).ifBlank { draft }
        store.msg(convId, Role.AI, out, "text", mapOf("via" to fin.via, "who" to "${port().name} × $who"))
    }

    /** 이 대화의 DeepSeek 방에서 한 번 묻고 답을 돌려준다(화면에는 아직 올리지 않음) */
    private suspend fun dsAnswer(convId: String, text: String): LlmReply {
        val base = port()
        return withTimeoutOrNull(8 * 60_000L) {
            llmLock.withLock {
                val conv = store.conversations.value.firstOrNull { it.id == convId }
                val fresh = conv?.dsSession == null
                if (base is DeepSeekWeb && !fresh) base.ensureSession(conv?.dsSession)
                streamingConv.value = convId
                val rr = try { base.send(text, convId, 1, fresh) } finally { streamingConv.value = null }
                if (base is DeepSeekWeb) base.currentUrl()?.takeIf { it.contains("/chat/") || it.contains("/a/") }?.let { u -> store.updateConversation(convId) { it.copy(dsSession = u) } }
                rr
            }
        } ?: LlmReply("", "TIMEOUT", false, 0)
    }

    private suspend fun askOnce(convId: String, text: String, waitForLogin: Boolean) {
        val base = port()
        val r = withTimeoutOrNull(8 * 60_000L) {
            llmLock.withLock {
                val conv = store.conversations.value.firstOrNull { it.id == convId }
                val fresh = conv?.dsSession == null
                if (base is DeepSeekWeb && !fresh) base.ensureSession(conv?.dsSession)
                streamingConv.value = convId
                val rr = try { base.send(text, convId, 1, fresh) } finally { streamingConv.value = null }
                if (base is DeepSeekWeb) base.currentUrl()?.takeIf { it.contains("/chat/") || it.contains("/a/") }?.let { u -> store.updateConversation(convId) { it.copy(dsSession = u) } }
                rr
            }
        } ?: LlmReply("", "TIMEOUT", false, 0)
        if (r.text.isNotBlank()) {
            // 이 DeepSeek 대화방이 작업 형식(AEP)으로 답했다면 봉투를 벗겨 사람 말만 남긴다
            val shown = com.ht.hjtk.core.stream.AepView.clean(r.text)
            store.msg(convId, Role.AI, shown, "text", mapOf("via" to r.via) + (if (shown != r.text) mapOf("filtered" to "aep") else emptyMap()))
            return
        }

        // 로그인/확인이 필요하면: 조종석에서 끝내는 순간 같은 질문을 자동으로 이어 보낸다(최대 10분)
        if (waitForLogin && base is DeepSeekWeb && (r.via == "NEEDS_LOGIN" || r.via == "CHALLENGE")) {
            val m = store.msg(convId, Role.SYSTEM, explain(r.via) + " 로그인을 마치면 이 질문을 자동으로 이어서 보낼게요.", "error", mapOf("via" to r.via, "waiting" to "1"))
            val ok = withTimeoutOrNull(10 * 60_000L) {
                while (base.status.value != DeepSeekWeb.Status.READY) { base.refreshState(); delay(1_500) }
                true
            } ?: false
            store.updateMsg(convId, m.id) { it.copy(meta = it.meta + ("waiting" to "0")) }
            if (ok) { store.msg(convId, Role.SYSTEM, "로그인을 확인했어요 · 이어서 보냅니다", "note"); askOnce(convId, text, waitForLogin = false) }
            else store.msg(convId, Role.SYSTEM, "로그인을 10분 기다렸어요. 로그인한 뒤 다시 보내 주세요.", "error", mapOf("via" to r.via))
            return
        }
        store.msg(convId, Role.SYSTEM, explain(r.via), "error", mapOf("via" to r.via))
    }

    /** 사용자가 직접 실행하는 명령(/슬래시, 명령어 사전의 '실행') */
    fun runDirect(convId: String, call: CommandCall) {
        scope.launch {
            val spec = com.ht.hjtk.core.command.CommandCatalog.find(call.cmd)
            if (spec == null) { store.msg(convId, Role.SYSTEM, "없는 명령: ${call.cmd}", "error"); return@launch }
            val tools = AndroidTools(h, "manual", convId, human)
            val t0 = System.currentTimeMillis()
            val r = runCatching { tools.run(call) }.getOrElse { com.ht.hjtk.core.command.CommandResult.fail(it.message ?: "오류") }
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
        android.app.PendingIntent.getBroadcast(ctx, 77, Intent(ctx, ScreenShareReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
}

class ScreenShareReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) { Hjtk.get(context).runner.fulfillScreen() }
}
