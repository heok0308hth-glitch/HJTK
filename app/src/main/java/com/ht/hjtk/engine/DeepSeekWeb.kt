package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ht.hjtk.core.agent.LlmPort
import com.ht.hjtk.core.agent.LlmReply
import com.ht.hjtk.core.stream.DeepSeekPatchAssembler
import com.ht.hjtk.core.stream.DeltaExtractor
import com.ht.hjtk.core.stream.HistoryExtract
import com.ht.hjtk.core.stream.SnapshotReconciler
import com.ht.hjtk.core.stream.SseParser
import com.ht.hjtk.core.stream.TerminationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * DeepSeek 무키 양방향 — 앱 안의 인증 WebView 컨테이너(L4′).
 *
 * 1.1에서 바뀐 세 가지(실기기 '오래 기다리다 튕김'의 원인):
 *  ① WebView는 메인 스레드 전용이다. 밖에서 읽던 url은 메인에서 기록한 사본(lastUrl)으로 바꿨다.
 *  ② 렌더러가 죽으면(메모리) onRenderProcessGone을 처리하지 않을 경우 앱 전체가 죽는다. 이제 정리 후 재생성하고,
 *     답은 같은 대화방을 다시 열어 화면에서 회수한다.
 *  ③ 붙어 있지 않은 WebView는 레이아웃이 0이라 페이지가 반쯤만 돈다. 앱이 보이는 동안에는 화면 '뒤'에 도킹한다
 *     (터치·포커스 차단). 밖으로 나가면 투명 창(권한 있을 때) 또는 수동 레이아웃으로 옮긴다.
 */
class DeepSeekWeb(private val app: Context) : LlmPort {
    override val name = "DeepSeek"
    enum class Status { OFF, LOADING, LOGIN, READY, CHALLENGE, OFFLINE }

    val status = MutableStateFlow(Status.OFF)
    val lastVia = MutableStateFlow("")
    /** 조종석 아래 한 줄 진단(사람이 읽는 상태) */
    val diag = MutableStateFlow("")
    /** 지금 받는 중인 답(스트림이 열린 동안 계속 갱신) — 대화 화면이 글자를 실시간으로 그린다 */
    val partial = MutableStateFlow("")

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var web: WebView? = null
    @Volatile private var lastUrl: String? = null
    @Volatile private var frameError: String? = null
    @Volatile private var foreground = false
    @Volatile var renderDeaths = 0; private set

    private var parking: FrameLayout? = null
    private var shield: Shield? = null
    private var dockHost: WeakReference<ViewGroup>? = null
    private var cockpit: WeakReference<FrameLayout>? = null
    private var tapJs: String? = null

    private val events = Channel<Tap>(Channel.UNLIMITED)
    private val mutex = Mutex()

    sealed interface Tap {
        data class Open(val id: String, val url: String, val ct: String, val status: Int) : Tap
        data class Chunk(val id: String, val text: String) : Tap
        data class End(val id: String, val why: String) : Tap
        data class Clip(val text: String) : Tap
        data class Hist(val json: String) : Tap
    }

    /** 브리지는 텍스트만 받는다. 파일·네트워크·실행 권한을 주지 않는다. (JS 스레드에서 호출됨 → 채널로만 넘긴다) */
    inner class Bridge {
        @JavascriptInterface fun open(id: String, url: String, ct: String, st: Int) { events.trySend(Tap.Open(id, url, ct, st)) }
        @JavascriptInterface fun chunk(id: String, text: String) { if (text.length <= 400_000) events.trySend(Tap.Chunk(id, text)) }
        @JavascriptInterface fun end(id: String, why: String) { events.trySend(Tap.End(id, why)) }
        @JavascriptInterface fun clip(text: String) { if (text.length in 2..400_000) events.trySend(Tap.Clip(text)) }
        @JavascriptInterface fun href(url: String) { if (url.startsWith(ORIGIN)) lastUrl = url }
        @JavascriptInterface fun hist(json: String) { events.trySend(Tap.Hist(json)) }
        @JavascriptInterface fun protocolVersion(): Int = 2
    }

    /** 화면 뒤 도킹용 껍데기: 터치·포커스·접근성을 모두 막아 사용자는 존재를 모른다. */
    private class Shield(c: Context) : FrameLayout(c) {
        init {
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
        @SuppressLint("ClickableViewAccessibility") override fun onTouchEvent(ev: MotionEvent) = false
    }

