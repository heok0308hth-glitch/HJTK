package com.hjtk.app.core.plan

import com.hjtk.app.core.Clock
import com.hjtk.app.core.HostPorts
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.ApprovalRequest
import com.hjtk.app.core.model.CommandRequest
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.Plan
import com.hjtk.app.core.model.PlanOutcome
import com.hjtk.app.core.model.PlanState
import com.hjtk.app.core.model.PlanStep
import com.hjtk.app.core.model.StepOutcome

/**
 * Wraps a step's execution. The orchestrator uses it to take a resource lock
 * for commands that share a carrier, a mail account or the system UI.
 */
interface StepInterceptor {
    suspend fun around(step: PlanStep, block: suspend () -> CommandResult): CommandResult

    object None : StepInterceptor {
        override suspend fun around(step: PlanStep, block: suspend () -> CommandResult): CommandResult = block()
    }
}

sealed class PlanEvent {
    data class Started(val plan: Plan, val resumedSkip: Int = 0) : PlanEvent()
    data class StepStarted(val index: Int, val total: Int, val step: PlanStep) : PlanEvent()
    data class StepFinished(val index: Int, val total: Int, val outcome: StepOutcome) : PlanEvent()
    data class ApprovalNeeded(val request: ApprovalRequest) : PlanEvent()
    data class Finished(val outcome: PlanOutcome) : PlanEvent()
}

/** The single place a human decision enters a running plan. */
interface ApprovalGate {
    /** Suspends until the user taps [실행] (true) or [취소] (false). */
    suspend fun confirm(request: ApprovalRequest): Boolean
}

/**
 * The only door through which a side effect leaves HJTK.
 *
 * Responsibilities kept here on purpose:
 *  - resolve slot references between steps,
 *  - stop for approval before any EXTERNAL/DESTRUCTIVE/SPEND step,
 *  - write every transition to the journal before and after the work,
 *  - checkpoint step results for resume,
 *  - report timing back to the ETA estimator.
 *
 * Step failures stop the plan: a half-executed mail→calendar chain is worse
 * than an honest failure, so the executor never guesses onward.
 */
class Executor(
    private val registry: CommandRegistry,
    private val journal: Journal,
    private val eta: EtaEstimator,
    private val compiler: PlanCompiler,
    private val checkpoints: CheckpointStore,
    private val clock: Clock
) {

    suspend fun run(
        plan: Plan,
        ports: HostPorts,
        gate: ApprovalGate,
        resume: Boolean = false,
        interceptor: StepInterceptor = StepInterceptor.None,
        onEvent: suspend (PlanEvent) -> Unit = {}
    ): PlanOutcome {
        val restored = if (resume) checkpoints.load(plan.id) else emptyMap()
        val doneIds = restored.keys
        val outputs = LinkedHashMap<Int, Pair<String, Map<String, String>>>()
        val outcomes = ArrayList<StepOutcome>()
        val memory = LinkedHashMap<String, StepMemory>()

        // Rebuild outputs of already finished steps from the checkpoint.
        restored.forEach { (stepId, mem) ->
            val idx = plan.steps.indexOfFirst { it.id == stepId }
            if (idx >= 0) outputs[idx + 1] = mem.output to mem.data
        }

        journal.append("plan_started", plan.id, detail = plan.titleKo)
        onEvent(PlanEvent.Started(plan, doneIds.size))
        var state = PlanState.RUNNING
        var failure: String? = null

        for ((i, step) in plan.steps.withIndex()) {
            if (step.id in doneIds) continue
            val spec = registry.spec(step.commandId)
            if (spec == null) {
                state = PlanState.FAILED
                failure = "알 수 없는 명령: ${step.commandId}"
                break
            }
            val entry = registry.get(step.commandId)!!
            val args = compiler.resolveArgs(step, outputs)

            // approval before any outward effect
            if (spec.effect.needsApproval) {
                val request = ApprovalText.build(plan.id, step, spec, args)
                journal.append("approval", plan.id, step.id, step.commandId, Journal.digest(args.toString()), ApprovalText.effectLabel(spec.effect))
                onEvent(PlanEvent.ApprovalNeeded(request))
                val approved = gate.confirm(request)
                if (!approved) {
                    journal.append("plan_finished", plan.id, step.id, step.commandId, detail = "cancelled")
                    val outcome = PlanOutcome(plan.id, PlanState.CANCELLED, outcomes, "사용자가 실행을 취소했습니다.")
                    onEvent(PlanEvent.Finished(outcome))
                    checkpoints.clear(plan.id)
                    return outcome
                }
            }

            onEvent(PlanEvent.StepStarted(i + 1, plan.steps.size, step))
            journal.append("step_started", plan.id, step.id, step.commandId)
            val started = clock.nowMs()
            val result = try {
                interceptor.around(step) { entry.run(CommandRequest(step.commandId, args, plan.titleKo), ports) }
            } catch (t: Throwable) {
                CommandResult.Failed(t.message?.take(180) ?: "실행 중 오류", retryable = true)
            }
            val ended = clock.nowMs()
            val tookMs = ended - started
            eta.observe(step.commandId, tookMs)

            val outcome = when (result) {
                is CommandResult.Ok -> StepOutcome(step.id, step.commandId, true, result.output, result.data, null, started, ended)
                is CommandResult.NeedInput -> StepOutcome(step.id, step.commandId, false, "", emptyMap(), "입력 필요: ${result.prompt}", started, ended)
                is CommandResult.Failed -> StepOutcome(step.id, step.commandId, false, "", emptyMap(), result.reason, started, ended)
            }
            outcomes.add(outcome)
            outputs[i + 1] = outcome.output to outcome.data
            memory[step.id] = StepMemory(outcome.output, outcome.data)
            checkpoints.save(plan.id, plan.titleKo, memory)

            journal.append(
                "step_finished",
                plan.id,
                step.id,
                step.commandId,
                Journal.digest(outcome.output.take(2000) + outcome.data.toString()),
                if (outcome.ok) "ok ${tookMs}ms" else "failed ${tookMs}ms: ${outcome.error?.take(120)}"
            )
            onEvent(PlanEvent.StepFinished(i + 1, plan.steps.size, outcome))

            if (!outcome.ok) {
                state = PlanState.FAILED
                failure = outcome.error ?: "단계 실패"
                break
            }
        }

        journal.append("plan_finished", plan.id, detail = state.name.lowercase())
        val outcome = PlanOutcome(plan.id, if (state == PlanState.RUNNING) PlanState.DONE else state, outcomes, failure)
        onEvent(PlanEvent.Finished(outcome))
        if (outcome.state == PlanState.DONE) checkpoints.clear(plan.id)
        return outcome
    }
}
