package com.ht.ensemble.agent

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.aegis.ApprovalSummaries
import com.ht.ensemble.aegis.Canonical
import com.ht.ensemble.aegis.NormalizedAction
import com.ht.ensemble.aegis.Policy
import com.ht.ensemble.aegis.PolicyDecision
import com.ht.ensemble.aegis.PolicyInput
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandResult
import com.ht.ensemble.command.CommandSpec
import com.ht.ensemble.command.Effect

/** 보고 타이머·ETA가 엔진 종류와 상관없이 읽는 공통 면 */
interface MissionEngine {
    val plan: List<PlanStep>
    val next: String
    val durations: List<Long>
    val progress: Double?
    suspend fun run(): LoopOutcome
}

/** 실제 명령 실행 + 승인(AgentLoop와 같은 규칙: 사전·인자 검증·정책·1회용 승인 토큰) */
class CommandExec(
    private val jobId: String, private val tools: ToolPort, private val human: HumanPort, private val gate: ApprovalGate,
    private val emit: (AgentEvent) -> Unit, private val clock: () -> Long, private val autoAllowE2: Boolean
) {
    suspend fun run(call: CommandCall): CommandResult {
        val spec = tools.spec(call.cmd) ?: return CommandResult.fail("사전에 없는 명령: ${call.cmd}").also { emit(AgentEvent.CmdEnd(clock(), call, null, it, 0)) }
        val missing = spec.params.filter { it.required && call.args[it.name].isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) return CommandResult.fail("필수 인자 누락: ${missing.joinToString()}").also { emit(AgentEvent.CmdEnd(clock(), call, spec, it, 0)) }
        if (spec.effect.needsApproval) approve(call, spec)?.let { return it }
        emit(AgentEvent.CmdStart(clock(), call, spec))
        val t0 = clock()
        val r = try { tools.run(call) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { CommandResult.fail("실행 오류: ${t.message ?: t.javaClass.simpleName}") }
        emit(AgentEvent.CmdEnd(clock(), call, spec, r, clock() - t0))
        r.artifact?.let { emit(AgentEvent.Artifact(clock(), it.name)) }
        return r
    }

    private suspend fun approve(call: CommandCall, spec: CommandSpec): CommandResult? {
        val action = NormalizedAction(call.cmd, call.args.toSortedMap(), call.args["account"])
        val to = call.args["to"].orEmpty()
        val known = to.isBlank() || to.split(',', ';').all { tools.recipientKnown(it.trim()) }
        val text = call.args["body"] ?: call.args["text"] ?: ""
        return when (val d = Policy.decide(PolicyInput(spec.effect, known, Regex("https?://").containsMatchIn(text), autoAllowE2 && spec.effect == Effect.EXTERNAL && known))) {
            is PolicyDecision.Deny -> CommandResult.fail("정책상 거부: ${d.reason}").also { emit(AgentEvent.Approval(clock(), call, false, d.reason)) }
            is PolicyDecision.Auto -> null
            is PolicyDecision.Ask -> {
                val req = ApprovalRequest(jobId, call, spec, action, Canonical.digest(action), ApprovalSummaries.describe(spec, action), d.warning)
                emit(AgentEvent.NeedApproval(clock(), req)); emit(AgentEvent.State(clock(), JobState.PAUSED_APPROVAL, spec.title))
                val ans = human.approve(req)
                emit(AgentEvent.State(clock(), JobState.RUNNING, "승인 응답"))
                when (ans) {
                    is ApprovalAnswer.Rejected -> CommandResult.fail("사용자가 거절: ${ans.why}").also { emit(AgentEvent.Approval(clock(), call, false, ans.why)) }
                    is ApprovalAnswer.Approved -> when (val v = gate.verifyAndConsume(ans.token, NormalizedAction(call.cmd, call.args.toSortedMap(), call.args["account"]), clock())) {
                        is ApprovalGate.Verdict.Ok -> { emit(AgentEvent.Approval(clock(), call, true, "승인")); null }
                        is ApprovalGate.Verdict.Bad -> CommandResult.fail("승인 검증 실패(${v.code})").also { emit(AgentEvent.Approval(clock(), call, false, v.code)) }
                    }
                }
            }
        }
    }
}
