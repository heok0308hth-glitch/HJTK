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
    val order = listOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI, Provider.GROK, Provider.DEEPSEEK)
    fun isIn(p: Provider) = if (p == Provider.DEEPSEEK) ds == DeepSeekWeb.Status.READY else p in logged
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("AI 로그인"), "${order.count { isIn(it) }}/5 " + I18n.t("연결됨 · 키 없이 사용"))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            order.forEach { p ->
                val on = tab == p
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Ink else C.Paper2).press({ vm.loginTab.value = p }).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (isIn(p)) C.Red else if (on) C.OnInkMute else C.Hair))
                    Spacer(Modifier.width(7.dp))
                    Text(p.label, style = T.small.copy(color = if (on) C.OnInk else C.Ink2, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp))
                }
            }
        }
        key(tab) {
            if (tab == Provider.DEEPSEEK) {
                Hint(if (ds == DeepSeekWeb.Status.READY) "로그인됨 · 키 없이 쓰는 중" else "아래에서 DeepSeek에 로그인하세요", ds == DeepSeekWeb.Status.READY) { vm.h.deepseek.reload() }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView({ c -> FrameLayout(c).also { vm.h.deepseek.attach(it) } }, Modifier.fillMaxSize())
                    DisposableEffect(Unit) { onDispose { vm.h.deepseek.park() } }
                }
            } else {
                val port = remember(tab) { WebAiHub.port(tab)!! }
                val st by port.status.collectAsState()
                LaunchedEffect(tab) { while (true) { port.refresh(); delay(1_500) } }
                val ready = st == WebAi.Status.READY
                Hint(when {
                    ready -> "로그인됨 · 대화·작업에서 '${tab.label} 웹'으로 골라 쓰세요"
                    st == WebAi.Status.CHALLENGE -> "사람 확인을 한 번 눌러 주세요"
                    st == WebAi.Status.OFFLINE -> port.diag.value.ifBlank { "인터넷 연결을 확인해 주세요" }
                    else -> tip(tab)
                }, ready, onLogout = if (ready) ({ port.logout() }) else null) { port.reload() }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView({ c -> FrameLayout(c).also { port.attach(it) } }, Modifier.fillMaxSize())
                    DisposableEffect(tab) { onDispose { port.park() } }
                }
            }
        }
    }
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
