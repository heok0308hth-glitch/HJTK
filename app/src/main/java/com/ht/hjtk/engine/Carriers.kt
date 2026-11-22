package com.ht.hjtk.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.ht.hjtk.core.agent.LlmPort
import com.ht.hjtk.core.agent.LlmReply
import com.ht.hjtk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 앱 자체 HTTP(웹검색·날씨 등 공개 엔드포인트, 선택적 API 키 경로). DeepSeek 웹 API를 재구현하지 않는다. */
object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    data class Resp(val code: Int, val body: String, val url: String, val type: String)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), max: Int = 1_500_000, timeoutMs: Int = 15_000): Resp = req("GET", url, null, headers, max, timeoutMs)
    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 120_000): Resp =
        req("POST", url, body, headers + ("Content-Type" to "application/json"), 4_000_000, timeoutMs)

    suspend fun req(method: String, url: String, body: String?, headers: Map<String, String>, max: Int, timeoutMs: Int): Resp = withContext(Dispatchers.IO) {
        var u = URL(url); var hops = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA); setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7"); setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
            }
            val code = c.responseCode
            val loc = if (code in 300..399 && hops < 5) c.getHeaderField("Location") else null
            if (loc != null) { u = URL(u, loc); hops++; c.disconnect(); continue }
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return@withContext Resp(code, "", u.toString(), c.contentType ?: "")
            val raw = (if (c.contentEncoding == "gzip") GZIPInputStream(stream) else stream).use { s ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16384); var total = 0
                while (true) { val n = s.read(buf); if (n < 0 || total > max) break; out.write(buf, 0, n); total += n }
                out.toByteArray()
            }
            val ct = c.contentType ?: ""
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(raw, 0, minOf(raw.size, 3000), Charsets.ISO_8859_1))?.groupValues?.get(1)
                ?: "UTF-8"
            val text = runCatching { String(raw, charset(cs)) }.getOrDefault(String(raw))
            c.disconnect()
            return@withContext Resp(code, text, u.toString(), ct)
        }
        @Suppress("UNREACHABLE_CODE") Resp(0, "", url, "")
    }
}

/** L7 사용자 릴레이: 공유 시트·텍스트 선택(PROCESS_TEXT)·붙여넣기로 들어온 답. */
object RelayInbox {
    data class Item(val text: String, val source: String, val at: Long = System.currentTimeMillis())
    val flow = MutableSharedFlow<Item>(extraBufferCapacity = 16)
    fun submit(text: String, source: String) { if (text.isNotBlank()) flow.tryEmit(Item(text, source)) }
}

/** L1 — 선택 경로. 키가 있을 때만 존재한다. */
class DeepSeekApi(private val store: Store) : LlmPort {
    override val name = "DeepSeek API"
    private val history = HashMap<String, JSONArray>()
    fun available() = store.secrets.has("deepseek_key")

    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
        val key = store.secrets.get("deepseek_key") ?: return LlmReply("", "NO_KEY", false, 0)
        val t0 = System.currentTimeMillis()
        val msgs = if (newSession) JSONArray().also { history[jobId] = it } else history.getOrPut(jobId) { JSONArray() }
        msgs.put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject().put("model", store.settings.value.dsModel).put("messages", msgs).put("stream", false)
        val r = runCatching { Http.postJson("https://api.deepseek.com/chat/completions", body.toString(), mapOf("Authorization" to "Bearer $key")) }.getOrNull()
            ?: return LlmReply("", "NET_ERROR", false, System.currentTimeMillis() - t0)
        val content = runCatching { JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }.getOrNull()
            ?: return LlmReply("", "HTTP_${r.code}", false, System.currentTimeMillis() - t0)
        msgs.put(JSONObject().put("role", "assistant").put("content", content))
        return LlmReply(content, "L1_API", true, System.currentTimeMillis() - t0)
    }
}

class GeminiApi(private val store: Store) {
    fun available() = store.secrets.has("gemini_key")
    suspend fun ask(prompt: String): String? {
        val key = store.secrets.get("gemini_key") ?: return null
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val model = store.settings.value.geminiModel
        val r = runCatching { Http.postJson("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body.toString(), mapOf("x-goog-api-key" to key)) }.getOrNull() ?: return null
        return runCatching {
            val parts = JSONObject(r.body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }.getOrNull()
    }
}

/**
 * 블랙박스 앱 경로(L2 송신 + L3 화면읽기 / L7 공유 회수).
 * Android에는 '보낸 요청의 답을 돌려받는' 계약이 없다. 그래서 답이 돌아올 수 있는 모든 길을 동시에 연다.
 */
class ExternalAiBridge(private val ctx: Context, private val launcher: Launcher) {
    data class Target(val name: String, val pkg: String?, val webUrl: String)
    companion object {
        val GEMINI = Target("Gemini", "com.google.android.apps.bard", "https://gemini.google.com/app")
        val DEEPSEEK_APP = Target("DeepSeek 앱", "com.deepseek.chat", "https://chat.deepseek.com")
    }

    fun installed(t: Target): Boolean = t.pkg != null && runCatching { ctx.packageManager.getPackageInfo(t.pkg, 0); true }.getOrDefault(false)

    /** 사용자 1탭(백그라운드 액티비티 시작 제한 때문에) → 대상 앱에 프롬프트 전달. 답은 화면읽기·공유·클립보드 중 먼저 온 것. */
    suspend fun ask(t: Target, prompt: String, useWeb: Boolean, waitMs: Long, jobTag: String): LlmReply {
        val t0 = System.currentTimeMillis()
        Clip.copy(ctx, prompt, sensitive = false)
        val intent = if (!useWeb && installed(t)) Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, prompt); setPackage(t.pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else Intent(Intent.ACTION_VIEW, Uri.parse(t.webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (t.pkg != null) A11yBridge.arm(t.pkg, prompt)
        launcher.launch(intent, "${t.name}에게 보내기", "${t.name}에 질문을 넣었어요(클립보드에도 복사). 답이 나오면 '공유 → HJTK' 또는 복사해 주세요.", customTab = useWeb || !installed(t))
        val got = withTimeoutOrNull(waitMs) {
            kotlinx.coroutines.flow.merge(
                RelayInbox.flow,
                A11yBridge.answers.filter { it.pkg == t.pkg }.map { RelayInbox.Item(it.text, "L3_A11Y") }
            ).first()
        }
        A11yBridge.disarm()
        return if (got == null) LlmReply("", "TIMEOUT", false, System.currentTimeMillis() - t0)
        else LlmReply(got.text, got.source, true, System.currentTimeMillis() - t0)
    }
}

/** DeepSeek 앱(설치된 앱)을 두뇌로 쓰는 경로. WebView보다 사람 손이 더 필요하다. */
class DeepSeekAppPort(private val bridge: ExternalAiBridge, private val store: Store) : LlmPort {
    override val name = "DeepSeek 앱"
    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply =
        bridge.ask(ExternalAiBridge.DEEPSEEK_APP, text, false, store.settings.value.geminiWaitMin * 60_000L, jobId)
}

fun openCustomTab(ctx: Context, url: String) {
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(ctx, Uri.parse(url))
    }.onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
