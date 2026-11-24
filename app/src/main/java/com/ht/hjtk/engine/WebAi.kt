package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import com.ht.ensemble.agent.LlmPort
import com.ht.ensemble.agent.LlmReply
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.ai.Tokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 키 없이 쓰는 AI 웹 — Claude · ChatGPT · Gemini · Grok.
 * DeepSeek처럼 HJTK 안 브라우저에 한 번 로그인하면(쿠키 유지), 그 화면에 글을 넣고 보내고 답을 읽는다.
 * 사이트마다 다른 것은 '어디에 입력창·보내기·답이 있나'뿐이라 [Site]에 모아 두었다(사이트가 바뀌면 여기만 고친다).
 */
class WebAi(private val app: Context, val site: Site) : LlmPort {
    enum class Status { OFF, LOADING, LOGIN, READY, CHALLENGE, OFFLINE }

    data class Site(
        val provider: Provider, val home: String,
        val input: List<String>, val send: List<String>, val stop: List<String>, val answer: List<String>, val streaming: String? = null,
    ) {
        fun cfg(): String = JSONObject().put("input", org.json.JSONArray(input)).put("send", org.json.JSONArray(send)).put("stop", org.json.JSONArray(stop))
            .put("answer", org.json.JSONArray(answer)).put("streaming", streaming ?: "").toString()
    }

    override val name: String get() = site.provider.label + " 웹"
    val status = MutableStateFlow(Status.OFF)
    val partial = MutableStateFlow("")
    val diag = MutableStateFlow("")
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var cockpit: WeakReference<ViewGroup>? = null
    @Volatile var lastUrl: String? = null; private set
    /** 대화에서 고른 모델(예: "Opus"). 다르면 보내기 전에 사이트의 모델 메뉴에서 고른다 */
    @Volatile var wantModel: String? = null
    private var gotModel: String? = null
    val modelNote = MutableStateFlow("")
    private val mutex = Mutex()
    private var lastUse = 0L
    @Volatile private var pageGen = 0
    private val js by lazy { app.assets.open("webai.js").bufferedReader().use { it.readText() } }
    private fun boot() = "window.__HJTK_CFG=${site.cfg()};" + js

