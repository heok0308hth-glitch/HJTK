package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ht.ensemble.command.Category
import com.ht.ensemble.command.CommandCall
import com.ht.ensemble.command.CommandHandler
import com.ht.ensemble.command.CommandResult
import com.ht.ensemble.command.CommandSpec
import com.ht.ensemble.command.Effect
import com.ht.ensemble.command.Param
import com.ht.hjtk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 계정 연동(여러 계정 가능): 깃허브·노션 토큰, 네이버 검색 API, 그리고 '로그인 브라우저' 세션.
 * 메일(구글·네이버·기타 IMAP)과 구글 캘린더(기기 캘린더)는 기존 경로를 그대로 쓰고, 이 허브는 목록과 기본 계정만 묶는다.
 * 밖으로 나가는 행동(이슈 만들기·파일 커밋·노션에 쓰기)은 E2 이상 → '실행' 승인.
 */
enum class Service(val label: String, val tokenHint: String, val help: String) {
    GITHUB("GitHub", "개인 액세스 토큰(ghp_… 또는 github_pat_…)", "github.com → Settings → Developer settings → Personal access tokens"),
    NOTION("Notion", "내부 통합 토큰(ntn_… 또는 secret_…)", "notion.so/my-integrations → 새 통합 → 연결할 페이지에서 '연결 추가'"),
    NAVER_API("네이버 검색 API", "Client ID:Client Secret", "developers.naver.com → 애플리케이션 등록 → 검색 API")
}

data class ConnAccount(val id: String, val service: Service, val label: String, val isDefault: Boolean) {
    fun toJson() = JSONObject().put("id", id).put("s", service.name).put("label", label).put("def", isDefault)
    // 3.0에서 유료 AI 전화(Bland·Vapi) 계정을 뺐다: 예전 기록은 읽을 때 조용히 건너뛴다
    companion object { fun from(o: JSONObject): ConnAccount? = Service.values().firstOrNull { it.name == o.optString("s") }?.let { ConnAccount(o.getString("id"), it, o.optString("label"), o.optBoolean("def")) } }
}

class ConnectorHub(private val ctx: Context, private val store: Store) {
    private val file = File(ctx.filesDir, "hjtk/conn.json")
    val accounts = MutableStateFlow(load())
    /** 로그인 브라우저에서 사용자가 로그인해 둔 사이트(쿠키는 WebView 공용) */
    val sessions = MutableStateFlow(loadSessions())

    private fun load(): List<ConnAccount> = runCatching { JSONObject(file.readText()).optJSONArray("acc")?.let { a -> (0 until a.length()).mapNotNull { ConnAccount.from(a.getJSONObject(it)) } } }.getOrNull().orEmpty()
    private fun loadSessions(): List<String> = runCatching { JSONObject(file.readText()).optJSONArray("ses")?.let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull().orEmpty()
    private fun save() = runCatching { file.parentFile?.mkdirs(); file.writeText(JSONObject().put("acc", JSONArray(accounts.value.map { it.toJson() })).put("ses", JSONArray(sessions.value)).toString()) }

    fun add(s: Service, label: String, token: String): ConnAccount {
        val a = ConnAccount("cn" + UUID.randomUUID().toString().take(8), s, label.ifBlank { s.label + " " + (accounts.value.count { it.service == s } + 1) }, accounts.value.none { it.service == s })
        store.secrets.put("conn_${a.id}", token.trim())
        if (s == Service.NAVER_API) token.split(":").takeIf { it.size == 2 }?.let { store.secrets.put("naver_id", it[0].trim()); store.secrets.put("naver_secret", it[1].trim()) }
        accounts.update { it + a }; save(); return a
    }
    fun remove(id: String) { store.secrets.put("conn_$id", null); accounts.update { l -> l.filterNot { it.id == id } }; save() }
    fun makeDefault(id: String) { val s = accounts.value.firstOrNull { it.id == id }?.service; accounts.update { l -> l.map { if (it.service == s) it.copy(isDefault = it.id == id) else it } }; save() }
    fun addSession(host: String) { if (host.isNotBlank() && host !in sessions.value) { sessions.update { it + host }; save() } }
    fun removeSession(host: String) { sessions.update { it - host }; save() }

    /** 계정 고르기: 라벨로 지정하거나 기본 계정 */
    fun token(s: Service, label: String? = null): Pair<ConnAccount, String>? {
        val list = accounts.value.filter { it.service == s }
        val a = label?.let { l -> list.firstOrNull { it.label.equals(l, true) } } ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull() ?: return null
        return store.secrets.get("conn_${a.id}")?.let { a to it }
    }

    // ── HTTP(JSON) ────────────────────────────────────────────────
    private suspend fun call(method: String, url: String, headers: Map<String, String>, body: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("User-Agent", "HJTK/2.0")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty()
            code to text
        } finally { c.disconnect() }
    }

