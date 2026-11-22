package com.ht.hjtk.core.agent

import com.ht.hjtk.core.aegis.ApprovalGate
import com.ht.hjtk.core.aegis.ApprovalSummaries
import com.ht.hjtk.core.aegis.Canonical
import com.ht.hjtk.core.aegis.NormalizedAction
import com.ht.hjtk.core.aegis.Policy
import com.ht.hjtk.core.aegis.PolicyDecision
import com.ht.hjtk.core.aegis.PolicyInput
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.core.command.CommandResult
import com.ht.hjtk.core.command.CommandSpec
import com.ht.hjtk.core.command.Effect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class JobControl {
    enum class Signal { RUN, PAUSE, ABORT }
    val signal = MutableStateFlow(Signal.RUN)
    fun pause() { if (signal.value != Signal.ABORT) signal.value = Signal.PAUSE }
    fun resume() { if (signal.value != Signal.ABORT) signal.value = Signal.RUN }
    fun abort() { signal.value = Signal.ABORT }
}

data class LoopConfig(val maxTurns: Int = 40, val maxCmdsPerTurn: Int = 6, val autoAllowE2: Boolean = false, val maxWaitSec: Int = 600)

data class LoopOutcome(val state: JobState, val final: String, val turns: Int, val label: String)

/**
 * 오케스트레이션 루프. 이해는 모델에게, 판단은 코드에게.
 * 모델이 무엇을 말하든, 실행되는 것은 '명령어 사전에 있고 · 인자가 검증되고 · 필요하면 사용자가 허락한' 행동뿐이다.
 */
