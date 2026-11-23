package com.ht.hjtk.core.agent

import com.ht.hjtk.core.ai.AiProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 지휘자가 부리는 팀원 하나(같은 회사라도 API·웹은 따로) */
data class CrewMember(val key: String, val name: String, val provider: com.ht.hjtk.core.ai.Provider, val lanes: Int = 1, val free: Boolean = true) {
    fun score(s: AiProfiles.Skill) = AiProfiles.score(provider, s)
}

/** 팀 운영(앱이 구현): 지금 쓸 수 있는 사람 · 말 걸기 · 실제 성적 기록(한도 감지 → 자동 교체) */
interface Crew {
    fun members(): List<CrewMember>
    fun port(m: CrewMember, lane: Int): LlmPort
    /** 결과를 알려 주면 성적표를 고치고, 한도에 걸렸으면 잠시 쉬게 한다 */
    fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String)
    /** 지금 일을 맡길 수 있나(한도로 쉬는 중이면 false) */
    fun available(m: CrewMember): Boolean
    /** 실제 성적을 반영한 능력 점수 */
    fun rate(m: CrewMember, s: AiProfiles.Skill): Double
}

/**
 * 작업 무대 — 화면이 그대로 그리는 상태(누가 · 무엇을 · 어디까지).
 * 지휘자가 바꾸고, 대화 화면의 작업 패널과 작업 화면이 함께 본다.
 */
object StageBus {
    enum class S { WAIT, RUN, CHECK, DONE, SWAP, FAIL }
    data class Card(val id: String, val title: String, val who: String, val provider: String, val status: S = S.WAIT, val doing: String = "",
                    val startedAt: Long = 0, val endedAt: Long = 0, val after: List<String> = emptyList(), val tool: Boolean = false)
    data class Stage(val jobId: String, val goal: String, val center: String, val centerProvider: String, val phase: String, val cards: List<Card>,
                     val progress: Double = 0.0, val note: String = "", val startedAt: Long = System.currentTimeMillis())

    val stages = MutableStateFlow<Map<String, Stage>>(emptyMap())
    fun put(s: Stage) = stages.update { it + (s.jobId to s) }
    fun edit(jobId: String, f: (Stage) -> Stage) = stages.update { m -> m[jobId]?.let { m + (jobId to f(it)) } ?: m }
    fun card(jobId: String, id: String, f: (Card) -> Card) = edit(jobId) { st -> st.copy(cards = st.cards.map { if (it.id == id) f(it) else it }) }
    fun drop(jobId: String) = stages.update { it - jobId }
}
