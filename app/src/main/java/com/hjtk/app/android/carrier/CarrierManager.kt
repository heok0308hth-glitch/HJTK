package com.hjtk.app.android.carrier

import com.hjtk.app.android.prefs.Settings
import com.hjtk.app.core.AiAnswer
import com.hjtk.app.core.AiPort
import com.hjtk.app.core.CarrierInfo
import com.hjtk.app.core.Clock
import kotlinx.coroutines.flow.toList

/**
 * Chooses a carrier, tries it, and only then tries the next one.
 *
 * The order is: the carrier the user pinned in Settings, then any carrier that
 * is ready. A carrier that fails mid-answer is reported instead of silently
 * retried on a different model, because pretending two models are one is how
 * answers get subtly wrong.
 */
class CarrierManager(
    private val carriers: List<AiCarrier>,
    private val settings: Settings,
    private val clock: Clock
) : AiPort {

    private val lastStatus = HashMap<String, CarrierStatus>()

    private fun ordered(preferred: String?): List<AiCarrier> {
        val pin = preferred ?: settings.preferredCarrier
        val head = carriers.filter { it.id == pin }
        val tail = carriers.filter { it.id != pin }.sortedBy { if (it.requiresKey) 1 else 0 }
        return head + tail
    }

    override suspend fun ask(prompt: String, preferredCarrier: String?): AiAnswer {
        if (prompt.isBlank()) error("질문이 비어 있습니다.")
        val tried = ArrayList<String>()
        for (carrier in ordered(preferredCarrier)) {
            val status = runCatching { carrier.status() }.getOrElse { CarrierStatus(false, it.message.orEmpty()) }
            lastStatus[carrier.id] = status
            if (!status.available) {
                tried.add("${carrier.label}: ${status.note}")
                continue
            }
            val started = clock.nowMs()
            val deltas = runCatching { carrier.stream(prompt).toList() }.getOrElse { listOf(CarrierDelta("", true, it.message)) }
            val error = deltas.firstOrNull { it.error != null }?.error
            val text = deltas.joinToString("") { it.text }.trim()
            if (error == null && text.isNotBlank()) {
                return AiAnswer(text, carrier.id, carrier.label, clock.nowMs() - started)
            }
            tried.add("${carrier.label}: ${error ?: "빈 응답"}")
        }
        error(
            "사용할 수 있는 AI 캐리어가 없습니다.\n" + tried.joinToString("\n") +
                "\n설정 > AI에서 API 키를 입력하거나, 질문을 명령으로 바꿔 실행해 보세요."
        )
    }

    override fun statusLine(): String {
        val ready = carriers.filter { c ->
            (c.id == "deepseek" && !settings.deepSeekKey.isNullOrBlank()) ||
                (c.id == "gemini" && !settings.geminiKey.isNullOrBlank())
        }
        return if (ready.isEmpty()) "AI 캐리어 없음(명령 모드)" else ready.joinToString(", ") { it.label } + " 준비됨"
    }

    override fun isConfigured(): Boolean =
        !settings.deepSeekKey.isNullOrBlank() || !settings.geminiKey.isNullOrBlank()

    override fun carriers(): List<CarrierInfo> = carriers.map { c ->
        val status = lastStatus[c.id] ?: CarrierStatus(
            available = when (c.id) {
                "deepseek" -> !settings.deepSeekKey.isNullOrBlank()
                "gemini" -> !settings.geminiKey.isNullOrBlank()
                else -> !c.requiresKey
            },
            note = if (c.requiresKey) "설정에서 API 키 입력" else "무키"
        )
        CarrierInfo(c.id, c.label, status.available, c.requiresKey, status.note)
    }
}
