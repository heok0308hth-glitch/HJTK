package com.hjtk.app.core.model

import kotlinx.serialization.Serializable

import java.util.UUID

/**
 * How much damage a command can do if it is run with the wrong arguments.
 *
 * The level decides the approval the executor demands *before* the effect
 * leaves the app. It is deliberately coarse: users understand five buckets,
 * not a policy language.
 */
@Serializable
enum class EffectClass(val level: Int) {
    /** Reads local state or the network. Nothing outside changes. */
    READ(0),

    /** Writes only inside HJTK's own storage (notes, memory, exports). */
    LOCAL_WRITE(1),

    /** Leaves the app under the user's name: mail, share, dial, calendar. */
    EXTERNAL(2),

    /** Spends money or a metered quota (paid API calls on the user's key). */
    SPEND(3),

    /** Destroys or overwrites something that cannot be recovered. */
    DESTRUCTIVE(4);

    val needsApproval: Boolean get() = level >= 2
}

@Serializable
enum class CommandCategory(val labelKo: String) {
    SEARCH("검색"),
    FILES("파일"),
    MAIL("메일"),
    CALENDAR("일정"),
    APPS("앱"),
    PIPELINE("파이프라인"),
    SKILL("스킬"),
    MEMORY("메모리"),
    CLIPBOARD("클립보드"),
    NOTIFY("알림"),
    DEVICE("디바이스"),
    AI("AI"),
    HUB("허브"),
    SYSTEM("시스템")
}

/** A slot a command accepts. `name` is what the NLU extractor writes. */
@Serializable
data class ParamSpec(
    val name: String,
    val labelKo: String,
    val required: Boolean,
    val example: String = ""
)

/** Everything the router and the UI need to know about one command. */
@Serializable
data class CommandSpec(
    val id: String,
    val labelKo: String,
    val category: CommandCategory,
    val effect: EffectClass,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
    /** Words that make the router pick this command. */
    val keywords: List<String> = emptyList(),
    /** Ordered regex candidates; named groups become arguments. */
    val patterns: List<String> = emptyList(),
    val builtIn: Boolean = true
) {
    val primaryParam: ParamSpec? get() = params.firstOrNull { it.required }
}

@Serializable
data class CommandRequest(
    val commandId: String,
    val args: Map<String, String> = emptyMap(),
    val raw: String = "",
    /** Set by the UI when the user typed the command explicitly from a card. */
    val explicit: Boolean = false
) {
    fun arg(name: String): String? = args[name]?.trim()?.takeIf { it.isNotEmpty() }
}

sealed class CommandResult {
    data class Ok(
        val output: String,
        val data: Map<String, String> = emptyMap(),
        /** Optional one-tap follow-ups the chat card can offer. */
        val actions: List<ResultAction> = emptyList()
    ) : CommandResult()

    /** The command needs one more slot before it can run. */
    data class NeedInput(
        val param: String,
        val prompt: String
    ) : CommandResult()

    /** A handled, explainable failure. Never a raw stack trace. */
    data class Failed(val reason: String, val retryable: Boolean = false) : CommandResult()
}

@Serializable
data class ResultAction(
    val label: String,
    val commandId: String,
    val args: Map<String, String> = emptyMap()
)

/**
 * One step of an executable plan. `dependsOn` names earlier step ids so the
 * executor can substitute {{stepId.data.key}} references at run time.
 */
@Serializable
data class PlanStep(
    val id: String = "s" + UUID.randomUUID().toString().take(8),
    val commandId: String,
    val args: Map<String, String> = emptyMap(),
    val effect: EffectClass = EffectClass.READ,
    val titleKo: String = "",
    val dependsOn: List<String> = emptyList(),
    /** Estimated seconds, refined by the ETA estimator before the plan shows. */
    val estimatedSeconds: Int = 3
)

@Serializable
enum class PlanState { DRAFT, AWAITING_APPROVAL, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

@Serializable
data class Plan(
    val id: String = "p" + UUID.randomUUID().toString().take(8),
    val titleKo: String,
    val steps: List<PlanStep>,
    val createdAt: Long = 0L,
    val state: PlanState = PlanState.DRAFT
) {
    val estimatedSeconds: Int get() = steps.sumOf { it.estimatedSeconds }
}

@Serializable
data class StepOutcome(
    val stepId: String,
    val commandId: String,
    val ok: Boolean,
    val output: String,
    val data: Map<String, String> = emptyMap(),
    val error: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
) {
    val tookMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)
}

