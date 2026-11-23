package com.ht.hjtk.engine

import android.content.Context
import com.ht.hjtk.core.agent.LlmPort
import com.ht.hjtk.core.agent.LlmReply
import com.ht.hjtk.core.ai.Assignment
import com.ht.hjtk.core.ai.Member
import com.ht.hjtk.core.ai.ModelRouter
import com.ht.hjtk.core.ai.Provider
import com.ht.hjtk.core.ai.Role
import com.ht.hjtk.core.ai.Difficulty
import com.ht.hjtk.core.ai.Tokens
import com.ht.hjtk.core.ai.TokenLedger
import com.ht.hjtk.core.ai.Transport
import com.ht.hjtk.data.Store
import com.ht.hjtk.tools.TapBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 회사별 계정(같은 회사 여러 개 가능). 키는 Keystore로 암호화해 secrets에 따로 둔다. */
data class AiAccount(val id: String, val provider: Provider, val label: String, val baseUrl: String = "", val enabled: Boolean = true, val isDefault: Boolean = false) {
    fun toJson() = JSONObject().put("id", id).put("p", provider.name).put("label", label).put("base", baseUrl).put("on", enabled).put("def", isDefault)
    companion object { fun from(o: JSONObject) = AiAccount(o.getString("id"), Provider.of(o.getString("p")) ?: Provider.DEEPSEEK, o.optString("label"), o.optString("base"), o.optBoolean("on", true), o.optBoolean("def")) }
}

/** 회사별 생성 설정(클로드 앱처럼 세부 조정) */
data class GenPrefs(val model: String = "auto", val temperature: Double? = null, val maxTokens: Int = 4096, val reasoning: Boolean = false, val system: String = "", val appRelay: Boolean = true) {
    fun toJson() = JSONObject().put("model", model).put("temp", temperature ?: JSONObject.NULL).put("max", maxTokens).put("reason", reasoning).put("sys", system).put("app", appRelay)
    companion object { fun from(o: JSONObject?) = o?.let { GenPrefs(it.optString("model", "auto"), if (it.isNull("temp")) null else it.optDouble("temp"), it.optInt("max", 4096), it.optBoolean("reason"), it.optString("sys"), it.optBoolean("app", true)) } ?: GenPrefs() }
}

data class AiState(
    val accounts: List<AiAccount> = emptyList(),
    val prefs: Map<Provider, GenPrefs> = emptyMap(),
    /** 대화에 쓸 AI: "auto" 또는 Member.key(+ "|모델ID") */
    val chatPin: String = "auto",
    /** 작업 역할 고정: Role.name → "auto" 또는 Member.key */
    val rolePins: Map<String, String> = emptyMap(),
    val difficulty: Difficulty = Difficulty.STANDARD,
    val webDeepSeek: Boolean = true,
    /** 토큰 절약: 자료 압축 + 작업 단계는 대화 기록 없이(무상태) 보냄 */
    val saveTokens: Boolean = true
) {
    fun pref(p: Provider) = prefs[p] ?: GenPrefs()
}

/** 사용량 기록: 날짜 × AI별 토큰(API는 실제값, 웹·앱은 추정). 사용량 화면과 PC가 읽는다 */
object UsageLog {
    data class Day(val date: String, val by: Map<String, IntArray>)
    @Volatile private var file: File? = null
    private val days = LinkedHashMap<String, LinkedHashMap<String, IntArray>>()   // date → who → [in, out, calls]
    val version = MutableStateFlow(0)
    fun install(ctx: Context) {
        file = File(ctx.filesDir, "hjtk/usage.json")
        runCatching { val o = JSONObject(file!!.readText()); o.keys().forEach { d -> val m = LinkedHashMap<String, IntArray>(); val od = o.getJSONObject(d); od.keys().forEach { w -> val a = od.getJSONArray(w); m[w] = intArrayOf(a.getInt(0), a.getInt(1), a.optInt(2)) }; days[d] = m } }
    }
    @Synchronized fun add(who: String, input: Int, output: Int) {
        val d = java.time.LocalDate.now().toString()
        val m = days.getOrPut(d) { LinkedHashMap() }
        val a = m.getOrPut(who) { intArrayOf(0, 0, 0) }; a[0] += input; a[1] += output; a[2]++
        while (days.size > 60) days.remove(days.keys.first())
        runCatching { file?.writeText(JSONObject().apply { days.forEach { (k, v) -> put(k, JSONObject().apply { v.forEach { (w, x) -> put(w, JSONArray(x.toList())) } }) } }.toString()) }
        version.value++
    }
    @Synchronized fun recent(n: Int): List<Day> = days.entries.toList().takeLast(n).map { Day(it.key, it.value.mapValues { e -> e.value.copyOf() }) }
}

