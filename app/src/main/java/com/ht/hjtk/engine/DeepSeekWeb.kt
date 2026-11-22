package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import kotlin.coroutines.resume

/**
 * DeepSeek 무키 양방향 — 앱 안의 인증 WebView 컨테이너(L4′).
 * 사용자는 chat.deepseek.com에 한 번 로그인한다. 이후 HJTK는
 *  ① 입력창에 봉투(AEP-1)를 넣고 ② 페이지 자신의 fetch 스트림을 tee로 복제해(P0) 전문을 회수한다.
 * 페이지가 PoW·쿠키·봇 검사를 직접 풀기 때문에 우리는 관찰만 한다. API 키는 쓰지 않는다.
 */
class DeepSeekWeb(private val app: Context) : LlmPort {
    override val name = "DeepSeek"
    enum class Status { OFF, LOADING, LOGIN, READY, CHALLENGE }

    val status = MutableStateFlow(Status.OFF)
    val lastVia = MutableStateFlow("")
    private var web: WebView? = null
    private var parking: FrameLayout? = null
    private val events = Channel<Tap>(Channel.UNLIMITED)
    private val mutex = Mutex()

    sealed interface Tap {
        data class Open(val id: String, val url: String, val ct: String, val status: Int) : Tap
        data class Chunk(val id: String, val text: String) : Tap
        data class End(val id: String, val why: String) : Tap
        data class Clip(val text: String) : Tap
    }