    private fun onMain(b: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post(b) }
    private suspend fun <T> ui(b: () -> T): T = withContext(Dispatchers.Main.immediate) { b() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(): WebView {
        web?.let { return it }
        val w = WebView(app)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            userAgentString = WebUa.chrome(app)
        }
        if (Build.VERSION.SDK_INT >= 26) runCatching { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) { url?.let { lastUrl = it }; if (status.value != Status.READY) status.value = Status.LOADING }
            override fun onPageFinished(v: WebView, url: String?) {
                pageGen++
                CookieManager.getInstance().flush(); url?.let { lastUrl = it }; v.evaluateJavascript(boot(), null)
                if (cockpit?.get() == null) v.evaluateJavascript(Thermal.FREEZE_CSS, null)
                refresh()
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { url?.let { lastUrl = it } }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) { status.value = Status.OFFLINE; diag.value = "네트워크 오류: ${e.description}" }
            }
            // 로그인(구글·애플·MS·X 포함)까지 전부 이 안에서. 앱 열기 링크(intent:// 등)만 막는다
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = r.url.scheme !in setOf("http", "https")
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail?): Boolean {
                diag.value = "${site.provider.label} 화면이 닫혀 다시 여는 중"
                if (web === v) { web = null; runCatching { (v.parent as? ViewGroup)?.removeView(v); v.destroy() } }
                status.value = Status.OFF; return true
            }
        }
        web = w
        status.value = Status.LOADING
        w.loadUrl(lastUrl ?: site.home)
        place()
        return w
    }

    /** 배치: 로그인 화면 > 활동 화면 뒤(안 보이게) > 창 없이 크기만. 0×0이면 사이트가 입력창을 '안 보임'으로 친다 */
    private fun place() {
        val w = web ?: return
        val target = cockpit?.get() ?: WebAiHub.dockHost?.get()?.takeIf { it.isAttachedToWindow }?.let { WebAiHub.shieldFor(app, it) }
        if (target != null) {
            if (w.parent !== target) { (w.parent as? ViewGroup)?.removeView(w); target.addView(w, FrameLayout.LayoutParams(-1, -1)) }
        } else {
            (w.parent as? ViewGroup)?.removeView(w)
            val dm = app.resources.displayMetrics
            w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        w.onResume(); w.resumeTimers()
        // 사용자가 보는 로그인 화면이 아니면 애니메이션·커서 깜빡임을 얼린다(GPU 쉬게: 발열↓)
        w.evaluateJavascript(if (cockpit?.get() != null) Thermal.THAW_CSS else Thermal.FREEZE_CSS, null)
    }

    /** 답을 다 받으면 잠시 뒤 그리기를 멈춘다(쓸 때 다시 깨운다) */
    private val nap = Runnable { if (cockpit?.get() == null && !mutex.isLocked) web?.onPause() }

    /** 로그인 화면에 붙이기(사용자가 직접 로그인) */
    fun attach(container: ViewGroup) = onMain { cockpit = WeakReference(container); ensure(); place(); web?.let { it.isFocusable = true; it.isFocusableInTouchMode = true; it.requestFocus() } }
    fun park() = onMain { cockpit = null; place(); refresh() }
    fun replace() = onMain { place() }
    fun open() = onMain { ensure(); refresh() }
    fun reload() = onMain { web?.reload() ?: ensure() }
    fun home() = onMain { ensure().loadUrl(site.home) }

    fun logout() = onMain {
        val host = android.net.Uri.parse(site.home).host ?: return@onMain
        val cm = CookieManager.getInstance()
        cm.getCookie(site.home)?.split(';')?.forEach { c -> val n = c.substringBefore('=').trim(); if (n.isNotEmpty()) { cm.setCookie(site.home, "$n=; Max-Age=0"); cm.setCookie(".$host", "$n=; Max-Age=0") } }
        cm.flush(); web?.clearCache(false); web?.loadUrl(site.home)
        WebAiHub.setLoggedIn(site.provider, false)
    }

    /** 오래 안 쓰면 메모리를 돌려준다(쿠키는 남아 로그인 유지) */
    fun trim(idleMs: Long = 5 * 60_000L) = onMain {
        if (cockpit?.get() == null && !mutex.isLocked && System.currentTimeMillis() - lastUse > idleMs) web?.let { w -> web = null; runCatching { (w.parent as? ViewGroup)?.removeView(w); w.destroy() }; status.value = Status.OFF }
    }

    fun refresh() = onMain {
        web?.evaluateJavascript("(function(){try{return window.__hjtkAi?window.__hjtkAi.state():'LOADING'}catch(e){return 'LOADING'}})()") { r ->
            val s = runCatching { Status.valueOf(DeepSeekWeb.decodeJs(r)) }.getOrDefault(Status.LOADING)
            status.value = s
            if (s == Status.READY) WebAiHub.setLoggedIn(site.provider, true)
        }
    }

    private suspend fun eval(code: String, ms: Long = 6_000): String = withTimeoutOrNull(ms) {
        val d = CompletableDeferred<String>()
        ui { val w = web; if (w == null) d.complete("") else w.evaluateJavascript("(function(){try{${boot()};return $code}catch(e){return ''}})()") { d.complete(DeepSeekWeb.decodeJs(it)) } }
        d.await()
    } ?: ""

    private suspend fun waitState(ms: Long): Status {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val s = runCatching { Status.valueOf(eval("window.__hjtkAi.state()")) }.getOrDefault(Status.LOADING)
            status.value = s
            if (s == Status.READY || s == Status.LOGIN || s == Status.CHALLENGE) return s
            delay(700)
        }
        return status.value
    }

    /** 구글 계정 하나로: 로그인 화면에서 'Google로 계속' → 계정 → 동의를 대신 누른다(최대 [ms]) */
    suspend fun ssoConnect(loginUrl: String, ms: Long = 60_000, onStep: (String) -> Unit = {}): Boolean {
        ui { ensure(); place() }
        if (waitState(15_000) == Status.READY) return true.also { WebAiHub.setLoggedIn(site.provider, true) }
        ui { web?.loadUrl(loginUrl) }; delay(2_500)
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val st = runCatching { Status.valueOf(eval("window.__hjtkAi.state()")) }.getOrDefault(Status.LOADING)
            if (st == Status.READY) { status.value = st; WebAiHub.setLoggedIn(site.provider, true); return true }
            if (st == Status.CHALLENGE) { onStep("사람 확인이 필요해요 — 화면에서 한 번 눌러 주세요"); delay(3_000); continue }
            val r = eval("window.__hjtkAi.sso('${site.provider.name.lowercase()}')")
            onStep(when { r.startsWith("ACCOUNT") -> "구글 계정을 고르는 중"; r.startsWith("CONSENT") -> "동의하는 중"; r.startsWith("CLICK") -> "Google로 계속 누르는 중"; r.startsWith("LOGIN_PAGE") -> "로그인 화면 여는 중"; else -> "기다리는 중" })
            delay(Thermal.poll(1_800))
        }
        return false
    }

    /** 이어서 쓸 대화방 주소(없으면 새 대화) */
    suspend fun ensureSession(url: String?) { if (url != null && url != lastUrl) ui { ensure().loadUrl(url) } }

    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = mutex.withLock {
        val t0 = System.currentTimeMillis(); lastUse = t0
        fun fail(v: String) = LlmReply("", v, false, System.currentTimeMillis() - t0)
        ui { main.removeCallbacks(nap); ensure(); place() }
        if (newSession) {
            // 새 대화: 페이지가 실제로 새로 뜰 때까지 기다린다(옛 화면에 글을 넣거나 옛 답을 읽는 일 방지)
            val g0 = pageGen
            ui { web?.loadUrl(site.home) }
            withTimeoutOrNull(15_000) { while (pageGen == g0) delay(150) }
            delay(500)
        }
        when (waitState(25_000)) {
            Status.LOGIN -> return fail("NEEDS_LOGIN")
            Status.CHALLENGE -> return fail("CHALLENGE")
            Status.READY -> Unit
            else -> return fail("WEB_NOT_READY")
        }
        wantModel?.takeIf { it != gotModel }?.let { m ->
            val r = eval("window.__hjtkAi.model(${JSONObject.quote(m)})")
            val ok = when (r) { "ALREADY" -> true; "OPENED" -> { delay(600); eval("window.__hjtkAi.choose(${JSONObject.quote(m)})") == "OK" }; else -> false }
            if (ok) { gotModel = m; modelNote.value = "$m 선택됨"; delay(400) } else modelNote.value = "사이트에서 $m 을(를) 찾지 못해 기본 모델로 보냈어요"
        }
        // 지금 화면의 답에 모두 표시 → 이후에 생긴 표시 없는 답만 이번 답으로 인정
        eval("window.__hjtkAi.mark()")
        if (eval("window.__hjtkAi.put(${JSONObject.quote(text)})") == "NO_INPUT") return fail("NO_INPUT")
        try {
            delay(350)
            eval("window.__hjtkAi.submit()")
            var sent = false
            repeat(20) { if (!sent) { delay(Thermal.poll(400)); val left = eval("window.__hjtkAi.pending()").toIntOrNull() ?: 0; val n = eval("window.__hjtkAi.fresh()").toIntOrNull() ?: 0; if (left <= 1 || n > 0) sent = true } }
            if (!sent) { eval("window.__hjtkAi.submit()"); delay(800) }
            // 답 기다리기: 새 답이 생기고, '멈춤' 버튼이 사라지고, 글자가 2.5초 동안 그대로면 끝
            val end = t0 + 6 * 60_000L
            var last = ""; var stableSince = 0L; var appeared = false
            while (System.currentTimeMillis() < end) {
                delay(Thermal.poll(700))
                val cur = eval("window.__hjtkAi.lastFresh()")
                if (cur.isNotBlank()) appeared = true
                if (!appeared) { if (System.currentTimeMillis() - t0 > 75_000) return fail("NO_ANSWER"); continue }
                if (cur != last) { last = cur; stableSince = System.currentTimeMillis(); partial.value = cur; LiveBus.put(jobId.ifBlank { "web" }, name, cur) }
                val busy = eval("window.__hjtkAi.busy()") == "true"
                if (!busy && last.isNotBlank() && System.currentTimeMillis() - stableSince > 2_500) break
            }
            main.postDelayed(nap, if (Thermal.level.value >= 2) 3_000 else 8_000)
            lastUse = System.currentTimeMillis()
            if (last.isBlank()) return fail("NO_ANSWER")
            UsageLog.add(name, Tokens.estimate(text), Tokens.estimate(last))
            LlmReply(last, "WEB_${site.provider.name}", true, System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 작업 중지: 사이트에서도 생성을 멈춘다(계속 일하지 않게)
            withContext(kotlinx.coroutines.NonCancellable) { eval("window.__hjtkAi.stop()", 2_000) }
            throw e
        } finally { LiveBus.clear(jobId.ifBlank { "web" }, name); partial.value = "" }
    }

    /** 진단: 페이지를 열어(필요하면) 화면 구조 요약 JSON을 돌려준다 */
    suspend fun probe(): String {
        ui { ensure(); place() }
        if (web?.url.isNullOrBlank() || web?.url == "about:blank") { ui { web?.loadUrl(site.home) }; delay(4_000) }
        return eval("window.__hjtkAi ? window.__hjtkAi.probe() : '{\"error\":\"어댑터 없음\"}'", 6_000).ifBlank { "{\"error\":\"응답 없음\"}" }
    }

    /** 밖에서 멈추기(작업 중지 버튼) */
    suspend fun stopNow() { eval("window.__hjtkAi.stop()", 2_000) }
}