    private fun gh(t: String) = mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    private fun nt(t: String) = mapOf("Authorization" to "Bearer $t", "Notion-Version" to "2022-06-28")
    private fun err(code: Int, text: String) = CommandResult.fail("HTTP $code · " + (runCatching { JSONObject(text).optString("message") }.getOrNull()?.ifBlank { null } ?: text.take(160)))

    private fun notionId(s: String) = Regex("[0-9a-fA-F]{32}").find(s.replace("-", ""))?.value ?: s.trim()

    fun handlers(reader: PageReader, aiHub: AiHub?): Map<String, CommandHandler> = mapOf(
        "github.repos" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val (code, txt) = call("GET", "https://api.github.com/user/repos?per_page=30&sort=updated", gh(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONArray(txt); CommandResult.ok((0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { "${it.getString("full_name")}${if (it.optBoolean("private")) " (비공개)" else ""} · ★${it.optInt("stargazers_count")} · ${it.optString("description").take(60)}" } }) },
        "github.issues" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val (code, txt) = call("GET", "https://api.github.com/repos/${c.arg("repo")}/issues?state=${c.arg("state", "open")}&per_page=30", gh(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONArray(txt); CommandResult.ok(if (a.length() == 0) "이슈 없음" else (0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { "#${it.getInt("number")} ${it.getString("title")} · ${it.optJSONObject("user")?.optString("login")} · ${it.optString("html_url")}" } }) },
        "github.issue_create" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val (code, txt) = call("POST", "https://api.github.com/repos/${c.arg("repo")}/issues", gh(t), JSONObject().put("title", c.arg("title")).put("body", c.arg("body")).toString()); if (code >= 400) return@CommandHandler err(code, txt)
            CommandResult.ok("이슈를 만들었어요: " + JSONObject(txt).optString("html_url")) },
        "github.file" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val ref = c.args["ref"]?.let { "?ref=$it" } ?: ""
            val (code, txt) = call("GET", "https://api.github.com/repos/${c.arg("repo")}/contents/${c.arg("path").trimStart('/')}$ref", gh(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val o = JSONObject(txt); CommandResult.ok(String(Base64.getMimeDecoder().decode(o.optString("content"))).take(20_000)) },
        "github.commit_file" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val url = "https://api.github.com/repos/${c.arg("repo")}/contents/${c.arg("path").trimStart('/')}"
            val sha = call("GET", url + (c.args["branch"]?.let { "?ref=$it" } ?: ""), gh(t)).let { (code, txt) -> if (code < 400) JSONObject(txt).optString("sha").ifBlank { null } else null }
            val body = JSONObject().put("message", c.arg("message", "HJTK에서 커밋")).put("content", Base64.getEncoder().encodeToString(c.arg("content").toByteArray())).apply { sha?.let { put("sha", it) }; c.args["branch"]?.let { put("branch", it) } }
            val (code, txt) = call("PUT", url, gh(t), body.toString()); if (code >= 400) return@CommandHandler err(code, txt)
            CommandResult.ok("커밋했어요: " + JSONObject(txt).optJSONObject("commit")?.optString("html_url")) },
        "github.search" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val q = java.net.URLEncoder.encode(c.arg("q"), "UTF-8")
            val (code, txt) = call("GET", "https://api.github.com/search/repositories?q=$q&per_page=10", gh(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONObject(txt).optJSONArray("items") ?: JSONArray()
            CommandResult.ok((0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { "${it.getString("full_name")} · ★${it.optInt("stargazers_count")} · ${it.optString("html_url")}\n   ${it.optString("description").take(100)}" } }.ifBlank { "결과 없음" }) },
        "github.notifications" to CommandHandler { c -> val (_, t) = token(Service.GITHUB, c.args["account"]) ?: return@CommandHandler noAcc("GitHub")
            val (code, txt) = call("GET", "https://api.github.com/notifications?per_page=20", gh(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONArray(txt); CommandResult.ok(if (a.length() == 0) "새 알림 없음" else (0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { "${it.optJSONObject("repository")?.optString("full_name")} · ${it.optJSONObject("subject")?.optString("title")} (${it.optString("reason")})" } }) },
        "notion.search" to CommandHandler { c -> val (_, t) = token(Service.NOTION, c.args["account"]) ?: return@CommandHandler noAcc("Notion")
            val (code, txt) = call("POST", "https://api.notion.com/v1/search", nt(t), JSONObject().put("query", c.arg("q")).put("page_size", 15).toString()); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONObject(txt).optJSONArray("results") ?: JSONArray()
            CommandResult.ok((0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { o ->
                val title = o.optJSONObject("properties")?.let { p -> p.keys().asSequence().mapNotNull { k -> p.optJSONObject(k)?.optJSONArray("title") }.firstOrNull() }?.let { arr -> (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optString("plain_text") } }
                "${title ?: "(제목 없음)"} · ${o.optString("object")} · ${o.optString("url")}" } }.ifBlank { "결과 없음(통합을 페이지에 연결했는지 확인)" }) },
        "notion.read" to CommandHandler { c -> val (_, t) = token(Service.NOTION, c.args["account"]) ?: return@CommandHandler noAcc("Notion")
            val (code, txt) = call("GET", "https://api.notion.com/v1/blocks/${notionId(c.arg("id"))}/children?page_size=100", nt(t)); if (code >= 400) return@CommandHandler err(code, txt)
            val a = JSONObject(txt).optJSONArray("results") ?: JSONArray()
            CommandResult.ok((0 until a.length()).mapNotNull { i -> val b = a.getJSONObject(i); val type = b.optString("type")
                b.optJSONObject(type)?.optJSONArray("rich_text")?.let { rt -> (0 until rt.length()).joinToString("") { rt.getJSONObject(it).optString("plain_text") } }?.let { if (type.startsWith("heading")) "## $it" else if (type.contains("list")) "- $it" else it } }
                .joinToString("\n").ifBlank { "(본문 없음)" }) },
        "notion.append" to CommandHandler { c -> val (_, t) = token(Service.NOTION, c.args["account"]) ?: return@CommandHandler noAcc("Notion")
            val blocks = JSONArray(c.arg("text").split("\n\n").map { para -> JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", para.take(1900)))))) })
            val (code, txt) = call("PATCH", "https://api.notion.com/v1/blocks/${notionId(c.arg("id"))}/children", nt(t), JSONObject().put("children", blocks).toString()); if (code >= 400) return@CommandHandler err(code, txt)
            CommandResult.ok("노션 페이지에 ${blocks.length()}문단을 추가했어요") },
        "site.read" to CommandHandler { c -> reader.read(c.arg("url"))?.let { CommandResult.ok(it.take(12_000)) } ?: CommandResult.fail("페이지를 읽지 못했어요(로그인이 필요하면 허브 → 로그인 브라우저에서 먼저 로그인)") },
        "account.list" to CommandHandler { _ ->
            val lines = ArrayList<String>()
            aiHub?.state?.value?.accounts?.forEach { lines += "AI · ${it.provider.label} · ${it.label}${if (it.isDefault) " (기본)" else ""}" }
            store.mail.value.forEach { lines += "메일 · ${it.provider} · ${it.email}" }
            accounts.value.forEach { lines += "${it.service.label} · ${it.label}${if (it.isDefault) " (기본)" else ""}" }
            sessions.value.forEach { lines += "로그인 사이트 · $it" }
            CommandResult.ok(lines.joinToString("\n").ifBlank { "연결된 계정이 없어요" }) }
    )

    private fun noAcc(s: String) = CommandResult.fail("$s 계정이 없어요. 허브 → 계정 연결에서 토큰을 넣어 주세요.")
}

/**
 * 로그인 브라우저의 쿠키로 페이지를 읽는다(보이지 않는 WebView, 읽고 바로 파기).
 * 구글은 정책상 앱 안 WebView 로그인을 막으므로 구글 계정 페이지는 대상이 아니다(메일은 IMAP, 캘린더는 기기 캘린더로).
 */
class PageReader(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(url: String): String? = withTimeoutOrNull(25_000) {
        withContext(Dispatchers.Main) {
            val w = WebView(ctx.applicationContext)
            w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
            w.settings.allowFileAccess = false; w.settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            val dm = ctx.resources.displayMetrics
            w.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY))
            w.layout(0, 0, dm.widthPixels, dm.heightPixels)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String?) { if (cont.isActive) cont.resume(Unit) }
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean { if (cont.isActive) cont.resume(Unit); return true }
                    }
                    w.loadUrl(url)
                }
                delay(1_200)   // 화면 그리는 스크립트가 글을 채울 시간
                suspendCancellableCoroutine<String?> { cont ->
                    w.evaluateJavascript("(function(){var t=document.title+'\\n'+(document.body?document.body.innerText:'');return t.slice(0,40000)})()") { r -> if (cont.isActive) cont.resume(DeepSeekWeb.decodeJs(r).ifBlank { null }) }
                }
            } finally { runCatching { w.stopLoading(); w.destroy() } }
        }
    }
}