/** 지금 화면에 흘려 보여 줄 '쓰는 중' 글자들(작업·대화 공통) */
object LiveBus {
    data class Line(val owner: String, val who: String, val text: String, val at: Long = System.currentTimeMillis())
    val lines = MutableStateFlow<Map<String, Line>>(emptyMap())
    fun put(owner: String, who: String, text: String) = lines.update { it + (owner to Line(owner, who, text)) }
    fun clear(owner: String) = lines.update { it - owner }
}

class AiHub(private val ctx: Context, private val store: Store, private val bridge: ExternalAiBridge) {
    private val file = File(ctx.filesDir, "hjtk/ai.json")
    val state = MutableStateFlow(load())

    // ── 저장 ──────────────────────────────────────────────────────
    private fun load(): AiState {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        var s = if (o == null) AiState() else AiState(
            accounts = o.optJSONArray("acc")?.let { a -> (0 until a.length()).map { AiAccount.from(a.getJSONObject(it)) } }.orEmpty(),
            prefs = o.optJSONObject("prefs")?.let { p -> Provider.values().filter { p.has(it.name) }.associateWith { GenPrefs.from(p.optJSONObject(it.name)) } }.orEmpty(),
            chatPin = o.optString("chat", "auto"),
            rolePins = o.optJSONObject("pins")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } }.orEmpty(),
            difficulty = Difficulty.of(o.optString("diff")),
            webDeepSeek = o.optBoolean("web", true),
            saveTokens = o.optBoolean("save", true)
        )
        // 1.x의 단일 키를 계정으로 옮긴다
        if (s.accounts.isEmpty()) {
            val migrated = ArrayList<AiAccount>()
            store.secrets.get("deepseek_key")?.let { k -> val a = AiAccount(newId(), Provider.DEEPSEEK, "기본", isDefault = true); store.secrets.put(keyName(a.id), k); migrated += a }
            store.secrets.get("gemini_key")?.let { k -> val a = AiAccount(newId(), Provider.GEMINI, "기본", isDefault = true); store.secrets.put(keyName(a.id), k); migrated += a }
            if (migrated.isNotEmpty()) s = s.copy(accounts = migrated)
        }
        return s
    }

    private fun save(s: AiState) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("acc", JSONArray(s.accounts.map { it.toJson() }))
                .put("prefs", JSONObject().apply { s.prefs.forEach { (k, v) -> put(k.name, v.toJson()) } })
                .put("chat", s.chatPin).put("pins", JSONObject(s.rolePins as Map<*, *>)).put("diff", s.difficulty.name)
                .put("web", s.webDeepSeek).put("save", s.saveTokens).toString())
        }
    }

    fun update(f: (AiState) -> AiState) { state.update(f); save(state.value) }
    private fun newId() = "ac" + UUID.randomUUID().toString().take(8)
    private fun keyName(id: String) = "ai_key_$id"
    fun key(a: AiAccount): String? = store.secrets.get(keyName(a.id))

    fun addAccount(p: Provider, label: String, apiKey: String, baseUrl: String = ""): AiAccount {
        val first = state.value.accounts.none { it.provider == p }
        val a = AiAccount(newId(), p, label.ifBlank { "계정 ${state.value.accounts.count { it.provider == p } + 1}" }, baseUrl, true, first)
        store.secrets.put(keyName(a.id), apiKey.trim())
        update { it.copy(accounts = it.accounts + a) }
        return a
    }
    fun removeAccount(id: String) { store.secrets.put(keyName(id), null); update { s -> s.copy(accounts = s.accounts.filterNot { it.id == id }) } }
    fun makeDefault(id: String) = update { s -> val p = s.accounts.firstOrNull { it.id == id }?.provider; s.copy(accounts = s.accounts.map { if (it.provider == p) it.copy(isDefault = it.id == id) else it }) }
    fun setPref(p: Provider, f: (GenPrefs) -> GenPrefs) = update { s -> s.copy(prefs = s.prefs + (p to f(s.pref(p)))) }

    // ── 쓸 수 있는 AI 목록 ─────────────────────────────────────────
    fun members(): List<Member> {
        val s = state.value
        val out = ArrayList<Member>()
        s.accounts.filter { it.enabled && key(it) != null }.groupBy { it.provider }.forEach { (p, accs) ->
            val pin = s.pref(p).model.takeIf { it != "auto" }
            // 같은 회사 여러 계정: 기본 계정을 먼저(라우터는 같은 회사면 첫 번째를 고른다)
            accs.sortedByDescending { it.isDefault }.forEach { a -> out += Member(p, Transport.API, a.id, if (accs.size > 1) a.label else "", pin) }
        }
        if (s.webDeepSeek) out += Member(Provider.DEEPSEEK, Transport.WEB)
        // 3.1: 로그인해 둔 AI 웹(Claude·ChatGPT·Gemini·Grok) — 키 없이
        WebAiHub.loggedIn.value.filter { it != Provider.DEEPSEEK }.sortedBy { it.ordinal }.forEach { out += Member(it, Transport.WEB) }
        // 3.0: 다른 AI 앱으로 넘기는 연결(Transport.APP)은 만들지 않는다 — 키(API) 또는 HJTK 안 웹만
        return out
    }

    private fun installed(pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun account(m: Member) = state.value.accounts.firstOrNull { it.id == m.accountId }

    fun chatAssignment(): Assignment? {
        val s = state.value
        val pin = s.chatPin
        if (pin != "auto") {
            val (key, model) = pin.split("|").let { it[0] to it.getOrNull(1) }
            members().firstOrNull { it.key == key }?.let { m -> val mm = model?.let { m.copy(pinnedModel = it) } ?: m; return Assignment(Role.CHAT, mm, ModelRouter.modelFor(mm, com.ht.hjtk.core.ai.Tier.BALANCED), "직접 선택") }
        }
        return ModelRouter.pick(Role.CHAT, Difficulty.STANDARD, members())
    }

    fun team(d: Difficulty) = ModelRouter.team(d, members(), state.value.rolePins.mapNotNull { (k, v) -> Role.values().firstOrNull { it.name == k }?.let { it to v } }.toMap())

    // ── 포트 만들기(API·APP). WEB은 러너가 세션·잠금과 함께 감싼다 ──────
    fun apiPort(a: Assignment, owner: String, stateless: Boolean, ledger: TokenLedger? = null): LlmPort? {
        val acc = account(a.member) ?: return null
        val k = key(acc) ?: return null
        val model = a.model?.id ?: return null
        return ApiPort(a.member.provider, acc.baseUrl.ifBlank { a.member.provider.defaultBase }, k, model, a.name, state.value.pref(a.member.provider), owner, stateless, ledger)
    }

    fun appPort(a: Assignment, budget: TapBudget, waitMs: Long): LlmPort = object : LlmPort {
        override val name = a.name
        override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply {
            if (budget.left <= 0) return LlmReply("", "BUDGET", false, 0)
            budget.left--
            val p = a.member.provider
            return bridge.ask(ExternalAiBridge.Target(p.label, p.appPkg, p.webUrl), text, useWeb = false, waitMs = waitMs, jobTag = jobId)
        }
    }

    /** 그림을 볼 수 있는 AI(키가 있는 Claude·GPT·Gemini) */
    fun visionAssignment(): Assignment? {
        val vis = members().filter { it.transport == Transport.API && it.provider in setOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI) }
        return ModelRouter.pick(Role.RESEARCH, Difficulty.STANDARD, vis)
    }
    fun canSee(a: Assignment?) = a != null && a.member.transport == Transport.API && a.member.provider in setOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI)

    /** 이미지를 보고 답한다. (누가 봤나, 답) — 볼 수 있는 AI가 없으면 null */
    suspend fun see(images: List<File>, question: String, owner: String = "vision"): Pair<String, String>? {
        val a = visionAssignment() ?: return null
        val port = apiPort(a, owner, stateless = true) as? ApiPort ?: return null
        val r = port.sendWithImages(question, owner, true, images)
        return if (r.text.isBlank()) null else a.name to r.text
    }

    /** 키 확인: 짧은 요청 하나 */
    suspend fun test(acc: AiAccount): String {
        val k = key(acc) ?: return "키가 없어요"
        val model = com.ht.hjtk.core.ai.ModelCatalog.forTier(acc.provider, com.ht.hjtk.core.ai.Tier.FAST)?.id ?: return "모델 없음"
        val r = ApiStream.chat(acc.provider, acc.baseUrl.ifBlank { acc.provider.defaultBase }, k, model, listOf("user" to "Reply with exactly: OK"), null, GenPrefs(maxTokens = 16)) { _ -> }
        return r.error ?: "연결됨 · $model · ${r.text.take(20)}"
    }
}