/** 웹 AI 네 개를 한곳에서: 사이트 정의 · 로그인 상태 · 활동 화면 뒤에 숨기기 */
object WebAiHub {
    val sites = listOf(
        WebAi.Site(Provider.CLAUDE, "https://claude.ai/new",
            input = listOf("div[contenteditable=\"true\"].ProseMirror", "fieldset div[contenteditable=\"true\"]", "div[contenteditable=\"true\"]"),
            send = listOf("button[aria-label=\"Send message\"]", "button[aria-label=\"메시지 보내기\"]", "fieldset button[type=\"submit\"]"),
            stop = listOf("button[aria-label=\"Stop response\"]", "button[aria-label*=\"Stop\"]", "button[aria-label*=\"중지\"]"),
            answer = listOf("div.font-claude-response", ".font-claude-message", "[data-is-streaming] .standard-markdown", "[data-is-streaming]"),
            streaming = "[data-is-streaming=\"true\"]"),
        WebAi.Site(Provider.GPT, "https://chatgpt.com/",
            input = listOf("#prompt-textarea", "div[contenteditable=\"true\"]#prompt-textarea", "textarea[name=\"prompt-textarea\"]", "form textarea"),
            send = listOf("button[data-testid=\"send-button\"]", "#composer-submit-button", "button[aria-label*=\"Send\"]", "button[aria-label*=\"보내기\"]"),
            stop = listOf("button[data-testid=\"stop-button\"]", "button[aria-label*=\"Stop\"]", "button[aria-label*=\"중지\"]"),
            answer = listOf("[data-message-author-role=\"assistant\"]")),
        WebAi.Site(Provider.GEMINI, "https://gemini.google.com/app",
            input = listOf("rich-textarea .ql-editor", "div.ql-editor[contenteditable=\"true\"]", "div[contenteditable=\"true\"][role=\"textbox\"]"),
            send = listOf("button.send-button", "button[aria-label*=\"Send\"]", "button[aria-label*=\"보내기\"]"),
            stop = listOf("button[aria-label*=\"Stop\"]", "button[aria-label*=\"중지\"]"),
            answer = listOf("model-response message-content", "message-content .markdown", ".model-response-text")),
        WebAi.Site(Provider.GROK, "https://grok.com/",
            input = listOf("textarea[aria-label]", "div[contenteditable=\"true\"]", "form textarea"),
            send = listOf("button[type=\"submit\"]", "button[aria-label=\"Submit\"]", "button[aria-label=\"제출\"]"),
            stop = listOf("button[aria-label*=\"Stop\"]", "button[aria-label*=\"중지\"]"),
            answer = listOf("div.items-start .message-bubble", "[class*=\"items-start\"] .message-bubble", ".response-content-markdown")),
    )

