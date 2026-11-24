package com.ht.ensemble.ports

import com.ht.ensemble.agent.LlmPort
import com.ht.ensemble.agent.LlmReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 어디서나 쓰는 AI 연결(포트). 안드로이드·서버·데스크톱 어디서나 돈다(HttpURLConnection + org.json).
 *
 * ```
 * Ports.openAi(key, "gpt-5")          Ports.anthropic(key, "claude-sonnet-4-5")
 * Ports.gemini(key, "gemini-2.5-pro")  Ports.deepSeek(key)   Ports.grok(key)   Ports.ollama("qwen3")
 * Ports.fn("내 모델") { prompt -> myModel(prompt) }
 * ```
 * 실패는 예외 대신 빈 답 + 이유(via)로 돌려준다 → 엔진이 다른 AI로 교체한다. 429는 `HTTP429`라서 '사용량 한도'로 인식된다.
 */
object Ports {
    /** OpenAI 호환 /chat/completions — OpenAI·DeepSeek·xAI·Gemini(호환 엔드포인트)·OpenRouter·Ollama·vLLM 등 */
    fun openAiCompatible(name: String, baseUrl: String, apiKey: String?, model: String, temperature: Double? = null, maxTokens: Int? = null, headers: Map<String, String> = emptyMap()): LlmPort =
        HttpPort(name) { prompt ->
            val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            temperature?.let { body.put("temperature", it) }; maxTokens?.let { body.put("max_tokens", it) }
            val h = headers + (if (apiKey.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"))
            Req(baseUrl.trimEnd('/') + "/chat/completions", h, body) { o -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty() }
        }

    fun openAi(apiKey: String, model: String = "gpt-5") = openAiCompatible("ChatGPT", "https://api.openai.com/v1", apiKey, model)
    fun deepSeek(apiKey: String, model: String = "deepseek-chat") = openAiCompatible("DeepSeek", "https://api.deepseek.com", apiKey, model)
    fun grok(apiKey: String, model: String = "grok-4") = openAiCompatible("Grok", "https://api.x.ai/v1", apiKey, model)
    fun gemini(apiKey: String, model: String = "gemini-2.5-pro") = openAiCompatible("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", apiKey, model)
    fun openRouter(apiKey: String, model: String) = openAiCompatible(model.substringBefore('/').ifBlank { "OpenRouter" }, "https://openrouter.ai/api/v1", apiKey, model)
    fun ollama(model: String, baseUrl: String = "http://localhost:11434/v1") = openAiCompatible("Ollama $model", baseUrl, null, model)

    /** Anthropic Messages API */
    fun anthropic(apiKey: String, model: String = "claude-sonnet-4-5", maxTokens: Int = 8192, baseUrl: String = "https://api.anthropic.com"): LlmPort =
        HttpPort("Claude") { prompt ->
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            Req(baseUrl.trimEnd('/') + "/v1/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body) { o ->
                val c = o.optJSONArray("content") ?: JSONArray()
                (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { b -> b.optString("type") == "text" }?.optString("text") }.joinToString("")
            }
        }

    /** 내 함수 그대로(로컬 모델, 사내 게이트웨이, 시험용 가짜 AI) */
    fun fn(name: String, block: suspend (String) -> String): LlmPort = object : LlmPort {
        override val name = name
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
            val t0 = System.currentTimeMillis()
            val out = runCatching { block(text) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; return LlmReply("", "ERR:${e.message}", false, System.currentTimeMillis() - t0) }
            return LlmReply(out, "FN", true, System.currentTimeMillis() - t0)
        }
    }

    internal class Req(val url: String, val headers: Map<String, String>, val body: JSONObject, val pick: (JSONObject) -> String)

    internal class HttpPort(override val name: String, private val build: (String) -> Req) : LlmPort {
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = build(text)
            val c = URL(r.url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 30_000; c.readTimeout = 600_000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json"); r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(r.body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }).orEmpty()
                if (code !in 200..299) return@withContext LlmReply("", "HTTP$code:" + txt.take(160), false, System.currentTimeMillis() - t0)
                LlmReply(runCatching { r.pick(JSONObject(txt)) }.getOrDefault("").trim(), "HTTP$code", true, System.currentTimeMillis() - t0)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { LlmReply("", "ERR:${e.message}", false, System.currentTimeMillis() - t0) }
            finally { c.disconnect() }
        }
    }
}
