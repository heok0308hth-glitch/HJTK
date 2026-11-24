package com.ht.hjtk.engine

import com.ht.hjtk.Hjtk
import com.ht.ensemble.agent.LlmPort
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.ai.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 상태 점검 — "일을 안 하는 건지, 못 하는 건지"를 바로 보이게.
 * 각 AI에 아주 짧은 시험 질문(1+1)을 실제 경로(웹·API)로 보내고, 실패하면 사람 말 이유와 화면 구조 진단을 남긴다.
 */
object AiHealth {
    val ALL = listOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI, Provider.GROK, Provider.DEEPSEEK)
    data class Check(val provider: Provider, val ok: Boolean, val ms: Long, val answer: String, val reason: String, val probe: String?, val at: Long = System.currentTimeMillis())
    val results = MutableStateFlow<Map<Provider, Check>>(emptyMap())
    val running = MutableStateFlow<Set<Provider>>(emptySet())

    /** 팀에 들어갈 수 있나(연결 경로가 있나) */
    fun route(h: Hjtk, p: Provider): String? = h.ai.members().firstOrNull { it.provider == p }?.let { if (it.transport == Transport.API) "API 키" else "웹 로그인" }

    suspend fun check(h: Hjtk, p: Provider): Check {
        running.update { it + p }
        try {
            val member = h.ai.members().firstOrNull { it.provider == p }
            val port: LlmPort? = when {
                member == null -> null
                member.transport == Transport.API -> h.ai.apiPort(com.ht.ensemble.ai.Assignment(com.ht.ensemble.ai.Role.CHAT, member, com.ht.ensemble.ai.ModelRouter.modelFor(member, com.ht.ensemble.ai.Tier.FAST), "점검"), "health:${p.name}", stateless = true)
                p == Provider.DEEPSEEK -> h.deepseek
                else -> WebAiHub.port(p)
            }
            val c = if (port == null) Check(p, false, 0, "", "연결 안 됨 — 허브 → 계정 하나로에서 로그인하면 팀에 들어와요", null)
            else {
                val t0 = System.currentTimeMillis()
                val r = withTimeoutOrNull(150_000) { runCatching { port.send("연결 점검입니다. 1+1의 답을 숫자 하나로만 답하세요.", "health", 1, true) }.getOrNull() }
                val ms = System.currentTimeMillis() - t0
                val text = r?.text?.trim().orEmpty()
                val via = r?.via ?: "TIMEOUT"
                val ok = text.contains("2")
                member?.let { Scoreboard.record(it.key, ok, ms, text, via) }
                val reason = when { ok -> ""; r == null -> "2분 30초 안에 답이 없었어요"; text.isNotBlank() -> "답은 왔지만 엉뚱해요: ${text.take(40)}"; else -> Scoreboard.reason(via).ifBlank { "빈 답" } }
                val probe = if (!ok && member?.transport == Transport.WEB && p != Provider.DEEPSEEK) runCatching { WebAiHub.port(p)?.probe() }.getOrNull() else null
                Check(p, ok, ms, text.take(40), reason, probe)
            }
            results.update { it + (p to c) }
            return c
        } finally { running.update { it - p } }
    }

    /** 사용자가 개발자에게 보낼 수 있는 진단 요약 */
    fun report(h: Hjtk): String = buildString {
        appendLine("HJTK AI 상태 진단 · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())}")
        val snap = Scoreboard.snapshot()
        ALL.forEach { p ->
            val m = h.ai.members().firstOrNull { it.provider == p }
            val st = m?.let { snap[it.key] }
            val c = results.value[p]
            appendLine("■ ${p.label}: ${route(h, p) ?: "연결 안 됨"} · 성공 ${st?.ok ?: 0} · 실패 ${st?.fail ?: 0}" + (st?.lastVia?.takeIf { it.isNotBlank() }?.let { " · 최근 실패 ${Scoreboard.reason(it)}" } ?: ""))
            c?.let { appendLine("  점검: " + if (it.ok) "통과 ${it.ms / 1000.0}초" else "실패 — ${it.reason}") }
            c?.probe?.let { appendLine("  화면: $it") }
        }
    }
}
