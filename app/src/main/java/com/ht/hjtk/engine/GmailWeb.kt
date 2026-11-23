package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱 비밀번호 없이 Gmail 보내기: '계정 하나로'에서 로그인한 구글 세션(같은 쿠키)으로
 * 보이지 않는 창에서 Gmail 작성 화면을 열고, 받는이·제목·본문을 채운 뒤 보내기를 누르고, 보냈다는 표시까지 확인한다.
 * 다른 앱으로 넘어가지 않는다. 승인('실행')은 명령 단계(Aegis)에서 이미 받았다.
 */
object GmailWeb {
    enum class R { SENT, NEEDS_LOGIN, NO_BUTTON, UNSURE, ERROR }
    data class Out(val r: R, val note: String)

    private var app: Context? = null
    private var web: WebView? = null
    private val lock = Mutex()
    fun install(c: Context) { app = c.applicationContext }

    fun composeUrl(to: String, subject: String, body: String): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "https://mail.google.com/mail/u/0/?view=cm&fs=1&tf=1&to=${e(to)}&su=${e(subject)}&body=${e(body)}"
    }

    suspend fun send(to: String, subject: String, body: String): Out = lock.withLock {
        val c = app ?: return@withLock Out(R.ERROR, "준비 안 됨")
        val loaded = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { open(c, loaded).loadUrl(composeUrl(to, subject, body)) }
        withTimeoutOrNull(25_000) { loaded.await() }
        try {
            // 1) 작성 화면이 뜨고 보내기 단추가 생길 때까지
            var clicked = false
            for (i in 0 until 40) {
                when (js(CLICK)) {
                    "LOGIN" -> return@withLock Out(R.NEEDS_LOGIN, "구글 로그인이 필요해요(허브 → 계정 하나로)")
                    "CLICKED" -> { clicked = true; break }
                }
                delay(700)
            }
            if (!clicked) return@withLock Out(R.NO_BUTTON, "Gmail 작성 화면에서 보내기 단추를 찾지 못했어요")
            // 2) 보냈다는 표시(또는 작성 창이 사라짐) 확인
            var gone = 0
            for (i in 0 until 24) {
                delay(600)
                when (js(VERIFY)) {
                    "SENT" -> return@withLock Out(R.SENT, "Gmail로 보냈어요")
                    "BAD" -> return@withLock Out(R.ERROR, "Gmail이 받는이 주소를 거절했어요")
                    "GONE" -> if (++gone >= 3) return@withLock Out(R.SENT, "Gmail로 보냈어요(작성 창 닫힘 확인)")
                    else -> gone = 0
                }
            }
            Out(R.UNSURE, "보내기를 눌렀지만 완료 표시를 확인하지 못했어요. Gmail 보낸편지함을 확인해 주세요")
        } finally {
            withContext(Dispatchers.Main) { web?.loadUrl("about:blank"); web?.onPause() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(c: Context, loaded: CompletableDeferred<Unit>): WebView {
        val w = web ?: WebView(c).also { v ->
            v.settings.javaScriptEnabled = true; v.settings.domStorageEnabled = true
            v.settings.useWideViewPort = true; v.settings.loadWithOverviewMode = true
            v.settings.userAgentString = desktopUa(c)       // 데스크톱 Gmail만 view=cm 작성 화면을 연다
            CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
            v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1280, 900)
            web = v
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String?) { if (u != null && u.contains("mail.google.com") || u?.contains("accounts.google") == true) if (!loaded.isCompleted) loaded.complete(Unit) }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.scheme !in setOf("http", "https")
        }
        w.onResume()
        return w
    }

    private suspend fun js(code: String): String = withTimeoutOrNull(4_000) {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { web?.evaluateJavascript(code) { d.complete(DeepSeekWeb.decodeJs(it)) } ?: d.complete("") }
        d.await()
    } ?: ""

    private fun desktopUa(c: Context): String {
        val v = runCatching { WebViewCompat.getCurrentWebViewPackage(c)?.versionName?.replace(Regex("[^0-9.]"), "") }.getOrNull()?.ifBlank { null } ?: "131.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    // 보내기 단추: 영어/한국어 라벨 모두. Gmail 단추는 mousedown→mouseup→click 순서를 받아야 반응한다
    private const val CLICK = """(function(){
if(/accounts\.google\.com|ServiceLogin|signin\/v2|\/v3\/signin/.test(location.href))return 'LOGIN';
var els=[].slice.call(document.querySelectorAll('div[role=button],button'));
var b=els.find(function(e){var l=(e.getAttribute('data-tooltip')||e.getAttribute('aria-label')||e.innerText||'').trim();return /^(Send|보내기)(\s|\(|$)/.test(l)&&e.getBoundingClientRect().width>0;});
if(!b)return 'WAIT';
var to=document.querySelector('input[name=to],input[aria-label*="To"],input[aria-label*="받는사람"],[name=to] input,div[aria-label*="받는사람"],div[aria-label*="To recipients"]');
if(!to&&!document.querySelector('span[email],div[data-hovercard-id]'))return 'WAIT';
['mousedown','mouseup','click'].forEach(function(t){b.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});
return 'CLICKED';})()"""

    private const val VERIFY = """(function(){
var t=(document.body&&document.body.innerText)||'';
if(/메일을 보냈습니다|메일이 전송되었습니다|Message sent|보낸편지함으로 이동|View message/.test(t))return 'SENT';
var dlg=document.querySelector('[role=alertdialog],[role=dialog]');
if(dlg&&/주소|address|recipient|받는사람/.test(dlg.innerText||''))return 'BAD';
var els=[].slice.call(document.querySelectorAll('div[role=button],button'));
var b=els.find(function(e){var l=(e.getAttribute('data-tooltip')||e.getAttribute('aria-label')||'').trim();return /^(Send|보내기)(\s|\(|$)/.test(l)&&e.getBoundingClientRect().width>0;});
return b?'WAIT':'GONE';})()"""
}
