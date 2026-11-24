package com.ht.ensemble.agent

import com.ht.ensemble.aegis.NormalizedAction
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandResult
import com.ht.ensemble.command.CommandSpec
import kotlinx.coroutines.flow.MutableStateFlow

enum class StepStatus { TODO, DOING, DONE }
data class PlanStep(val title: String, val etaSec: Int, val status: StepStatus)

data class AgentTurn(
    val say: String,
    val plan: List<PlanStep>,
    val next: String,
    val progress: Double?,
    val cmds: List<CommandCall>,
    val done: Boolean,
    val final: String?,
    val parseLevel: Int,
    val executable: Boolean
)

enum class JobState { CREATED, RUNNING, PAUSED_APPROVAL, PAUSED_INPUT, PAUSED, COMPLETED, FAILED, ABORTED;
    val terminal get() = this == COMPLETED || this == FAILED || this == ABORTED
}

data class LlmReply(val text: String, val via: String, val complete: Boolean, val ms: Long)

/** 모델에게 말을 거는 유일한 통로. 구현은 DeepSeek WebView / API / 앱 경로 */
interface LlmPort {
    val name: String
    suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply
}

interface ToolPort {
    fun spec(id: String): CommandSpec?
    suspend fun run(call: CommandCall): CommandResult
    fun recipientKnown(address: String): Boolean = true
}

data class ApprovalRequest(
    val jobId: String,
    val call: CommandCall,
    val spec: CommandSpec,
    val action: NormalizedAction,
    val digest: String,
    val summary: String,
    val warning: String?
)

sealed interface ApprovalAnswer {
    data class Approved(val token: com.ht.ensemble.aegis.ApprovalGate.Token) : ApprovalAnswer
    data class Rejected(val why: String) : ApprovalAnswer
}

interface HumanPort {
    suspend fun approve(req: ApprovalRequest): ApprovalAnswer
    suspend fun ask(jobId: String, question: String, choices: List<String>): String?
    suspend fun takeover(jobId: String, what: String, url: String?): String?
}

sealed interface AgentEvent {
    val at: Long
    data class Started(override val at: Long, val goal: String, val via: String) : AgentEvent
    data class Sent(override val at: Long, val step: Int, val chars: Int) : AgentEvent
    data class Replied(override val at: Long, val step: Int, val turn: AgentTurn, val via: String, val ms: Long) : AgentEvent
    data class CmdStart(override val at: Long, val call: CommandCall, val spec: CommandSpec) : AgentEvent
    data class CmdEnd(override val at: Long, val call: CommandCall, val spec: CommandSpec?, val result: CommandResult, val ms: Long) : AgentEvent
    data class NeedApproval(override val at: Long, val req: ApprovalRequest) : AgentEvent
    data class Approval(override val at: Long, val call: CommandCall, val approved: Boolean, val note: String) : AgentEvent
    data class Asked(override val at: Long, val question: String) : AgentEvent
    data class Answered(override val at: Long, val answer: String?) : AgentEvent
    data class Plan(override val at: Long, val steps: List<PlanStep>, val next: String, val progress: Double?) : AgentEvent
    data class Report(override val at: Long, val text: String) : AgentEvent
    data class Note(override val at: Long, val text: String) : AgentEvent
    data class State(override val at: Long, val state: JobState, val why: String) : AgentEvent
    data class Artifact(override val at: Long, val name: String) : AgentEvent
    data class Done(override val at: Long, val final: String, val label: String) : AgentEvent
    /** AI끼리 협업한 기록: 역할(구상·선택·검증·수정·대체) · 누가 · 무엇을 */
    data class Collab(override val at: Long, val role: String, val who: String, val text: String) : AgentEvent
}

/** 작업 하나의 맥락: 목표·현재 시각·의뢰인에 대해 알려진 것·지침 */
data class AgentContext(
    val jobId: String,
    val goal: String,
    val nowText: String,
    val memories: List<String> = emptyList(),
    val hubSites: List<String> = emptyList(),
    val hubApps: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val activeSkillGuide: String? = null,
    val userName: String? = null,
    val disabledCommands: Set<String> = emptySet()
)

/** 일시정지·재개·중지 신호. 엔진은 새 요청 전에 이 값을 본다 */
class JobControl {
    enum class Signal { RUN, PAUSE, ABORT }
    val signal = MutableStateFlow(Signal.RUN)
    fun pause() { if (signal.value != Signal.ABORT) signal.value = Signal.PAUSE }
    fun resume() { if (signal.value != Signal.ABORT) signal.value = Signal.RUN }
    fun abort() { signal.value = Signal.ABORT }
}

/** 엔진이 끝났을 때의 결과 */
data class LoopOutcome(val state: JobState, val final: String, val turns: Int, val label: String)
