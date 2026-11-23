package com.ht.hjtk.ui

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ht.hjtk.core.I18n
import com.ht.hjtk.core.ai.Provider
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.WebAi
import com.ht.hjtk.engine.WebAiHub
import kotlinx.coroutines.delay

/**
 * AI 로그인: 다섯 회사를 키 없이. 위 줄에서 회사를 고르고 아래 화면에서 평소처럼 로그인하면 끝.
 * 로그인이 확인되면 점이 채워지고, 대화·작업에 바로 쓰인다(쿠키가 남아 다음에도 유지).
 */
@Composable
fun AiLoginScreen(vm: MainVm) {
    val tab by vm.loginTab.collectAsState()
    val logged by WebAiHub.loggedIn.collectAsState()
    val ds by vm.h.deepseek.status.collectAsState()
    val google by com.ht.hjtk.engine.AccountHub.google.collectAsState()
    val naver by com.ht.hjtk.engine.AccountHub.naver.collectAsState()
    val steps by com.ht.hjtk.engine.AccountHub.steps.collectAsState()
    val running by com.ht.hjtk.engine.AccountHub.running.collectAsState()
    var acct by remember { mutableStateOf<String?>(if (!com.ht.hjtk.engine.AccountHub.google.value) "google" else null) }   // "google" | "naver" | null(AI 탭)
    LaunchedEffect(Unit) { while (true) { com.ht.hjtk.engine.AccountHub.refresh(); delay(2_000) } }
    val order = listOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI, Provider.GROK, Provider.DEEPSEEK)
    fun isIn(p: Provider) = if (p == Provider.DEEPSEEK) ds == DeepSeekWeb.Status.READY else p in logged
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("계정 하나로"), I18n.t("구글 하나면 AI · 메일 · 메모 · 드라이브까지"))
        // 1) 계정: 구글 / 네이버
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Account("Google", google, acct == "google", Modifier.weight(1f)) { acct = "google" }
            Account(I18n.t("네이버"), naver, acct == "naver", Modifier.weight(1f)) { acct = "naver" }
        }
        // 2) 구글로 AI 네 개 한 번에
        if (google) Column(Modifier.padding(horizontal = 16.dp)) {
            if (steps.isEmpty() || (!running && steps.any { !it.done })) InkButton(if (steps.isEmpty()) I18n.t("구글로 AI 4개 한 번에 연결") else I18n.t("남은 것 다시 연결"), Modifier.fillMaxWidth(), accent = true) { acct = null; com.ht.hjtk.engine.AccountHub.connectAll() }
            steps.forEach { st ->
                Row(Modifier.fillMaxWidth().press({ acct = null; vm.loginTab.value = st.provider }).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (st.done) C.Red else C.Hair)); Spacer(Modifier.width(10.dp))
                    Text(st.provider.label, style = T.small.copy(color = C.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(80.dp))
                    Text(st.state, style = T.small.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                }
            }
        }
        // 3) 회사별(직접 확인용)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            order.forEach { p ->
                val on = acct == null && tab == p
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Ink else C.Paper2).press({ acct = null; vm.loginTab.value = p }).padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (isIn(p)) C.Red else if (on) C.OnInkMute else C.Hair))
                    Spacer(Modifier.width(7.dp))
                    Text(p.label, style = T.small.copy(color = if (on) C.OnInk else C.Ink2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                }
            }
        }
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                acct != null -> key(acct) { AccountWeb(if (acct == "google") com.ht.hjtk.engine.AccountHub.GOOGLE_LOGIN else com.ht.hjtk.engine.AccountHub.NAVER_LOGIN) }
                tab == Provider.DEEPSEEK -> key(tab) {
                    AndroidView({ c -> FrameLayout(c).also { vm.h.deepseek.attach(it) } }, Modifier.fillMaxSize())
                    DisposableEffect(Unit) { onDispose { vm.h.deepseek.park() } }
                }
                else -> key(tab) {
                    val port = remember(tab) { WebAiHub.port(tab)!! }
                    LaunchedEffect(tab) { while (true) { port.refresh(); delay(1_500) } }
                    AndroidView({ c -> FrameLayout(c).also { port.attach(it) } }, Modifier.fillMaxSize())
                    DisposableEffect(tab) { onDispose { port.park() } }
                }
            }
        }
    }
}

@Composable
private fun Account(name: String, on: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) C.Ink else C.Paper2).press(onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = T.head.copy(fontSize = 15.sp, color = if (selected) C.OnInk else C.Ink))
            Text(if (on) I18n.t("연결됨") else I18n.t("로그인"), style = T.small.copy(fontSize = 12.sp, color = if (selected) C.OnInkMute else C.Graphite))
        }
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (on) C.Red else C.Hair))
    }
}

/** 구글·네이버 로그인용 브라우저(쿠키는 HJTK의 모든 웹 화면이 함께 쓴다) */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AccountWeb(url: String) {
    AndroidView({ c -> android.webkit.WebView(c).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.userAgentString = com.ht.hjtk.engine.WebUa.chrome(c)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(v: android.webkit.WebView, u: String?) { android.webkit.CookieManager.getInstance().flush(); com.ht.hjtk.engine.AccountHub.refresh() }
            override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest) = r.url.scheme !in setOf("http", "https")
        }
        loadUrl(url)
    } }, Modifier.fillMaxSize(), onRelease = { it.destroy() })
}

private fun tip(p: Provider) = when (p) {
    Provider.CLAUDE -> "이메일로 계속 → 메일로 온 코드 입력이 가장 확실해요"
    Provider.GPT -> "이메일 + 비밀번호로 로그인하세요(구글 로그인이 막히면 이메일로)"
    Provider.GEMINI -> "구글 계정으로 로그인하세요. 구글이 막으면 설정 → AI 모델에서 무료 키를 넣을 수 있어요"
    Provider.GROK -> "X 계정 또는 이메일로 로그인하세요"
    else -> ""
}

@Composable
private fun Hint(text: String, ok: Boolean, onLogout: (() -> Unit)? = null, onReload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(I18n.t(text), style = T.small.copy(fontSize = 12.5.sp, color = if (ok) C.Ink else C.Graphite), modifier = Modifier.weight(1f))
        onLogout?.let { Text(I18n.t("로그아웃"), style = T.small.copy(color = C.Red, fontWeight = FontWeight.Bold), modifier = Modifier.press(it).padding(8.dp)) }
        Text(I18n.t("새로고침"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press(onReload).padding(8.dp))
    }
    Hairline()
}
