package com.ht.hjtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.core.I18n

/** PC에서 쓰기: 폰이 같은 와이파이 안에 작은 웹 앱을 연다. PC 브라우저에 주소와 코드만 입력하면 된다. */
@Composable
fun PcScreen(vm: MainVm) {
    val st by vm.h.pc.state.collectAsState()
    Column(Modifier.fillMaxSize().background(C.Paper)) {
        ScreenHead(vm, I18n.t("PC에서 쓰기"), if (st.running) "켜짐 · 연결된 PC ${st.clients}대" else "꺼짐")
        Column(Modifier.weight(1f).padding(20.dp)) {
            Text(I18n.t("같은 와이파이의 PC 브라우저에서 아래 주소를 열고 코드를 입력하세요"), style = T.body)
            Spacer(Modifier.height(18.dp))
            if (st.running) {
                Label(I18n.t("주소"))
                Text(st.url, style = T.title.copy(fontSize = 22.sp), modifier = Modifier.padding(vertical = 6.dp))
                Spacer(Modifier.height(10.dp)); Label(I18n.t("연결 코드"))
                Text(st.code.chunked(3).joinToString(" "), style = T.display.copy(letterSpacing = 6.sp, color = C.Red), modifier = Modifier.padding(vertical = 6.dp))
                Spacer(Modifier.height(16.dp))
                Text("폰이 엔진으로 남아 DeepSeek 창·계정·명령을 그대로 씁니다. PC에서는 대화·작업 맡기기·실시간 진행·허락(실행/거절)·일시정지까지 할 수 있어요. 같은 네트워크만 접속되고, 2시간 쓰지 않으면 자동으로 꺼져요. 폰 화면을 켜 두거나 백그라운드 유지를 허용하면 더 안정적이에요.",
                    style = T.small.copy(fontSize = 12.5.sp, color = C.Graphite))
            } else if (st.url.isNotBlank()) Text(st.url, style = T.small.copy(color = C.Red))
            Spacer(Modifier.height(14.dp))
            Text("단축키 · Enter 보내기 · Shift+Enter 줄바꿈 · Ctrl+K 새 대화", style = T.small.copy(fontSize = 12.sp, color = C.Mute))
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            if (st.running) GhostButton(I18n.t("끄기"), Modifier.fillMaxWidth()) { vm.h.pc.stop() }
            else InkButton(I18n.t("PC에서 열기"), Modifier.fillMaxWidth(), accent = true) { vm.h.pc.start(); vm.runner.keepAlive() }
        }
    }
}

/** 첫 실행: 언어 고르기(한국어 / English) */
@Composable
fun LanguageScreen(onPick: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().background(C.Paper).statusBarsPadding().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("HJTK", style = T.label.copy(letterSpacing = 4.sp, color = C.Ink))
        Spacer(Modifier.height(18.dp))
        Text("언어를 고르세요", style = T.display)
        Text("Choose your language", style = T.title.copy(color = C.Graphite))
        Spacer(Modifier.height(28.dp))
        listOf(false to "한국어", true to "English").forEach { (en, label) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(C.Paper2).press({ onPick(en) }).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = T.title.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Text(if (en) "AI answers in English" else "AI가 한국어로 답해요", style = T.small.copy(color = C.Graphite))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("설정에서 언제든 바꿀 수 있어요 · Change anytime in Settings", style = T.small.copy(fontSize = 12.sp, color = C.Mute))
    }
}