class AgentLoop(
    private val ctx: AgentContext,
    private val llm: LlmPort,
    private val tools: ToolPort,
    private val human: HumanPort,
    private val gate: ApprovalGate,
    private val control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: LoopConfig = LoopConfig(),
    private val resumeFrom: String? = null
) : MissionEngine {
    private val turnDurations = ArrayList<Long>()
    override var plan: List<PlanStep> = emptyList(); private set
    override var next: String = ""; private set
    override var progress: Double? = null; private set
    override val durations: List<Long> get() = turnDurations

    override suspend fun run(): LoopOutcome {
        emit(AgentEvent.Started(clock(), ctx.goal, llm.name))
        emit(AgentEvent.State(clock(), JobState.RUNNING, "시작"))
        var step = 1
        var prompt = resumeFrom ?: AgentProtocol.kickoff(ctx)
        var newSession = resumeFrom == null
        var repairs = 0
        var emptyRetries = 0
        var lastSay = ""

        while (step <= config.maxTurns) {
            if (!gateControl()) return aborted(step)
            emit(AgentEvent.Sent(clock(), step, prompt.length))
            val reply = try { llm.send(prompt, ctx.jobId, step, newSession) } catch (t: Throwable) {
                emit(AgentEvent.Note(clock(), "${llm.name} 연결 중 문제가 생겼어요(${t.javaClass.simpleName}: ${t.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(90)})"))
                LlmReply("", "error", false, 0)
            }
            newSession = false
            turnDurations += reply.ms
            var replyText = reply.text
            if (replyText.isBlank()) {
                if (++emptyRetries > 3) return finish(JobState.FAILED, "모델 응답을 여러 번 받지 못해 멈췄습니다. 연결 상태를 확인한 뒤 재개하세요.", step, "응답 없음")
                emit(AgentEvent.State(clock(), JobState.PAUSED_INPUT, "응답 없음"))
                val ans = human.ask(ctx.jobId, "${llm.name}에서 답을 받지 못했어요. 답을 복사해 붙여넣거나 공유해 주시면 이어갑니다.", listOf("다시 시도", "중단"))
                emit(AgentEvent.Answered(clock(), ans))
                emit(AgentEvent.State(clock(), JobState.RUNNING, "재개"))
                when {
                    ans == null || ans == "중단" -> return finish(JobState.FAILED, "응답 없음으로 중단", step, "응답 없음")
                    ans == "다시 시도" -> continue
                    else -> replyText = ans   // L7: 사용자가 붙여넣은 답
                }
            }
            emptyRetries = 0
            val turn = AgentProtocol.parseTurn(replyText, ctx.jobId)
            emit(AgentEvent.Replied(clock(), step, turn, reply.via, reply.ms))
            if (turn.say.isNotBlank()) lastSay = turn.say
            if (turn.plan.isNotEmpty() || turn.next.isNotBlank() || turn.progress != null) {
                if (turn.plan.isNotEmpty()) plan = turn.plan
                if (turn.next.isNotBlank()) next = turn.next
                turn.progress?.let { progress = it }
                emit(AgentEvent.Plan(clock(), plan, next, progress))
            }

            if (!turn.executable && turn.cmds.isNotEmpty()) {
                // 깊은 복구(5단계 이하)로 읽힌 명령은 실행하지 않는다. 형식 재요청 1회.
                if (repairs < 2) { repairs++; step++; prompt = AgentProtocol.repair(ctx.jobId, step, "형식 복구 단계 ${turn.parseLevel}"); continue }
            }
            if (turn.parseLevel >= 6 && !turn.done) {
                if (repairs < 2) { repairs++; step++; prompt = AgentProtocol.repair(ctx.jobId, step, "JSON 블록 없음"); continue }
                // 형식을 끝내 못 지키면 산문을 결과로 확정한다(라벨 부착).
                return finish(JobState.COMPLETED, turn.say, step, "형식 미준수 · 산문 결과")
            }
            repairs = 0

            if (turn.done && turn.cmds.isEmpty()) {
                return finish(JobState.COMPLETED, turn.final ?: turn.say.ifBlank { lastSay }, step, "완료")
            }

            val results = ArrayList<Pair<CommandCall, CommandResult>>()
            var extra: String? = null
            for (call in turn.cmds.take(config.maxCmdsPerTurn)) {
                if (!gateControl()) return aborted(step)
                val r = execute(call)
                if (r === DONE_SIGNAL) return finish(JobState.COMPLETED, call.arg("summary", turn.final ?: lastSay), step, "완료")
                results += call to r
            }
            if (turn.cmds.size > config.maxCmdsPerTurn) extra = "명령은 한 번에 ${config.maxCmdsPerTurn}개까지만 실행했다. 나머지는 다시 요청하라."
            if (turn.done) return finish(JobState.COMPLETED, turn.final ?: lastSay, step, "완료")
            step++
            prompt = AgentProtocol.observation(ctx.jobId, step, results, extra)
        }
        return finish(JobState.COMPLETED, lastSay + "\n\n(최대 단계 ${config.maxTurns}에 도달해 정리했습니다)", step, "단계 상한")
    }

    private suspend fun gateControl(): Boolean {
        when (control.signal.value) {
            JobControl.Signal.ABORT -> return false
            JobControl.Signal.PAUSE -> {
                emit(AgentEvent.State(clock(), JobState.PAUSED, "사용자 일시정지"))
                val s = control.signal.first { it != JobControl.Signal.PAUSE }
                if (s == JobControl.Signal.ABORT) return false
                emit(AgentEvent.State(clock(), JobState.RUNNING, "재개"))
            }
            else -> {}
        }
        return true
    }

    private suspend fun execute(call: CommandCall): CommandResult {
        // 메타 명령은 루프가 직접 처리한다
        when (call.cmd) {
            "task.done" -> return DONE_SIGNAL
            "task.plan" -> {
                plan = call.arg("steps").lines().filter { it.isNotBlank() }.map { l ->
                    val parts = l.split('|'); PlanStep(parts[0].trim().trimStart('-', ' ', '*'), parts.getOrNull(1)?.trim()?.filter { it.isDigit() }?.toIntOrNull() ?: 60, StepStatus.TODO)
                }
                emit(AgentEvent.Plan(clock(), plan, next, progress))
                return CommandResult.ok("계획 갱신: ${plan.size}단계")
            }
            "task.report" -> { emit(AgentEvent.Report(clock(), call.arg("summary"))); return CommandResult.ok("사용자에게 보고함") }
            "task.note" -> { emit(AgentEvent.Note(clock(), call.arg("text"))); return CommandResult.ok("기록함") }
            "task.wait" -> {
                val s = call.int("seconds", 30).coerceIn(1, config.maxWaitSec)
                emit(AgentEvent.Note(clock(), "${s}초 대기"))
                delay(s * 1000L); return CommandResult.ok("${s}초 기다렸다")
            }
            "task.ask" -> {
                val q = call.arg("question")
                emit(AgentEvent.Asked(clock(), q))
                emit(AgentEvent.State(clock(), JobState.PAUSED_INPUT, "질문"))
                val a = human.ask(ctx.jobId, q, call.arg("choices").split('|').map { it.trim() }.filter { it.isNotEmpty() })
                emit(AgentEvent.Answered(clock(), a))
                emit(AgentEvent.State(clock(), JobState.RUNNING, "답변 받음"))
                return if (a == null) CommandResult.fail("사용자가 답하지 않았다. 합리적인 기본값으로 진행하되 그 사실을 final에 적어라.") else CommandResult.ok("사용자 답: $a")
            }
            "ui.takeover" -> {
                emit(AgentEvent.State(clock(), JobState.PAUSED_INPUT, "직접 조작 요청"))
                val a = human.takeover(ctx.jobId, call.arg("what"), call.args["url"])
                emit(AgentEvent.State(clock(), JobState.RUNNING, "사용자 조작 끝"))
                return if (a == null) CommandResult.fail("사용자가 하지 않았다") else CommandResult.ok("사용자가 완료함: $a")
            }
        }

        val spec = tools.spec(call.cmd) ?: return CommandResult.fail("'${call.cmd}'는 사전에 없는 명령이다. 사전의 명령만 써라.").also {
            emit(AgentEvent.CmdEnd(clock(), call, null, it, 0))
        }
        val missing = spec.params.filter { it.required && call.args[it.name].isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) {
            val r = CommandResult.fail("필수 인자 누락: ${missing.joinToString()}")
            emit(AgentEvent.CmdEnd(clock(), call, spec, r, 0)); return r
        }

        if (spec.effect.needsApproval) {
            val verdict = approve(call, spec)
            if (verdict != null) return verdict
        }

        emit(AgentEvent.CmdStart(clock(), call, spec))
        val t0 = clock()
        val r = try { tools.run(call) } catch (t: Throwable) { CommandResult.fail("실행 오류: ${t.message ?: t.javaClass.simpleName}") }
        emit(AgentEvent.CmdEnd(clock(), call, spec, r, clock() - t0))
        r.artifact?.let { emit(AgentEvent.Artifact(clock(), it.name)) }
        return r
    }

    /** null = 승인됨(계속 실행), 아니면 거절 결과 */
    private suspend fun approve(call: CommandCall, spec: CommandSpec): CommandResult? {
        val action = NormalizedAction(call.cmd, call.args.toSortedMap(), call.args["account"])
        val to = call.args["to"].orEmpty()
        val known = to.isBlank() || to.split(',', ';').all { tools.recipientKnown(it.trim()) }
        val text = (call.args["body"] ?: call.args["text"] ?: "")
        val decision = Policy.decide(PolicyInput(spec.effect, known, Regex("https?://").containsMatchIn(text), config.autoAllowE2 && spec.effect == Effect.EXTERNAL && known))
        when (decision) {
            is PolicyDecision.Deny -> return CommandResult.fail("정책상 거부: ${decision.reason}").also { emit(AgentEvent.Approval(clock(), call, false, decision.reason)) }
            is PolicyDecision.Auto -> return null
            is PolicyDecision.Ask -> {
                val digest = Canonical.digest(action)
                val req = ApprovalRequest(ctx.jobId, call, spec, action, digest, ApprovalSummaries.describe(spec, action), decision.warning)
                emit(AgentEvent.NeedApproval(clock(), req))
                emit(AgentEvent.State(clock(), JobState.PAUSED_APPROVAL, spec.title))
                val ans = human.approve(req)
                emit(AgentEvent.State(clock(), JobState.RUNNING, "승인 응답"))
                return when (ans) {
                    is ApprovalAnswer.Rejected -> CommandResult.fail("사용자가 거절했다: ${ans.why}. 이 행동은 하지 말고 다른 방법을 찾거나 물어봐라.").also { emit(AgentEvent.Approval(clock(), call, false, ans.why)) }
                    is ApprovalAnswer.Approved -> {
                        // 실행 직전 재정규화 → 다이제스트 대조 → 1회 소비
                        val again = NormalizedAction(call.cmd, call.args.toSortedMap(), call.args["account"])
                        when (val v = gate.verifyAndConsume(ans.token, again, clock())) {
                            is ApprovalGate.Verdict.Ok -> { emit(AgentEvent.Approval(clock(), call, true, "승인")); null }
                            is ApprovalGate.Verdict.Bad -> CommandResult.fail("승인 검증 실패(${v.code}) — 실행하지 않았다").also { emit(AgentEvent.Approval(clock(), call, false, v.code)) }
                        }
                    }
                }
            }
        }
    }

    private fun finish(state: JobState, final: String, turns: Int, label: String): LoopOutcome {
        emit(AgentEvent.Done(clock(), final, label))
        emit(AgentEvent.State(clock(), state, label))
        return LoopOutcome(state, final, turns, label)
    }

    private fun aborted(turns: Int): LoopOutcome {
        emit(AgentEvent.State(clock(), JobState.ABORTED, "사용자 중지"))
        return LoopOutcome(JobState.ABORTED, "사용자가 중지했습니다.", turns, "중지")
    }

    companion object { private val DONE_SIGNAL = CommandResult(true, "__done__") }
}
