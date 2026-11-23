package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ht.hjtk.core.command.CommandCall
import com.ht.hjtk.data.Role
import com.ht.hjtk.engine.Service

/** 허브 → 계정 연결: 모든 계정을 한 곳에서. 같은 서비스 여러 개 가능 */
@Composable
fun AccountsScreen(vm: MainVm) {
    val conn by vm.h.connectors.accounts.collectAsState()
    val sessions by vm.h.connectors.sessions.collectAsState()
    val ai by vm.h.ai.state.collectAsState()
    val mail by vm.store.mail.collectAsState()
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, "계정 연결", "AI가 이 계정들로 조사하고, 허락을 받아 보내고, 기록합니다")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionHead("01", "AI", "관리") { vm.go(Route.Ai) }
            if (ai.accounts.isEmpty()) Label(I18n.t("키 없음 · DeepSeek은 앱 안 웹으로 사용 중"))
            ai.accounts.forEach { a -> AccRow(a.provider.label, a.label + if (a.isDefault) " · 기본" else "") }

            SectionHead("02", I18n.t("메일 · 구글 · 네이버"), "추가") { vm.go(Route.MailAdd) }
            Text(I18n.t("구글(Gmail)·네이버·다음·기타 메일을 앱 비밀번호로 연결해요. 구글 캘린더는 기기 캘린더로 바로 쓰여요(로그인 불필요)."), style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
            mail.forEach { m -> AccRow(m.provider, m.email) }

            Service.values().forEachIndexed { i, s ->
                SectionHead("0${i + 3}", s.label)
                Text(s.help, style = T.small.copy(fontSize = 12.sp, color = C.Graphite))
                conn.filter { it.service == s }.forEach { a ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.label + if (a.isDefault) " · 기본" else "", style = T.body, modifier = Modifier.weight(1f))
                        if (!a.isDefault) Text(I18n.t("기본"), style = T.small, modifier = Modifier.press({ vm.h.connectors.makeDefault(a.id) }).padding(8.dp))
                        Text(I18n.t("삭제"), style = T.small.copy(color = C.Red), modifier = Modifier.press({ vm.h.connectors.remove(a.id) }).padding(8.dp))
                    }
                }
                var label by remember(s) { mutableStateOf("") }
                var tok by remember(s) { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(0.35f)) { Field(label, I18n.t("이름")) { label = it } }
                    Box(Modifier.weight(0.65f)) { Field(tok, s.tokenHint, secret = true) { tok = it } }
                }
                if (tok.length > 6) InkButton(I18n.t("연결"), Modifier.fillMaxWidth().padding(top = 6.dp)) { vm.h.connectors.add(s, label, tok); label = ""; tok = "" }
            }

            SectionHead("0${Service.values().size + 3}", "로그인한 사이트", "브라우저") { vm.go(Route.Browser) }
            Text(I18n.t("앱 안 브라우저에서 한 번 로그인해 두면, AI가 그 세션으로 페이지를 읽어요(내 카페·쇼핑 주문 내역 등). 구글은 정책상 앱 안 로그인이 막혀 있어 메일·캘린더 경로를 씁니다."), style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
            sessions.forEach { h ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h, style = T.body, modifier = Modifier.weight(1f))
                    Text(I18n.t("지우기"), style = T.small.copy(color = C.Red), modifier = Modifier.press({ vm.h.connectors.removeSession(h) }).padding(8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AccRow(kind: String, name: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(kind, style = T.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(88.dp))
        Text(name, style = T.body)
    }
}

@Composable
fun ScreenHead(vm: MainVm, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).press({ vm.back() }), contentAlignment = Alignment.Center) { Ico(I.Back, 22.dp) }
        Column(Modifier.weight(1f)) { Text(title, style = T.title); Label(sub) }
    }
    Hairline()
}

/** 로그인 브라우저: 사용자가 직접 로그인 → 그 사이트를 '로그인한 사이트'로 기록(쿠키는 앱 안 WebView 공용) */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: MainVm) {
    val target by vm.webUrl.collectAsState()
    var url by remember(target) { mutableStateOf(target.ifBlank { "https://nid.naver.com/nidlogin.login" }) }
    var cur by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, if (target.isBlank()) "로그인 브라우저" else "HJTK 브라우저", if (target.isBlank()) "로그인하면 AI가 이 세션으로 페이지를 읽을 수 있어요" else "다른 앱으로 넘어가지 않고 여기서 열어요")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(url, { url = it }, singleLine = true, textStyle = T.small.copy(fontSize = 13.sp), cursorBrush = SolidColor(C.Ink),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.Paper2).padding(10.dp))
            Text(I18n.t("열기"), style = T.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.press({ web?.loadUrl(if (url.startsWith("http")) url else "https://$url") }).padding(10.dp))
        }
        Box(Modifier.weight(1f)) {
            AndroidView({ c -> WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String?) { cur = u.orEmpty(); CookieManager.getInstance().flush() }
                    // 앱 열기 링크(intent:// market:// 등)는 따라가지 않는다: HJTK 밖으로 나가지 않기
                    override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean = r.url.scheme !in setOf("http", "https")
                }
                loadUrl(url); web = this
            } }, Modifier.fillMaxSize(), onRelease = { it.destroy() })
            LaunchedEffect(target) { if (target.isNotBlank()) web?.loadUrl(target) }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            val host = runCatching { android.net.Uri.parse(cur).host.orEmpty() }.getOrDefault("")
            InkButton(if (host.isBlank()) "페이지를 여는 중" else "$host 로그인 완료로 기록", Modifier.fillMaxWidth(), accent = true) { if (host.isNotBlank()) { vm.h.connectors.addSession(host); vm.back() } }
        }
    }
}

// PhoneScreen은 PhoneScreen.kt로 옮겼다(3.0 · 기기 안 AI 통화)
