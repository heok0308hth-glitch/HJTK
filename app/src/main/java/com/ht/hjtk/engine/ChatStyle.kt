package com.ht.hjtk.engine

import android.content.Context
import com.ht.ensemble.ai.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * 대화의 '노력 · 창의성 · 길이' — 고르면 회사마다 맞는 방식으로 바꿔 보낸다.
 *  API: Claude 생각 예산 · GPT 추론 강도 · Gemini 생각 예산 · 온도 · 최대 길이
 *  웹(키 없이): 같은 뜻을 질문 앞 한 줄 지시로(사이트 설정은 건드리지 않는다)
 */
object ChatStyle {
    data class S(val effort: Int = 1, val creativity: Int = 1, val length: Int = 1)
    val EFFORT = listOf("낮음", "중간", "높음", "매우 높음", "최대")
    val CREATIVE = listOf("정확", "균형", "창의", "매우 창의", "자유")
    val LENGTH = listOf("짧게", "보통", "자세히")
    val state = MutableStateFlow(S())
    private var ctx: Context? = null

    fun install(c: Context) {
        ctx = c; val p = c.getSharedPreferences("style", 0)
        state.value = S(p.getInt("e", 1), p.getInt("c", 1), p.getInt("l", 1))
    }
    fun set(s: S) { state.value = s; ctx?.getSharedPreferences("style", 0)?.edit()?.putInt("e", s.effort)?.putInt("c", s.creativity)?.putInt("l", s.length)?.apply() }

    /** API 요청 본문을 고친다(대화에만) */
    fun apply(p: Provider, body: JSONObject, s: S = state.value) {
        val temp = when (s.creativity) { 0 -> 0.2; 2 -> 0.9; 3 -> 1.1; 4 -> 1.3; else -> null }
        val lenMul = when (s.length) { 0 -> 0.5; 2 -> 2.0; else -> 1.0 }
        when (p.apiStyle) {
            "anthropic" -> {
                val budget = when (s.effort) { 2 -> 4_000; 3 -> 12_000; 4 -> 24_000; else -> 0 }
                val base = (body.optInt("max_tokens", 4096) * lenMul).toInt().coerceAtLeast(512)
                if (budget > 0) {                       // 생각을 켜면 온도는 1이어야 한다(Anthropic 규칙)
                    body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
                    body.put("max_tokens", base + budget); body.remove("temperature")
                } else { body.put("max_tokens", base); temp?.let { body.put("temperature", it.coerceAtMost(1.0)) } }
            }
            "gemini" -> {
                val g = body.optJSONObject("generationConfig") ?: JSONObject().also { body.put("generationConfig", it) }
                g.put("maxOutputTokens", (g.optInt("maxOutputTokens", 4096) * lenMul).toInt().coerceAtLeast(512))
                temp?.let { g.put("temperature", it) }
                when (s.effort) { 0 -> 512; 2 -> 4_096; 3 -> 12_288; 4 -> 24_576; else -> null }?.let { g.put("thinkingConfig", JSONObject().put("thinkingBudget", it)) }
            }
            else -> {
                val k = if (body.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
                body.put(k, (body.optInt(k, 4096) * lenMul).toInt().coerceAtLeast(512))
                temp?.let { body.put("temperature", it) }
                if (p == Provider.GPT) when (s.effort) { 0 -> "low"; 2 -> "medium"; 3, 4 -> "high"; else -> null }?.let { body.put("reasoning_effort", it); body.remove("temperature") }
            }
        }
    }

    /** 키 없는 웹 AI에 붙이는 한 줄(기본값이면 아무것도 붙이지 않는다) */
    fun hint(s: S = state.value): String {
        val parts = ArrayList<String>()
        when (s.effort) { 0 -> parts += "빠르게 핵심만"; 2 -> parts += "충분히 생각해서"; 3 -> parts += "단계별로 깊이 검토한 뒤"; 4 -> parts += "여러 관점에서 철저히 검증한 뒤 가장 깊이 있게" }
        when (s.creativity) { 0 -> parts += "사실에 근거해 정확하게"; 2 -> parts += "창의적으로"; 3 -> parts += "남들이 생각 못 할 아이디어로"; 4 -> parts += "틀에 얽매이지 말고 자유롭게" }
        when (s.length) { 0 -> parts += "짧게"; 2 -> parts += "자세히" }
        return if (parts.isEmpty()) "" else "[답하는 방식: ${parts.joinToString(", ")} 답해 줘]\n"
    }

    fun summary(s: S = state.value) = "노력 ${EFFORT[s.effort]} · ${CREATIVE[s.creativity]} · ${LENGTH[s.length]}"
}