    /** 브리지는 텍스트만 받는다. 파일·네트워크·실행 권한을 주지 않는다. */
    inner class Bridge {
        @JavascriptInterface fun open(id: String, url: String, ct: String, st: Int) { events.trySend(Tap.Open(id, url, ct, st)) }
        @JavascriptInterface fun chunk(id: String, text: String) { if (text.length <= 400_000) events.trySend(Tap.Chunk(id, text)) }
        @JavascriptInterface fun end(id: String, why: String) { events.trySend(Tap.End(id, why)) }
        @JavascriptInterface fun clip(text: String) { if (text.length in 2..400_000) events.trySend(Tap.Clip(text)) }
        @JavascriptInterface fun protocolVersion(): Int = 1
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "HJTKTap")
        val js = app.assets.open("tap.js").bufferedReader().use { it.readText() }
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(w, js, setOf(ORIGIN))
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                status.value = Status.LOADING
                if (!docStart) v.evaluateJavascript(js, null)
            }
            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()          // 빠뜨리면 재시작마다 로그아웃
                if (!docStart) v.evaluateJavascript(js, null)
                refreshState()
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                // DeepSeek과 로그인(구글·애플 등 제3자 로그인 포함)은 안에서, 나머지는 밖으로
                return !(u.startsWith(ORIGIN) || u.contains("deepseek.com") || u.contains("accounts.google.com") || u.contains("appleid.apple.com"))
            }
        }
        web = w
        w.loadUrl(ORIGIN)
        return w
    }

    fun refreshState() {
        web?.evaluateJavascript("(window.__hjtkState&&window.__hjtkState())||'LOADING'") { r ->
            status.value = when (r?.trim('"')) { "READY" -> Status.READY; "LOGIN" -> Status.LOGIN; "CHALLENGE" -> Status.CHALLENGE; else -> Status.LOADING }
        }
    }

    /** 조종석 화면에 붙이기(사용자가 직접 보고 조작) */
    fun attach(container: FrameLayout) {
        val w = webView()
        (w.parent as? ViewGroup)?.removeView(w)
        removeParking()
        container.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        w.onResume(); w.resumeTimers()
    }

    /** 화면 밖에서도 타이머·스트림이 멈추지 않게 보이지 않는 창에 '주차'한다(다른 앱 위 표시 권한이 있을 때). */
    fun park() {
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        if (!Settings.canDrawOverlays(app)) { w.resumeTimers(); return }
        runCatching {
            val wm = app.getSystemService(WindowManager::class.java)
            val box = FrameLayout(app)
            box.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val dm = app.resources.displayMetrics
            val lp = WindowManager.LayoutParams(dm.widthPixels, (dm.heightPixels * 0.7).toInt(),
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0f }
            wm.addView(box, lp); parking = box
            w.onResume(); w.resumeTimers()
        }.onFailure { w.resumeTimers() }
    }

    private fun removeParking() {
        parking?.let { p -> runCatching { (p.getChildAt(0) as? WebView)?.let { p.removeView(it) }; app.getSystemService(WindowManager::class.java).removeView(p) } }
        parking = null
    }

    fun newChat() { webView().loadUrl(ORIGIN) }
    fun load(url: String) { webView().loadUrl(url) }
    fun currentUrl(): String? = web?.url

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        web?.loadUrl(ORIGIN)
        status.value = Status.LOGIN
    }

    private suspend fun js(code: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont -> webView().evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(decodeJs(r)) } }
    }

    private suspend fun waitReady(timeoutMs: Long): Status {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = js("(window.__hjtkState&&window.__hjtkState())||'LOADING'")
            status.value = when (s) { "READY" -> Status.READY; "LOGIN" -> Status.LOGIN; "CHALLENGE" -> Status.CHALLENGE; else -> Status.LOADING }
            if (status.value == Status.READY || status.value == Status.LOGIN || status.value == Status.CHALLENGE) return status.value
            delay(700)   // 로컬 DOM 확인(네트워크 폴링 아님)
        }
        return status.value
    }

    /** 세션 URL로 복귀(작업 재개 시 같은 대화방을 이어 쓴다) */
    suspend fun ensureSession(url: String?) {
        if (url.isNullOrBlank()) return
        withContext(Dispatchers.Main) { if (webView().url != url) webView().loadUrl(url) }
        waitReady(20_000)
    }

    override suspend fun send(text: String, jobId: String, step: Int, newSession: Boolean): LlmReply = mutex.withLock {
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            webView()
            if (parking == null && web?.parent == null) park()
            if (newSession) webView().loadUrl(ORIGIN)
        }
        if (newSession) delay(1200)
        when (waitReady(30_000)) {
            Status.LOGIN -> return LlmReply("", "NEEDS_LOGIN", false, System.currentTimeMillis() - t0)
            Status.CHALLENGE -> return LlmReply("", "CHALLENGE", false, System.currentTimeMillis() - t0)
            Status.LOADING, Status.OFF -> return LlmReply("", "NOT_READY", false, System.currentTimeMillis() - t0)
            Status.READY -> {}
        }
        while (events.tryReceive().isSuccess) { /* 이전 잔여 이벤트 비우기 */ }

        val put = js("window.__hjtkSend(${JSONObject.quote(text)})")
        if (!put.startsWith("OK")) return LlmReply("", "INPUT_$put", false, System.currentTimeMillis() - t0)
        delay(350)
        // 전송: Enter → (스트림이 안 열리면) 전송 버튼 → 네이티브 Enter
        js("window.__hjtkEnter()")
        val round = Round()
        if (!round.waitOpen(2800)) {
            js("window.__hjtkClickSend()")
            if (!round.waitOpen(3500)) {
                withContext(Dispatchers.Main) {
                    val w = webView(); w.requestFocus()
                    val now = android.os.SystemClock.uptimeMillis()
                    w.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0))
                    w.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0))
                }
            }
        }
        val r = round.collect(t0)
        lastVia.value = r.via
        return r
    }

    /** 한 번의 왕복. P0(스트림) → P1(DOM) → P4(복사) 순으로 강등 */
    private inner class Round {
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
        private var clip: String? = null
        private val gate = TerminationGate()
        private var buffered = ArrayList<Tap>()

        suspend fun waitOpen(ms: Long): Boolean {
            if (streamId != null) return true
            return withTimeoutOrNull(ms) {
                while (streamId == null) handle(events.receive())
                true
            } ?: false
        }

        private fun handle(t: Tap) {
            when (t) {
                is Tap.Open -> if (streamId == null) streamId = t.id
                is Tap.Chunk -> if (t.id == streamId) { sse.feed(t.text); gate.onChunk(System.currentTimeMillis()) } else if (streamId == null) buffered += t
                is Tap.End -> if (t.id == streamId) { sse.finish(); ended = true }
                is Tap.Clip -> clip = t.text
            }
        }

        fun answer(): String = asm.answer().ifEmpty { rec.text() }

        suspend fun collect(t0: Long): LlmReply {
            gate.start(System.currentTimeMillis())
            while (true) {
                val now = System.currentTimeMillis()
                val a = answer()
                val v = gate.poll(now, a.length, asm.finished || generic || ended, false)
                if (v != TerminationGate.Verdict.RUNNING) break
                withTimeoutOrNull(300) { handle(events.receive()) }
            }
            val a = answer().trim()
            if (a.isNotEmpty()) return LlmReply(a, "P0_STREAM", asm.finished || ended || generic, System.currentTimeMillis() - t0)
            // P1: DOM 마지막 답변(스트림을 못 잡은 경우)
            delay(1500)
            val dom = js("(window.__hjtkLastAnswer&&window.__hjtkLastAnswer())||''").trim()
            if (dom.isNotEmpty()) return LlmReply(dom, "P1_DOM", false, System.currentTimeMillis() - t0)
            clip?.let { return LlmReply(it, "P4_CLIP", false, System.currentTimeMillis() - t0) }
            return LlmReply("", "NONE", false, System.currentTimeMillis() - t0)
        }
    }

    private fun chromeUa(): String {
        val v = runCatching { WebViewCompat.getCurrentWebViewPackage(app)?.versionName?.replace(Regex("[^0-9.]"), "") }.getOrNull()?.ifBlank { null } ?: "131.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Mobile Safari/537.36"
    }

    companion object {
        const val ORIGIN = "https://chat.deepseek.com"
        fun decodeJs(r: String?): String {
            val t = r ?: return ""
            if (t == "null") return ""
            return if (t.startsWith("\"")) runCatching { org.json.JSONArray("[$t]").getString(0) }.getOrDefault(t.trim('"')) else t
        }
    }
}