/** API 포트: 스트리밍으로 받아 LiveBus에 흘리고, 장부에 토큰을 적는다. stateless면 대화 기록 없이(작업 단계는 프롬프트에 필요한 자료가 다 들어 있다) */
class ApiPort(
    private val p: Provider, private val base: String, private val key: String, private val model: String, override val name: String,
    private val prefs: GenPrefs, private val owner: String, private val stateless: Boolean, private val ledger: TokenLedger?
) : LlmPort {
    private val history = HashMap<String, MutableList<Pair<String, String>>>()
    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = sendWithImages(text, jobId, newSession, emptyList())

    suspend fun sendWithImages(text: String, jobId: String, newSession: Boolean, images: List<File>): LlmReply {
        val t0 = System.currentTimeMillis()
        val h = if (stateless) mutableListOf() else (if (newSession) mutableListOf<Pair<String, String>>().also { history[jobId] = it } else history.getOrPut(jobId) { mutableListOf() })
        h += "user" to text
        // 대화 기록 예산: 오래된 턴부터 버린다(토큰 절약)
        while (h.sumOf { it.second.length } > 24_000 && h.size > 2) { h.removeAt(0); if (h.firstOrNull()?.first == "assistant") h.removeAt(0) }
        var last = 0L
        val o = owner.ifBlank { jobId }
        val r = ApiStream.chat(p, base, key, model, h.toList(), prefs.system.ifBlank { null }, prefs, images) { acc ->
            val now = System.currentTimeMillis()
            if (now - last > Thermal.liveIntervalMs) { last = now; LiveBus.put(o, name, acc) }
        }
        LiveBus.clear(o)
        val tin = r.inTokens.takeIf { it > 0 } ?: Tokens.estimate(h.joinToString("") { it.second }); val tout = r.outTokens.takeIf { it > 0 } ?: Tokens.estimate(r.text)
        ledger?.add(name, tin, tout)
        UsageLog.add(name, tin, tout)
        if (r.text.isNotBlank() && !stateless) h += "assistant" to r.text
        return LlmReply(r.text, r.error?.let { "API_ERR" } ?: "API_${p.name}", r.error == null && r.text.isNotBlank(), System.currentTimeMillis() - t0)
    }
}