    // ── 스레드 규칙 ───────────────────────────────────────────────
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private suspend fun <T> ui(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    // ── WebView 생성/파기 (메인 전용) ─────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView {
        web?.let { return it }
        val w = WebView(app)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = chromeUa()
        }
        // 화면 밖에 있어도 렌더러 우선순위를 낮추지 않는다(낮추면 시스템이 먼저 죽인다)
        if (Build.VERSION.SDK_INT >= 26) runCatching { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "HJTKTap")
        val js = tap()
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) runCatching { WebViewCompat.addDocumentStartJavaScript(w, js, setOf(ORIGIN)) }
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                frameError = null
                url?.let { lastUrl = it }
                if (status.value != Status.READY) status.value = Status.LOADING
                if (!docStart) v.evaluateJavascript(js, null)
            }
            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()          // 빠뜨리면 재시작마다 로그아웃
                url?.let { lastUrl = it }
                v.evaluateJavascript(js, null)               // document-start가 놓친 경우 대비(중복 실행은 스크립트가 막는다)
                refreshState()
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { url?.let { lastUrl = it } }   // SPA 내부 이동(/a/chat/s/…)
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) { frameError = "${e.errorCode} ${e.description}"; status.value = Status.OFFLINE; diag.value = "네트워크 오류: ${e.description}" }
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                // DeepSeek과 로그인(구글·애플 등 제3자 로그인 포함)은 안에서, 나머지는 밖으로
                return !(u.startsWith(ORIGIN) || u.contains("deepseek.com") || u.contains("accounts.google.com") || u.contains("appleid.apple.com"))
            }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail?): Boolean {
                // true를 돌려주지 않으면 안드로이드가 앱 프로세스까지 종료한다.
                renderDeaths++
                diag.value = if (d?.didCrash() == true) "DeepSeek 화면이 오류로 닫혔어요 · 다시 여는 중" else "메모리가 부족해 DeepSeek 화면이 닫혔어요 · 다시 여는 중"
                if (web === v) teardown() else runCatching { (v.parent as? ViewGroup)?.removeView(v); v.destroy() }
                events.trySend(Tap.End("*", "gone"))
                status.value = Status.OFF
                return true
            }
        }
        web = w
        status.value = Status.LOADING
        w.loadUrl(lastUrl?.takeIf { it.startsWith(ORIGIN) } ?: ORIGIN)
        place()
        return w
    }

    private fun teardown() {
        val w = web ?: return
        web = null
        runCatching { (w.parent as? ViewGroup)?.removeView(w) }
        removeParking()
        runCatching { w.stopLoading(); w.destroy() }
    }

    private fun tap(): String = tapJs ?: app.assets.open("tap.js").bufferedReader().use { it.readText() }.also { tapJs = it }

    // ── 배치: 조종석 > 화면 뒤 도킹 > 투명 창 > 수동 레이아웃 ─────────
    private fun place() {
        val w = web ?: return
        cockpit?.get()?.let { c -> moveTo(w, c); return }   // 조종석은 park()가 풀 때까지 우선(아직 창에 붙기 전이어도)
        val host = dockHost?.get()
        if (foreground && host != null && host.isAttachedToWindow) {
            val s = shield ?: Shield(app).also { shield = it }
            if (s.parent !== host) { (s.parent as? ViewGroup)?.removeView(s); host.addView(s, 0, FrameLayout.LayoutParams(-1, -1)) }
            moveTo(w, s); return
        }
        if (Settings.canDrawOverlays(app) && overlayPark(w)) return
        // 마지막 수단: 창 없이도 레이아웃 크기를 준다(0×0이면 입력창·버튼이 '보이지 않음'으로 계산된다)
        (w.parent as? ViewGroup)?.removeView(w); removeParking()
        val dm = app.resources.displayMetrics
        w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
        w.onResume(); w.resumeTimers()
    }

    // ── 발열: 쓰지 않는 동안 렌더링을 멈춘다(DeepSeek 페이지의 커서 깜빡임 같은 상시 그리기 차단) ──
    private val pauseIdle = Runnable { if (cockpit?.get() == null && !mutex.isLocked) web?.onPause() }
    private fun scheduleIdle() { main.removeCallbacks(pauseIdle); main.postDelayed(pauseIdle, if (Thermal.hot.value) 8_000L else 20_000L) }

    private fun moveTo(w: WebView, parent: ViewGroup) {
        if (w.parent !== parent) {
            (w.parent as? ViewGroup)?.removeView(w)
            if (parent !== parking) removeParking()
            parent.addView(w, FrameLayout.LayoutParams(-1, -1))
        }
        w.onResume(); w.resumeTimers(); scheduleIdle()
    }

    private fun overlayPark(w: WebView): Boolean {
        if (parking != null && w.parent === parking) { w.onResume(); w.resumeTimers(); return true }
        return runCatching {
            val wm = app.getSystemService(WindowManager::class.java)
            val box = FrameLayout(app)
            val dm = app.resources.displayMetrics
            val lp = WindowManager.LayoutParams(dm.widthPixels, (dm.heightPixels * 0.7).toInt(),
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0f }
            (w.parent as? ViewGroup)?.removeView(w)
            removeParking()
            box.addView(w, FrameLayout.LayoutParams(-1, -1))
            wm.addView(box, lp); parking = box
            w.onResume(); w.resumeTimers()
            true
        }.getOrDefault(false)
    }

    private fun removeParking() {
        val p = parking ?: return
        parking = null
        runCatching { (p.getChildAt(0) as? WebView)?.let { p.removeView(it) }; app.getSystemService(WindowManager::class.java).removeView(p) }
    }

    // ── 수명주기 훅(모두 메인에서 호출되지만, 어디서 불려도 안전하게) ─────
    /** 액티비티가 보일 때: 화면 뒤에 붙인다. 첫 프레임을 그린 뒤 유휴 시간에 만든다(시작 속도). */
    fun dock(host: ViewGroup) = onMain {
        foreground = true
        dockHost = WeakReference(host)
        Looper.myQueue().addIdleHandler { runCatching { ensureWeb(); place(); refreshState() }; false }
    }

    /** 액티비티가 가려질 때: 액티비티 창에서 떼어낸다(누수 방지) */
    fun undock() = onMain {
        foreground = false
        shield?.let { s -> (s.parent as? ViewGroup)?.removeView(s) }
        dockHost = null
        place()
    }

    /** 조종석 화면에 붙이기(사용자가 직접 보고 조작) */
    fun attach(container: FrameLayout) = onMain {
        main.removeCallbacks(pauseIdle)
        cockpit = WeakReference(container)
        ensureWeb(); place()
        web?.let { w -> w.isFocusable = true; w.isFocusableInTouchMode = true }
    }

    /** 조종석에서 내려왔다/앱이 밖으로 나갔다 */
    fun park() = onMain { cockpit = null; place() }

    fun refreshState() = onMain {
        val w = web ?: return@onMain
        w.evaluateJavascript(STATE_JS) { r -> applyState(decodeJs(r)) }
    }

    private fun applyState(s: String): Status {
        if (s == "NOTAP") { web?.evaluateJavascript(tap(), null) }
        val st = when (s) { "READY" -> Status.READY; "LOGIN" -> Status.LOGIN; "CHALLENGE" -> Status.CHALLENGE; else -> if (frameError != null) Status.OFFLINE else Status.LOADING }
        status.value = st
        diag.value = when (st) {
            Status.READY -> "연결됨"; Status.LOGIN -> "로그인이 필요해요"; Status.CHALLENGE -> "사람 확인이 필요해요"
            Status.OFFLINE -> "네트워크에 연결할 수 없어요"; else -> "여는 중"
        }
        return st
    }

    fun newChat() = onMain { ensureWeb().loadUrl(ORIGIN) }
    fun load(url: String) = onMain { ensureWeb().loadUrl(url) }
    fun reload() = onMain { if (web == null) ensureWeb() else web?.reload() }
    /** 스레드 안전: 메인에서 기록한 마지막 주소 */
    fun currentUrl(): String? = lastUrl

    fun logout() = onMain {
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        lastUrl = null
        web?.loadUrl(ORIGIN)
        status.value = Status.LOGIN
    }

    /** 렌더러가 죽었거나 멈췄으면 응답이 영영 오지 않는다 → 5초 상한 */
    private suspend fun js(code: String, timeoutMs: Long = 5_000): String = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main.immediate) {
            val w = web ?: return@withContext ""
            suspendCancellableCoroutine { cont -> w.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(decodeJs(r)) } }
        }
    } ?: ""

    private suspend fun waitReady(timeoutMs: Long): Status {
        val end = System.currentTimeMillis() + timeoutMs
        var n = 0
        while (System.currentTimeMillis() < end) {
            if (frameError != null) { status.value = Status.OFFLINE; return Status.OFFLINE }
            if (web == null) ui { ensureWeb() }
            val s = js(STATE_JS)
            val st = ui { applyState(s) }
            if (st == Status.READY || st == Status.LOGIN || st == Status.CHALLENGE) return st
            delay(if (n++ < 12) 250 else 600)   // 로컬 DOM 확인(네트워크 폴링 아님)
        }
        return status.value
    }

    /** 세션 URL로 복귀(작업 재개 시 같은 대화방을 이어 쓴다) */
    suspend fun ensureSession(url: String?) {
        if (url.isNullOrBlank()) return
        val moved = ui { val w = ensureWeb(); if (lastUrl != url) { w.loadUrl(url); true } else false }
        if (moved) delay(400)
        waitReady(15_000)
    }

    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = mutex.withLock {
        ui { main.removeCallbacks(pauseIdle); web?.onResume() }
        try {
            sendLocked(text, newSession).also { r -> if (r.text.isNotBlank()) UsageLog.add("DeepSeek", com.ht.hjtk.core.ai.Tokens.estimate(text), com.ht.hjtk.core.ai.Tokens.estimate(r.text)) }
        } finally { onMain { scheduleIdle() } }
    }

    private suspend fun sendLocked(text: String, newSession: Boolean): LlmReply {
        val t0 = System.currentTimeMillis()
        val r = attempt(text, newSession, t0)
        if (r.via != VIA_GONE) return r.also { lastVia.value = it.via }
        // 렌더러가 답하는 도중 죽었다: 질문은 이미 DeepSeek에 들어갔을 수 있다 → 다시 보내지 말고 대화방을 열어 답을 회수
        val back = recover(t0)
        return (back ?: LlmReply("", VIA_GONE, false, System.currentTimeMillis() - t0)).also { lastVia.value = it.via }
    }

    private suspend fun attempt(text: String, newSession: Boolean, t0: Long): LlmReply {
        fun fail(via: String) = LlmReply("", via, false, System.currentTimeMillis() - t0)
        partial.value = ""
        ui { ensureWeb(); place() }
        if (newSession) freshChat()
        when (waitReady(if (newSession) 20_000 else 15_000)) {
            Status.LOGIN -> return fail("NEEDS_LOGIN")
            Status.CHALLENGE -> return fail("CHALLENGE")
            Status.OFFLINE -> return fail("OFFLINE")
            Status.LOADING, Status.OFF -> return fail("NOT_READY")
            Status.READY -> {}
        }
        while (events.tryReceive().isSuccess) { /* 이전 잔여 이벤트 비우기 */ }
        diag.value = "보내는 중"

        // 기준점: 지금 화면의 답 개수/마지막 답. 이후 이것과 다른 '새 답'만 인정한다.
        val mark = runCatching { JSONObject(js("window.__hjtkMark?window.__hjtkMark():'{}'").ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val round = Round(mark.optInt("n", 0), mark.optString("last", ""), text)

        val put = js("window.__hjtkSend(${JSONObject.quote(text)})")
        if (put.isEmpty()) return fail(if (web == null) VIA_GONE else "NOT_READY")
        if (!put.startsWith("OK")) return fail("INPUT_$put")
        delay(120)
        js("window.__hjtkEnter()")

        // 제출 확인: 스트림이 열렸거나 입력창이 비었으면(React가 비움) 보낸 것이다.
        // 보낸 뒤에는 절대 버튼을 누르지 않는다 — 생성 중의 그 자리는 '중지' 버튼이라 답을 취소해 버린다.
        val started = System.currentTimeMillis()
        var clicked = false; var native = false; var submitted = false
        while (System.currentTimeMillis() - started < 7_000) {
            if (round.waitOpen(250)) { submitted = true; break }
            val box = js(BOX_JS, 1_500).toIntOrNull() ?: -1
            if (box == 0) { submitted = true; break }
            val el = System.currentTimeMillis() - started
            if (!clicked && el > 1_000) { clicked = true; js("window.__hjtkClickSend()") }
            else if (!native && el > 3_000) {
                native = true
                ui {
                    web?.let { w ->
                        val now = android.os.SystemClock.uptimeMillis()
                        w.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0))
                        w.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0))
                    }
                }
            }
        }
        if (!submitted) return fail("NOT_SENT")
        diag.value = "답을 받는 중"
        val r = round.collect(t0)
        js("location.href", 1_500).takeIf { it.startsWith(ORIGIN) }?.let { lastUrl = it }
        diag.value = if (r.text.isNotEmpty()) "연결됨 · ${r.via}" else "답을 읽지 못함 · ${r.via}"
        return r
    }

    /** 새 대화: 페이지를 다시 읽지 않고 DeepSeek의 '새 대화' 버튼을 누른다(2~4초 → 0.3초). 실패하면 주소를 연다. */
    private suspend fun freshChat() {
        val onChatPage = lastUrl?.let { it.startsWith(ORIGIN) && !it.contains("sign_in") } == true && status.value == Status.READY
        if (onChatPage) {
            val path = lastUrl.orEmpty().removePrefix(ORIGIN)
            if (path == "" || path == "/") return   // 이미 빈 새 대화
            if (js("window.__hjtkNewChat?window.__hjtkNewChat():'NONE'") == "OK") {
                repeat(10) { delay(120); val u = js("location.pathname", 800); if (u == "/" || u.isEmpty()) { lastUrl = ORIGIN; return } }
            }
        }
        ui { web?.loadUrl(ORIGIN) }
        delay(400)
    }

    private suspend fun recover(t0: Long): LlmReply? {
        val session = lastUrl?.takeIf { it.contains("/chat/") || it.contains("/a/") } ?: return null
        delay(800)
        ui { ensureWeb(); web?.loadUrl(session) }
        if (waitReady(20_000) != Status.READY) return null
        // 답이 아직 생성 중일 수 있으니 화면 글자가 멈출 때까지 본다(최대 90초)
        var last = ""; var same = 0
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end && same < 3) {
            delay(1_500)
            val now = js(LAST_ANSWER_JS).trim()
            if (now.isNotEmpty() && now == last) same++ else same = 0
            last = now
        }
        return last.takeIf { it.isNotEmpty() }?.let { LlmReply(it, "P1_RECOVERED", false, System.currentTimeMillis() - t0) }
    }

    /**
     * 한 번의 왕복. 세 개의 독립된 출처를 대조한다.
     *  P0 스트림(페이지 자신의 응답을 지나가며 읽음) · P1 화면의 '새' 답 노드 · P2 서버 기록(보낸 질문과 짝이 맞는 답)
     * 하나가 놓쳐도 다른 하나가 받는다. 이전 답은 기준점(n0,last0) 때문에 절대 새 답으로 오인되지 않는다.
     */
    private inner class Round(private val n0: Int, private val last0: String, private val prompt: String) {
        private var streamId: String? = null
        private val asm = DeepSeekPatchAssembler()
        private val rec = SnapshotReconciler()
        private val sse = SseParser { data ->
            if (!asm.accept(data)) {
                val f = DeltaExtractor.parse(data)
                f.answer?.let { rec.accept(it) }
                if (f.done) generic = true
            }
        }
        private var generic = false
        private var ended = false
        var gone = false; private set
        private var clip: String? = null
        private var hist: String? = null
        private val gate = TerminationGate()
        private val buffered = ArrayList<Tap.Chunk>()

        suspend fun waitOpen(ms: Long): Boolean {
            if (streamId != null) return true
            return withTimeoutOrNull(ms) {
                while (streamId == null && !gone) handle(events.receive())
                streamId != null
            } ?: false
        }

        private fun handle(t: Tap) {
            when (t) {
                is Tap.Open -> if (streamId == null) {
                    streamId = t.id
                    buffered.filter { it.id == t.id }.forEach { sse.feed(it.text) }; buffered.clear()
                }
                is Tap.Chunk -> if (t.id == streamId) { sse.feed(t.text); gate.onChunk(System.currentTimeMillis()) } else if (streamId == null && buffered.size < 200) buffered += t
                is Tap.End -> when {
                    t.why == "gone" -> gone = true
                    t.id == streamId -> { sse.finish(); ended = true }
                }
                is Tap.Clip -> clip = t.text
                is Tap.Hist -> hist = t.json
            }
        }

        fun answer(): String = asm.answer().ifEmpty { rec.text() }

        private suspend fun domNew(): String = js("window.__hjtkNewAnswer?window.__hjtkNewAnswer($n0,${JSONObject.quote(last0)}):''").trim()

        /** 화면의 새 답이 더 이상 자라지 않을 때까지(연속 2회 동일) */
        private suspend fun domStable(maxMs: Long): String {
            var prev = ""; var same = 0
            val end = System.currentTimeMillis() + maxMs
            while (System.currentTimeMillis() < end && same < 2) {
                val d = domNew()
                if (d.isNotEmpty() && d == prev) same++ else same = 0
                prev = d
                if (same < 2) delay(500)
            }
            return prev
        }

        private suspend fun history(): String? {
            hist = null
            if (js("window.__hjtkHistory?window.__hjtkHistory():'NONE'") != "OK") return null
            withTimeoutOrNull(6_000) { while (hist == null) handle(events.receive()) }
            return hist?.let { HistoryExtract.lastAnswerFor(it, prompt) }
        }

        suspend fun collect(t0: Long): LlmReply {
            fun reply(text: String, via: String, done: Boolean) = LlmReply(text, via, done, System.currentTimeMillis() - t0)
            gate.start(System.currentTimeMillis())
            var lastDom = 0L; var domPrev = ""; var domSame = 0
            while (!gone) {
                val now = System.currentTimeMillis()
                val a = answer()
                if (a.isNotEmpty() && a.length != partial.value.length) partial.value = a
                val v = gate.poll(now, a.length, asm.finished || generic || ended, false)
                if (v != TerminationGate.Verdict.RUNNING) break
                // 스트림을 못 잡고 있다면 화면을 곁눈질: 새 답이 완성돼 멈춰 있으면 기다리지 않고 바로 받는다
                if (streamId == null && now - lastDom > 1_200) {
                    lastDom = now
                    val d = domNew()
                    if (d.isNotEmpty()) { partial.value = d; if (d == domPrev) domSame++ else domSame = 0; domPrev = d }
                    if (domSame >= 2) return reply(d, "P1_DOM", false)
                }
                withTimeoutOrNull(250) { handle(events.receive()) }
            }
            val a = answer().trim()
            if (gone) return reply(a, if (a.isEmpty()) VIA_GONE else "P0_PARTIAL", false)
            val streamDone = asm.finished || ended || generic
            if (a.isNotEmpty() && streamDone) return reply(a, "P0_STREAM", true)
            // 스트림이 비었거나 끝 표시 없이 멈췄다 → 화면의 새 답과 대조해 더 긴 쪽
            val dom = domStable(if (a.isEmpty()) 6_000 else 2_500)
            if (web == null) return reply("", VIA_GONE, false)
            if (dom.length > a.length) return reply(dom, "P1_DOM", false)
            if (a.isNotEmpty()) return reply(a, "P0_STREAM", false)
            history()?.let { return reply(it, "P2_HISTORY", true) }
            clip?.let { return reply(it, "P4_CLIP", false) }
            return reply("", "NONE", false)
        }
    }

    private fun chromeUa(): String {
        val v = runCatching { WebViewCompat.getCurrentWebViewPackage(app)?.versionName?.replace(Regex("[^0-9.]"), "") }.getOrNull()?.ifBlank { null } ?: "131.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Mobile Safari/537.36"
    }

    companion object {
        const val ORIGIN = "https://chat.deepseek.com"
        const val VIA_GONE = "RENDERER_GONE"
        private const val STATE_JS = "(function(){try{if(window.__hjtkState)return window.__hjtkState();return document.readyState==='complete'?'NOTAP':'LOADING'}catch(e){return 'LOADING'}})()"
        private const val LAST_ANSWER_JS = "(window.__hjtkLastAnswer&&window.__hjtkLastAnswer())||''"
        private const val BOX_JS = "(window.__hjtkBox?window.__hjtkBox():-1)"
        fun decodeJs(r: String?): String {
            val t = r ?: return ""
            if (t == "null") return ""
            return if (t.startsWith("\"")) runCatching { org.json.JSONArray("[$t]").getString(0) }.getOrDefault(t.trim('"')) else t
        }
    }
}