    private var ctx: Context? = null
    private val ports = HashMap<Provider, WebAi>()
    val loggedIn = MutableStateFlow<Set<Provider>>(emptySet())
    @Volatile var dockHost: WeakReference<ViewGroup>? = null
    private var shield: FrameLayout? = null

    fun install(app: Context) {
        ctx = app
        val p = app.getSharedPreferences("webai", Context.MODE_PRIVATE)
        loggedIn.value = p.getStringSet("in", emptySet())!!.mapNotNull { Provider.of(it) }.toSet()
    }

    fun port(p: Provider): WebAi? { val c = ctx ?: return null; val s = sites.firstOrNull { it.provider == p } ?: return null; return synchronized(ports) { ports.getOrPut(p) { WebAi(c, s) } } }

    fun setLoggedIn(p: Provider, on: Boolean) {
        if (on) Scoreboard.clearSoft("${p.name}:WEB:-")      // 다시 로그인되면 '로그인 필요'로 쉬던 것을 바로 풀어 준다
        val now = if (on) loggedIn.value + p else loggedIn.value - p
        if (now == loggedIn.value) return
        loggedIn.value = now
        ctx?.getSharedPreferences("webai", Context.MODE_PRIVATE)?.edit()?.putStringSet("in", now.map { it.name }.toSet())?.apply()
    }

