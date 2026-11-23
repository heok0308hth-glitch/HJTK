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
    var url by remember { mutableStateOf("https://nid.naver.com/nidlogin.login") }
    var cur by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, "로그인 브라우저", "로그인하면 AI가 이 세션으로 페이지를 읽을 수 있어요")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(url, { url = it }, singleLine = true, textStyle = T.small.copy(fontSize = 13.sp), cursorBrush = SolidColor(C.Ink),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.Paper2).padding(10.dp))
            Text(I18n.t("열기"), style = T.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.press({ web?.loadUrl(if (url.startsWith("http")) url else "https://$url") }).padding(10.dp))
        }
        Box(Modifier.weight(1f)) {
            AndroidView({ c -> WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView, u: String?) { cur = u.orEmpty(); CookieManager.getInstance().flush() } }
                loadUrl(url); web = this
            } }, Modifier.fillMaxSize(), onRelease = { it.destroy() })
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            val host = runCatching { android.net.Uri.parse(cur).host.orEmpty() }.getOrDefault("")
            InkButton(if (host.isBlank()) "페이지를 여는 중" else "$host 로그인 완료로 기록", Modifier.fillMaxWidth(), accent = true) { if (host.isNotBlank()) { vm.h.connectors.addSession(host); vm.back() } }
        }
    }
}

/** AI 전화: 대본 받고 직접 걸기(무료) / AI가 대신 걸기(선택 계정 · 매번 '실행' 승인) */
@Composable
fun PhoneScreen(vm: MainVm) {
    var number by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf("") }
    val provider = remember { vm.h.phone.available() }
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, "AI 전화", provider?.let { "$it 연결됨 · 통화마다 허락을 받아요" } ?: "키 없이도 대본 받고 직접 걸 수 있어요")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Label(I18n.t("번호")); Field(number, I18n.t("예: 02-123-4567")) { number = it }
            Spacer(Modifier.height(10.dp)); Label(I18n.t("통화 목적"))
            Field(goal, I18n.t("예: 토요일 저녁 7시 4명 예약 가능한지 묻고, 되면 예약해 줘"), multi = true) { goal = it }
            Spacer(Modifier.height(10.dp)); Label(I18n.t("AI가 말해도 되는 정보(선택)"))
            Field(facts, I18n.t("예: 이름 허태혁, 연락처는 이 번호로"), multi = true) { facts = it }
            Spacer(Modifier.height(14.dp))
            Text(I18n.t("AI 통화는 첫 마디에 'AI 비서'임을 밝히고, 목적 밖의 결제·계약에는 동의하지 않으며, 카드·주민번호 같은 정보는 말하지 않아요. 긴급번호는 걸지 않고, 하루 5통 · 같은 번호 10분 간격으로 제한돼요."),
                style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = number.filter { it.isDigit() }.length >= 7 && goal.isNotBlank()
            GhostButton(I18n.t("대본 받고 직접 걸기"), Modifier.fillMaxWidth()) {
                if (ready) { vm.tab(Route.Chat); vm.store.msg(vm.convId.value, Role.USER, "$number 에 전화할 대본 · $goal", meta = mapOf("mission" to "true"))
                    vm.runner.startMission(vm.convId.value, "$number 에 전화하기 위한 짧은 통화 대본을 써 줘(인사·요점·물어볼 것·예상 답별 대응). 목적: $goal. 말해도 되는 정보: $facts. 마지막에 phone.prepare로 번호와 대본을 띄워 줘.", difficulty = com.ht.hjtk.core.ai.Difficulty.QUICK) }
            }
            InkButton(if (provider == null) "AI가 대신 걸기 · 허브에서 계정 연결" else "AI가 대신 걸기", Modifier.fillMaxWidth(), accent = provider != null) {
                if (provider == null) vm.go(Route.Accounts)
                else if (ready) { vm.tab(Route.Chat); vm.store.msg(vm.convId.value, Role.USER, "AI 전화 · $number · $goal")
                    vm.runner.runDirect(vm.convId.value, CommandCall("u1", "phone.ai_call", mapOf("number" to number, "goal" to goal, "facts" to facts))) }
            }
        }
    }
}
