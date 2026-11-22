package com.hjtk.app.core.orchestrator

import com.hjtk.app.core.Clock
import com.hjtk.app.core.HostPorts
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.ApprovalRequest
import com.hjtk.app.core.model.ChatItem
import com.hjtk.app.core.model.NoticeTone
import com.hjtk.app.core.model.Plan
import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.model.PlanStep
import com.hjtk.app.core.model.ProgressReport
import com.hjtk.app.core.model.Skill
import com.hjtk.app.core.model.SkillStep
import com.hjtk.app.core.model.StepOutcome
import com.hjtk.app.core.nlu.NluRouter
import com.hjtk.app.core.nlu.Route
import com.hjtk.app.core.plan.ApprovalGate
import com.hjtk.app.core.plan.CheckpointStore
import com.hjtk.app.core.plan.CompileResult
import com.hjtk.app.core.plan.Executor
import com.hjtk.app.core.plan.Journal
import com.hjtk.app.core.plan.OpenPlan
import com.hjtk.app.core.plan.PlanCompiler
import com.hjtk.app.core.plan.PlanEvent
import com.hjtk.app.core.plan.ResourceGuard
import com.hjtk.app.core.session.SessionStore
import com.hjtk.app.core.skill.PipelineStore
import com.hjtk.app.core.util.TimeFmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Public, immutable view of one running (or queued) plan. */
data class JobState(
    val plan: Plan,
    val state: PlanState,
    val doneSteps: Int = 0,
    val totalSteps: Int = 0,
    val activeStep: PlanStep? = null,
    val pendingApproval: ApprovalRequest? = null,
    val etaSecondsLeft: Int = 0,
    val outcomes: List<StepOutcome> = emptyList(),
    val queued: Boolean = false
) {
    val progress: Float get() = if (totalSteps <= 0) 0f else doneSteps.toFloat() / totalSteps
}

data class EngineState(
    val items: List<ChatItem> = emptyList(),
    val jobs: List<JobState> = emptyList(),
    val lastReport: ProgressReport? = null,
    val openPlan: OpenPlan? = null
) {
    val busy: Boolean get() = jobs.any { !it.queued }
    val primary: JobState? get() = jobs.lastOrNull { !it.queued } ?: jobs.lastOrNull()
    val statusLine: String
        get() {
            val running = jobs.count { !it.queued }
            val queued = jobs.count { it.queued }
            val approval = jobs.firstOrNull { it.pendingApproval != null }
            return when {
                approval != null -> "승인 대기: ${approval.pendingApproval!!.titleKo}"
                running > 0 -> "실행 중 ${running}개" + if (queued > 0) " · 대기 $queued" else ""
                queued > 0 -> "대기 ${queued}개"
                openPlan != null -> "이어서 실행할 작업 있음"
                else -> "대기 중"
            }
        }
    fun job(planId: String): JobState? = jobs.firstOrNull { it.plan.id == planId }
}

/**
 * Runs up to [maxConcurrent] independent plans at the same time.
 *
 * Complexity that would be wrong to hide lives here:
 *  - a job that needs the same model session, the mail transport or the system
 *    UI takes a [ResourceGuard] lock per step, so two jobs interleave freely
 *    but never collide;
 *  - a fourth request waits in a FIFO queue and starts on its own;
 *  - each job owns its approval deferred, cancel handle, reporter and
 *    checkpoint, so stopping one never touches another;
 *  - chat remains a single timeline; every card is keyed by plan id.
 */