    /** 활동 화면 뒤(사용자 눈에 안 보이는 곳)에 웹 화면을 둔다: 창에 붙어 있어야 사이트가 정상 동작한다 */
    fun dock(host: ViewGroup) { dockHost = WeakReference(host); synchronized(ports) { ports.values.forEach { it.replace() } } }
    fun undock() { shield?.let { (it.parent as? ViewGroup)?.removeView(it) }; dockHost = null; synchronized(ports) { ports.values.forEach { it.replace() } } }
    internal fun shieldFor(c: Context, host: ViewGroup): FrameLayout {
        val s = shield ?: object : FrameLayout(c) { override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = true }.also { shield = it }
        if (s.parent !== host) { (s.parent as? ViewGroup)?.removeView(s); host.addView(s, 0, FrameLayout.LayoutParams(-1, -1)) }
        return s
    }
    fun trimAll() = synchronized(ports) { ports.values.forEach { it.trim() } }
}

/**
 * 보이지 않는 브라우저로 검색: 검색 사이트가 앱의 HTTP 요청을 막을 때(로봇 차단·화면 개편) 진짜 브라우저 화면에서 결과를 읽는다.
 * 구글(로그인돼 있으면 그 계정으로) → 안 되면 네이버 모바일. 결과 링크만 가져온다.
 */
object Scout {
    private var app: Context? = null
    private var web: WebView? = null
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    fun install(c: Context) { app = c.applicationContext }

    data class Hit(val title: String, val url: String, val snippet: String)

    suspend fun search(q: String, n: Int): List<Hit> = lock.withLock {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        for (url in listOf("https://www.google.com/search?q=$enc&hl=ko&num=12", "https://m.search.naver.com/search.naver?query=$enc")) {
            val r = load(url)
            if (r.size >= 3) return@withLock r.take(n)
        }
        emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String): List<Hit> {
        val c = app ?: return emptyList()
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val w = web ?: WebView(c).also { v ->
                v.settings.javaScriptEnabled = true; v.settings.domStorageEnabled = true; v.settings.userAgentString = WebUa.chrome(c)
                v.settings.blockNetworkImage = true                       // 이미지 안 받기: 빠르고 덜 뜨겁다
                CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
                val dm = c.resources.displayMetrics
                v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, dm.widthPixels, dm.heightPixels)
                web = v
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String?) { if (!done.isCompleted) done.complete(Unit) }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.scheme !in setOf("http", "https")
            }
            w.onResume(); w.loadUrl(url)
        }
        withTimeoutOrNull(15_000) { done.await() }
        delay(700)
        val raw = withTimeoutOrNull(5_000) {
            val d = CompletableDeferred<String>()
            withContext(Dispatchers.Main) { web?.evaluateJavascript(JS) { d.complete(DeepSeekWeb.decodeJs(it)) } ?: d.complete("[]") }
            d.await()
        } ?: "[]"
        main.postDelayed({ web?.onPause() }, 3_000)
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i); var u = o.optString("u")
                if (u.contains("/url?")) u = android.net.Uri.parse(u).getQueryParameter("q") ?: u
                if (!u.startsWith("http") || Regex("""google\.|gstatic|search\.naver|naver\.com/search|accounts\.""").containsMatchIn(android.net.Uri.parse(u).host.orEmpty() + u.take(40))) null
                else Hit(o.optString("t").trim(), u, o.optString("s").trim())
            }.distinctBy { it.url }
        }.getOrDefault(emptyList())
    }

    private const val JS = """(function(){var out=[],seen={};function add(a,t,s){var u=a.href;if(!u||seen[u])return;seen[u]=1;out.push({t:t,u:u,s:(s||'').slice(0,220)});}
document.querySelectorAll('a h3').forEach(function(h){var a=h.closest('a');if(!a)return;var box=a.closest('[data-hveid],div.g,div.MjjYud,div.Gx5Zad');add(a,h.innerText,box?box.innerText.replace(h.innerText,''):'');});
if(out.length<3){document.querySelectorAll('a').forEach(function(a){var t=(a.innerText||'').trim().split('
')[0];if(t.length>10&&t.length<120&&/^https?:/.test(a.href)){var b=a.closest('li,div');add(a,t,b?b.innerText.replace(t,''):'');}});}
return JSON.stringify(out.slice(0,15));})()"""
}

/** 일반 크롬처럼 보이는 UA(웹뷰 표시 'wv' 없이). DeepSeek 창과 같은 값 */
object WebUa {
    fun chrome(c: Context): String {
        val v = runCatching { WebViewCompat.getCurrentWebViewPackage(c)?.versionName?.replace(Regex("[^0-9.]"), "") }.getOrNull()?.ifBlank { null } ?: "131.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Mobile Safari/537.36"
    }
}