/** 세 가지 API 형식(OpenAI 호환·Anthropic·Gemini)의 스트리밍 클라이언트. 의존성 없이 HttpURLConnection + SSE 줄 읽기 */
object ApiStream {
    data class Result(val text: String, val inTokens: Int = 0, val outTokens: Int = 0, val error: String? = null)

    /** 이미지(JPEG/PNG)를 base64로. 긴 변 1280px로 이미 줄여 둔 파일을 쓴다 */
    private fun b64(f: File) = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    private fun mime(f: File) = if (f.name.endsWith(".png", true)) "image/png" else "image/jpeg"

    suspend fun chat(p: Provider, base: String, key: String, model: String, messages: List<Pair<String, String>>, system: String?, prefs: GenPrefs, images: List<File> = emptyList(), onDelta: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val b = base.trimEnd('/')
        val lastUser = messages.indexOfLast { it.first == "user" }
        val (url, headers, body) = when (p.apiStyle) {
            "anthropic" -> Triple("$b/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", prefs.maxTokens).put("stream", true).apply {
                    system?.let { put("system", it) }; prefs.temperature?.let { put("temperature", it) }
                    put("messages", JSONArray(messages.mapIndexed { i, m ->
                        if (i == lastUser && images.isNotEmpty()) JSONObject().put("role", m.first).put("content", JSONArray().apply {
                            images.forEach { f -> put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime(f)).put("data", b64(f)))) }
                            put(JSONObject().put("type", "text").put("text", m.second))
                        }) else JSONObject().put("role", m.first).put("content", m.second)
                    }))
                })
            "gemini" -> Triple("$b/models/$model:streamGenerateContent?alt=sse", mapOf("x-goog-api-key" to key),
                JSONObject().put("contents", JSONArray(messages.mapIndexed { i, m -> JSONObject().put("role", if (m.first == "assistant") "model" else "user").put("parts", JSONArray().apply {
                    if (i == lastUser) images.forEach { f -> put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime(f)).put("data", b64(f)))) }
                    put(JSONObject().put("text", m.second))
                }) })).apply {
                    system?.let { put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
                    put("generationConfig", JSONObject().put("maxOutputTokens", prefs.maxTokens).apply { prefs.temperature?.let { t -> put("temperature", t) } })
                })
            else -> Triple("$b/chat/completions", mapOf("Authorization" to "Bearer $key"),
                JSONObject().put("model", model).put("stream", true).apply {
                    // 새 OpenAI 모델은 max_completion_tokens만 받는다
                    if (p == Provider.GPT) put("max_completion_tokens", prefs.maxTokens) else put("max_tokens", prefs.maxTokens)
                    prefs.temperature?.let { put("temperature", it) }
                    if (p != Provider.GROK) put("stream_options", JSONObject().put("include_usage", true))
                    if (p == Provider.GPT && prefs.reasoning) put("reasoning_effort", "high")
                    val ms = JSONArray(); system?.let { ms.put(JSONObject().put("role", "system").put("content", it)) }
                    messages.forEachIndexed { i, m ->
                        if (i == lastUser && images.isNotEmpty()) ms.put(JSONObject().put("role", m.first).put("content", JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", m.second))
                            images.forEach { f -> put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${mime(f)};base64,${b64(f)}"))) }
                        })) else ms.put(JSONObject().put("role", m.first).put("content", m.second))
                    }
                    put("messages", ms)
                })
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code >= 400) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
                val msg = runCatching { JSONObject(err).let { o -> o.optJSONObject("error")?.optString("message") ?: o.optString("message") } }.getOrNull()?.ifBlank { null } ?: err.take(160)
                return@withContext Result("", error = "HTTP $code · $msg")
            }
            val sb = StringBuilder(); var tin = 0; var tout = 0
            c.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (p.apiStyle) {
                        "anthropic" -> when (o.optString("type")) {
                            "content_block_delta" -> o.optJSONObject("delta")?.optString("text")?.let { if (it.isNotEmpty()) { sb.append(it); onDelta(sb.toString()) } }
                            "message_start" -> tin = o.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens") ?: 0
                            "message_delta" -> tout = o.optJSONObject("usage")?.optInt("output_tokens") ?: tout
                        }
                        "gemini" -> {
                            o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                                for (i in 0 until parts.length()) { val part = parts.optJSONObject(i); if (part?.optBoolean("thought") == true) continue; part?.optString("text")?.let { sb.append(it) } }
                                onDelta(sb.toString())
                            }
                            o.optJSONObject("usageMetadata")?.let { u -> tin = u.optInt("promptTokenCount", tin); tout = u.optInt("candidatesTokenCount", tout) }
                        }
                        else -> {
                            o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")?.let { if (it.isNotEmpty() && it != "null") { sb.append(it); onDelta(sb.toString()) } }
                            o.optJSONObject("usage")?.let { u -> tin = u.optInt("prompt_tokens", tin); tout = u.optInt("completion_tokens", tout) }
                        }
                    }
                }
            }
            Result(sb.toString(), tin, tout, if (sb.isEmpty()) "빈 응답" else null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result("", error = "${e.javaClass.simpleName}: ${e.message?.take(120)}") }
        finally { runCatching { c.disconnect() } }
    }
}