class Orchestrator(
    private val registry: CommandRegistry,
    private val router: NluRouter,
    private val compiler: PlanCompiler,
    private val executor: Executor,
    private val journal: Journal,
    private val checkpoints: CheckpointStore,
    private val archive: com.hjtk.app.core.plan.PlanArchive,
    private val sessions: SessionStore,
    private val pipelines: PipelineStore,
    private val clock: Clock,
    private val reportMinutes: () -> Int,
    private val reportsEnabled: () -> Boolean = { true },
    val maxConcurrent: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) : com.hjtk.app.core.PlanGateway {

    /**
     * A running plan. The job reference is filled right after the coroutine is
     * created but *before* it is started, so a plan that finishes instantly can
     * never leave a stale entry in [handles] (that used to block the queue).
     */
    private class Handle(val plan: Plan, var resume: Boolean) {
        var job: Job? = null
        var approval: CompletableDeferred<Boolean>? = null
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private val guard = ResourceGuard()
    private val handles = ConcurrentHashMap<String, Handle>()
    private val queue = ArrayDeque<Pair<Plan, Boolean>>()
    private var ports: HostPorts? = null

    /** Set by tests so no real waiting happens. */
    var reportIntervalOverrideMs: Long? = null

    fun attachPorts(hostPorts: HostPorts) {
        ports = hostPorts
        _state.value = _state.value.copy(openPlan = journal.openPlan())
    }

    // ---------------------------------------------------------------- input

    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        addItem(ChatItem.UserText(text = trimmed, at = clock.nowMs()))
        sessions.append("user", trimmed)
        when (val route = router.route(trimmed)) {
            is Route.Chat -> assistant(route.reply)
            is Route.Command -> {
                if (route.confidence < 0.7) {
                    assistant("이렇게 이해했습니다: ${registry.labelOf(route.request.commandId)}")
                }
                runDeclaration(route.request.commandId, route.request.args, registry.labelOf(route.request.commandId))
            }
            is Route.PlanRoute -> runSteps(route.steps, route.title)
            is Route.SkillRoute -> runSkill(route.skill)
            is Route.PipelineRoute -> runSteps(route.pipeline.steps, route.pipeline.name)
            is Route.Unknown -> {
                val lines = if (route.suggestions.isEmpty()) route.message
                else route.message + "\n" + route.suggestions.joinToString("\n") { "· ${it.labelKo} (`/${it.id}`)" }
                assistant(lines)
            }
        }
    }

    /** Runs one command with explicit arguments, bypassing the text router. */
    fun runExplicit(commandId: String, args: Map<String, String> = emptyMap()) {
        runDeclaration(commandId, args, registry.labelOf(commandId))
    }

    fun runPipeline(pipelineId: String, variables: Map<String, String> = emptyMap()) {
        val p = pipelines.byId(pipelineId) ?: return
        runSteps(p.steps, p.name, variables)
    }

    fun runSkill(skill: Skill) = runSteps(skill.steps, skill.name)

    // ------------------------------------------------------------- controls

    fun approve(planId: String) {
        handles[planId]?.approval?.complete(true)
    }

    fun reject(planId: String) {
        handles[planId]?.approval?.complete(false)
    }

    /** Stops one job, or every job when [planId] is null. */
    fun cancel(planId: String? = null) {
        val targets = if (planId == null) handles.keys.toList() else listOf(planId)
        for (id in targets) {
            val handle = handles[id] ?: continue
            handles.remove(id)
            handle.approval?.complete(false)
            handle.job?.cancel()
            journal.append("plan_finished", handle.plan.id, detail = "stopped")
            archive.clear(handle.plan.id)
            removeJob(handle.plan.id, PlanState.CANCELLED)
            assistant("‘${handle.plan.titleKo}’ 작업을 중단했습니다. 아직 실행되지 않은 단계는 실행되지 않습니다.")
        }
        if (planId == null) {
            queue.clear()
            _state.value = _state.value.copy(jobs = emptyList())
        }
        pump()
    }

    fun resume(planId: String) {
        val handle = handles[planId] ?: return
        if (handle.resume) return
        // Resume is handled inside the running executor through its checkpoint;
        // exposed here for symmetry with the queue API.
        handle.resume = true
    }

    /**
     * Rebuilds the interrupted plan from disk and runs it again from its
     * checkpoint. A step that was mid-execution when the process died is
     * re-approved before it runs, because its effect may or may not have landed.
     */
    fun resumeOpenPlan() {
        val open = journal.openPlan()
        _state.value = _state.value.copy(openPlan = open)
        if (open == null) {
            assistant("이어서 실행할 작업이 없습니다.")
            return
        }
        val plan = archive.load(open.planId)
        if (plan == null) {
            assistant(
                "끊긴 작업 ‘${open.title}’의 정의를 찾지 못했습니다(앱 데이터가 정리되었을 수 있습니다). " +
                    "같은 요청을 다시 보내면 처음부터 실행합니다."
            )
            return
        }
        val interrupted = plan.steps.firstOrNull { it.id !in open.completedSteps }
        assistant(
            "‘${plan.titleKo}’을(를) 이어서 실행합니다. " +
                (interrupted?.let { "'${it.titleKo}' 단계부터입니다." } ?: "") +
                " 외부로 나가는 단계는 다시 확인을 받습니다."
        )
        enqueue(plan, resume = true)
    }

    // ------------------------------------------------------------- internals

    private fun runSteps(steps: List<SkillStep>, title: String, variables: Map<String, String> = emptyMap()) {
        when (val result = compiler.compile(title, steps, variables)) {
            is CompileResult.Success -> enqueue(result.plan, resume = false)
            is CompileResult.NeedsArgs -> assistant("${result.prompt}\n\n필요한 값을 알려 주시면 바로 실행하겠습니다.")
            is CompileResult.Invalid -> assistant("계획을 만들지 못했습니다: ${result.reason}")
        }
    }

    private fun runDeclaration(commandId: String, args: Map<String, String>, title: String) {
        runSteps(listOf(SkillStep(commandId, args)), title)
    }

    private fun enqueue(plan: Plan, resume: Boolean) {
        addItem(ChatItem.PlanCard(plan = plan, at = clock.nowMs()))
        val running = handles.size
        if (running >= maxConcurrent) {
            queue.addLast(plan to resume)
            upsertJob(
                JobState(
                    plan = plan,
                    state = PlanState.DRAFT,
                    totalSteps = plan.steps.size,
                    etaSecondsLeft = plan.estimatedSeconds,
                    queued = true
                )
            )
            assistant("‘${plan.titleKo}’을(를) 대기열에 넣었습니다. 앞선 작업이 끝나면 자동으로 시작합니다.")
            return
        }
        start(plan, resume)
    }

    private fun pump() {
        while (handles.size < maxConcurrent && queue.isNotEmpty()) {
            val (plan, resume) = queue.removeFirst()
            start(plan, resume)
        }
    }

    private fun start(plan: Plan, resume: Boolean) {
        val host = ports ?: return
        val restored = if (resume) checkpoints.load(plan.id) else emptyMap()
        upsertJob(
            JobState(
                plan = plan,
                state = PlanState.RUNNING,
                doneSteps = restored.size,
                totalSteps = plan.steps.size,
                etaSecondsLeft = plan.estimatedSeconds,
                queued = false
            )
        )

        val gate = object : ApprovalGate {
            override suspend fun confirm(request: ApprovalRequest): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                handles[plan.id]?.approval = deferred
                updateJob(plan.id) { it.copy(pendingApproval = request, activeStep = null) }
                addItem(ChatItem.ApprovalCard(request = request, at = clock.nowMs()))
                val decision = deferred.await()
                updateJob(plan.id) { it.copy(pendingApproval = null) }
                return decision
            }
        }

        archive.save(plan)
        val handle = Handle(plan, resume)
        handles[plan.id] = handle
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val reporter = launch { reportLoop(plan.id) }
            try {
                val outcome = executor.run(
                    plan = plan,
                    ports = host,
                    gate = gate,
                    resume = resume,
                    interceptor = object : com.hjtk.app.core.plan.StepInterceptor {
                        override suspend fun around(
                            step: PlanStep,
                            block: suspend () -> com.hjtk.app.core.model.CommandResult
                        ): com.hjtk.app.core.model.CommandResult =
                            guard.withResource(step.commandId) { block() }
                    }
                ) { event ->
                    when (event) {
                        is PlanEvent.Started -> Unit
                        is PlanEvent.StepStarted -> updateJob(plan.id) {
                            it.copy(activeStep = event.step, totalSteps = event.total, state = PlanState.RUNNING)
                        }
                        is PlanEvent.StepFinished -> {
                            val details = event.outcome.output.ifBlank { event.outcome.error ?: "" }.trim()
                            if (details.isNotBlank()) {
                                addItem(
                                    ChatItem.AssistantText(
                                        text = details,
                                        commandId = event.outcome.commandId,
                                        at = clock.nowMs()
                                    )
                                )
                            }
                            val remaining = plan.steps.drop(event.index).sumOf { it.estimatedSeconds }
                            updateJob(plan.id) {
                                it.copy(
                                    doneSteps = event.index,
                                    etaSecondsLeft = remaining,
                                    outcomes = it.outcomes + event.outcome
                                )
                            }
                        }
                        is PlanEvent.ApprovalNeeded -> Unit
                        is PlanEvent.Finished -> {
                            when (event.outcome.state) {
                                PlanState.DONE -> {
                                    assistant("‘${plan.titleKo}’ 작업을 마쳤습니다. ${plan.steps.size}단계.")
                                    archive.clear(plan.id)
                                    removeJob(plan.id, PlanState.DONE)
                                }
                                PlanState.CANCELLED -> {
                                    archive.clear(plan.id)
                                    removeJob(plan.id, PlanState.CANCELLED)
                                }
                                else -> {
                                    notice(
                                        "‘${plan.titleKo}’ 작업을 끝내지 못했습니다",
                                        event.outcome.failure ?: "알 수 없는 이유",
                                        NoticeTone.ERROR
                                    )
                                    // A failed job is reported in the conversation and
                                    // removed from the running list: it is not running.
                                    archive.clear(plan.id)
                                    removeJob(plan.id, PlanState.FAILED)
                                }
                            }
                        }
                    }
                }
                if (outcome.state == PlanState.FAILED) {
                    _state.value = _state.value.copy(openPlan = journal.openPlan())
                }
            } catch (ce: CancellationException) {
                // cancel() already updated the UI and wrote the journal entry.
            } finally {
                reporter.cancel()
                handles.remove(plan.id)
                updateJob(plan.id) { it.copy(activeStep = null, pendingApproval = null) }
                if (_state.value.jobs.none { it.plan.id == plan.id && !it.queued }) {
                    _state.value = _state.value.copy(jobs = _state.value.jobs.filterNot { it.plan.id == plan.id && it.state == PlanState.DONE })
                }
                pump()
            }
        }
        handle.job = job
        job.start()
    }

    private suspend fun reportLoop(planId: String) {
        if (!reportsEnabled()) return
        val interval = reportIntervalOverrideMs ?: (reportMinutes().coerceIn(1, 60) * 60_000L)
        while (scope.isActive) {
            delay(interval)
            if (!reportsEnabled()) break
            val job = _state.value.job(planId) ?: break
            if (job.state != PlanState.RUNNING && job.state != PlanState.PAUSED) break
            val report = ProgressReport(
                planId = job.plan.id,
                planTitle = job.plan.titleKo,
                doneSteps = job.doneSteps,
                totalSteps = job.totalSteps,
                state = job.state,
                headline = job.activeStep?.let { "지금: ${it.titleKo}" } ?: "준비 중",
                lines = buildList {
                    add("${job.doneSteps}/${job.totalSteps}단계 완료")
                    job.activeStep?.let { add("현재: ${it.titleKo}") }
                    add("남은 예상 ${TimeFmt.duration(job.etaSecondsLeft)}")
                    add("실행 중 작업 ${_state.value.jobs.count { !it.queued }}개")
                },
                etaSecondsLeft = job.etaSecondsLeft,
                at = clock.nowMs()
            )
            addItem(ChatItem.ReportCard(report = report, at = clock.nowMs()))
            _state.value = _state.value.copy(lastReport = report)
        }
    }

    // ------------------------------------------------------------ job state

    private fun upsertJob(job: JobState) {
        val current = _state.value.jobs.filterNot { it.plan.id == job.plan.id }
        _state.value = _state.value.copy(jobs = current + job)
    }

    private fun updateJob(planId: String, transform: (JobState) -> JobState) {
        val jobs = _state.value.jobs.map { if (it.plan.id == planId) transform(it) else it }
        _state.value = _state.value.copy(jobs = jobs)
    }

    private fun removeJob(planId: String, state: PlanState) {
        val remaining = _state.value.jobs.filterNot { it.plan.id == planId }
        _state.value = _state.value.copy(jobs = remaining)
        handles.remove(planId)
    }

    // ------------------------------------------------------------- chat items

    private fun assistant(text: String, actions: List<com.hjtk.app.core.model.ResultAction> = emptyList()) {
        addItem(ChatItem.AssistantText(text = text, actions = actions, at = clock.nowMs()))
        sessions.append("assistant", text)
    }

    private fun notice(title: String, body: String, tone: NoticeTone) {
        addItem(ChatItem.NoticeCard(title = title, body = body, tone = tone, at = clock.nowMs()))
        sessions.append("system", "$title — $body")
    }

    private fun addItem(item: ChatItem) {
        _state.value = _state.value.copy(items = _state.value.items + item)
    }

    /** Public entry for other components (capture services, background work). */
    fun postNotice(title: String, body: String, tone: NoticeTone = NoticeTone.NEUTRAL) {
        notice(title, body, tone)
    }

    fun restoreConversation() {
        val record = sessions.current()
        val items = record.messages.mapNotNull { m ->
            when (m.role) {
                "user" -> ChatItem.UserText(id = m.id, text = m.text, at = m.at)
                "assistant" -> ChatItem.AssistantText(id = m.id, text = m.text, at = m.at)
                else -> ChatItem.NoticeCard(id = m.id, title = "시스템", body = m.text, at = m.at)
            }
        }
        _state.value = _state.value.copy(items = items, openPlan = journal.openPlan())
    }

    fun newSession() {
        sessions.create()
        _state.value = EngineState(openPlan = journal.openPlan())
    }

    // ----------------------------------------------------------- PlanGateway

    override suspend fun submit(steps: List<SkillStep>, title: String): String {
        runSteps(steps, title)
        return "계획을 시작합니다."
    }

    override fun status(): String {
        val s = _state.value
        if (s.jobs.isEmpty()) return if (s.openPlan != null) "이어서 실행할 계획이 있습니다: ${s.openPlan.title}" else "대기 중입니다."
        return s.jobs.joinToString("\n") { j ->
            val label = when {
                j.queued -> "대기"
                j.pendingApproval != null -> "승인 대기"
                else -> "실행 중"
            }
            "· ${j.plan.titleKo} — $label ${j.doneSteps}/${j.totalSteps} (남은 예상 ${TimeFmt.duration(j.etaSecondsLeft)})"
        }
    }

    override fun stop(): Boolean {
        if (_state.value.jobs.none { !it.queued }) return false
        cancel(null)
        return true
    }

    override fun isRunning(): Boolean = _state.value.jobs.any { !it.queued }
}