@Serializable
data class PlanOutcome(
    val planId: String,
    val state: PlanState,
    val outcomes: List<StepOutcome>,
    val failure: String? = null
)

/** Messages shown in the chat timeline. Local-only except USER/ASSISTANT text. */
sealed class ChatItem {
    abstract val id: String
    abstract val at: Long

    data class UserText(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val text: String,
        override val at: Long
    ) : ChatItem()

    data class AssistantText(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val text: String,
        val commandId: String? = null,
        val actions: List<ResultAction> = emptyList(),
        override val at: Long
    ) : ChatItem()

    data class PlanCard(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val plan: Plan,
        override val at: Long
    ) : ChatItem()

    data class ApprovalCard(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val request: ApprovalRequest,
        override val at: Long
    ) : ChatItem()

    data class ReportCard(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val report: ProgressReport,
        override val at: Long
    ) : ChatItem()

    data class NoticeCard(
        override val id: String = "m" + UUID.randomUUID().toString().take(8),
        val title: String,
        val body: String,
        val tone: NoticeTone = NoticeTone.NEUTRAL,
        override val at: Long
    ) : ChatItem()
}

@Serializable
enum class NoticeTone { NEUTRAL, WARN, ERROR, SUCCESS }

/** What the user is asked to approve, in plain words plus the exact effect. */
@Serializable
data class ApprovalRequest(
    val id: String = "a" + UUID.randomUUID().toString().take(8),
    val planId: String,
    val stepId: String,
    val commandId: String,
    val titleKo: String,
    val summaryKo: String,
    val details: List<Pair<String, String>> = emptyList(),
    val effect: EffectClass = EffectClass.EXTERNAL
)

@Serializable
data class ProgressReport(
    val planId: String,
    val planTitle: String,
    val doneSteps: Int,
    val totalSteps: Int,
    val state: PlanState,
    val headline: String,
    val lines: List<String> = emptyList(),
    val etaSecondsLeft: Int = 0,
    val at: Long = 0L
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

@Serializable
data class MemoryEntry(
    val id: String = "mem" + UUID.randomUUID().toString().take(8),
    val key: String,
    val value: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val source: String = "user"
) {
    val searchable: String get() = (key + " " + value + " " + tags.joinToString(" ")).lowercase()
}

@Serializable
data class SkillStep(
    val commandId: String,
    val args: Map<String, String> = emptyMap(),
    val titleKo: String = ""
)

@Serializable
data class Skill(
    val id: String = "k" + UUID.randomUUID().toString().take(8),
    val name: String,
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val pattern: String = "",
    val steps: List<SkillStep> = emptyList(),
    val builtIn: Boolean = false
)

@Serializable
data class PipelineParam(
    val name: String,
    val labelKo: String,
    val example: String = "",
    val required: Boolean = true
)

@Serializable
data class Pipeline(
    val id: String = "f" + UUID.randomUUID().toString().take(8),
    val name: String,
    val description: String = "",
    val params: List<PipelineParam> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val builtIn: Boolean = false,
    val lastRunAt: Long = 0L,
    val lastRunOk: Boolean = false
)

@Serializable
enum class HubKind(val labelKo: String) {
    APP("앱"),
    SITE("사이트"),
    AI("AI"),
    MAIL("메일"),
    FILE("파일"),
    COMMAND("명령")
}

@Serializable
data class HubEntry(
    val id: String = "h" + UUID.randomUUID().toString().take(8),
    val kind: HubKind,
    val label: String,
    /** package name, url, command id or file uri */
    val target: String,
    val note: String = "",
    val pinned: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L
)

@Serializable
/** A factory for a built-in pipeline (kept out of the persisted model). */
data class PipelineBlueprint(
    val name: String,
    val description: String,
    val params: List<PipelineParam> = emptyList(),
    val steps: List<SkillStep> = emptyList()
)

data class CustomCommand(
    val id: String,
    val label: String,
    val template: String,
    val keywords: List<String> = emptyList(),
    val createdAt: Long = 0L
)
