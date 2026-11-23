package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.BuildConfig
import com.ht.hjtk.R
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.Perms

@Composable
internal fun Toggle(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(46.dp, 26.dp).clip(RoundedCornerShape(50)).background(if (on) C.Ink else C.Paper3).press(onClick).padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(if (on) C.Red else C.Paper))
    }
}

@Composable
internal fun Segmented(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Paper2).padding(3.dp)) {
        options.forEach { (k, l) ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (value == k) C.Ink else C.Paper2).press({ onPick(k) }).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(l, style = T.small.copy(color = if (value == k) C.OnInk else C.Ink2, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp))
            }
        }
    }
}

@Composable
private fun PermRow(n: Int, title: String, sub: String, granted: Boolean, optional: Boolean = false, onClick: () -> Unit) =
    NumRow(n, title, sub, trailing = if (granted) "허용됨" else if (optional) "선택" else "필요", dot = if (granted) C.Ink else if (optional) C.Hair else C.Red, onClick = onClick)

@Composable
fun SettingsScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val s by vm.store.settings.collectAsState()
    val ds by vm.h.deepseek.status.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var dsKey by remember { mutableStateOf("") }; var gKey by remember { mutableStateOf("") }; var nId by remember { mutableStateOf("") }; var nSec by remember { mutableStateOf("") }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u -> if (u != null) backupMsg = com.ht.hjtk.engine.Backup.export(ctx, u) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { val m = com.ht.hjtk.engine.Backup.restore(ctx, u); backupMsg = m; if (!m.startsWith("복원 실패")) com.ht.hjtk.engine.Backup.restart(ctx) }
    }
    var name by remember { mutableStateOf(s.userName) }; var city by remember { mutableStateOf(s.homeCity) }
    key(tick) {
    Column(Modifier.fillMaxSize()) {
        TopBar("설정", "v${BuildConfig.VERSION_NAME} · 온디바이스 AI 0 · 명령어 ${CommandCatalog.ALL.size}", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            SectionHead("01", I18n.t("AI 연결 — 메인 2개"))
            Spacer(Modifier.height(10.dp)); Label(I18n.t("DeepSeek · 두뇌(계획·판단)"))
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("web" to "내장 웹(권장)", "api" to "API 키"), if (s.dsMode == "app") "web" else s.dsMode) { m -> vm.store.updateSettings { it.copy(dsMode = m) } }
            Text(when (s.dsMode) {
                "web" -> "앱 안의 DeepSeek 화면에 한 번 로그인하면, 키 없이 답 전문을 스트림에서 직접 읽습니다. 가장 자동적인 경로."
                else -> "DeepSeek Platform 키가 있을 때만 동작합니다(선택). 가장 빠르고 안정적이지만 필수가 아닙니다."
            }, style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))
            NumRow(1, I18n.t("조종석 — DeepSeek 화면"), when (ds) { DeepSeekWeb.Status.READY -> "로그인됨"; DeepSeekWeb.Status.LOGIN -> "로그인이 필요해요"; DeepSeekWeb.Status.CHALLENGE -> "확인이 필요해요"; else -> "열어서 확인" },
                dot = if (ds == DeepSeekWeb.Status.READY) C.Ink else C.Red, onClick = { vm.go(Route.Cockpit) })
            Hairline()
            NumRow(2, I18n.t("DeepSeek 로그아웃"), "쿠키·저장소를 지웁니다", onClick = { vm.h.deepseek.logout() })
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (I18n.en) "Language" else "언어", style = T.body, modifier = Modifier.weight(1f))
                Segmented(listOf("ko" to "한국어", "en" to "English"), if (I18n.en) "en" else "ko") { v -> vm.store.updateSettings { it.copy(lang = v) } }
            }
            NumRow(0, I18n.t("AI 모델 · 계정 · 난이도"), "Claude · ChatGPT · Gemini · Grok · DeepSeek · 모델 세부 설정 · 역할 분담", onClick = { vm.go(Route.Ai) })
            Spacer(Modifier.height(14.dp)); Label(I18n.t("작업 공정 · 검토 AI(AI끼리 협업)"))
            Segmented(listOf("auto" to "자동", "gemini" to "Gemini", "deepseek2" to "DeepSeek 2창", "off" to "끄기"), s.collab) { m -> vm.store.updateSettings { it.copy(collab = m) } }
            Text(when (s.collab) {
                "auto" -> "Gemini API 키가 있으면 Gemini가, 없으면 DeepSeek의 두 번째 대화방이 계획을 고르고 결과물을 검증해요. 사람 손이 들지 않아요."
                "gemini" -> if (vm.h.gemini.available()) "Gemini(API 키)가 검토합니다." else "Gemini 키가 없어서 DeepSeek 두 번째 창이 대신 검토해요."
                "deepseek2" -> "DeepSeek을 별도 대화방에서 '검토자'로 씁니다. 작성자와 맥락이 섞이지 않아요."
                else -> "검토 없이 작성자 혼자 진행합니다(빠르지만 품질 확인이 약해요)."
            }, style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp)); Label(I18n.t("Gemini · 검토자(교차 검증)"))
            Spacer(Modifier.height(6.dp))
            Text(I18n.t("Gemini는 API 키로 연결해요(무료 등급 있음 · 아래 02). HJTK는 다른 앱으로 넘어가지 않아서, 키가 없으면 DeepSeek의 두 번째 창이 검토를 맡아요."),
                style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))

            SectionHead("02", I18n.t("API 키 — 선택 사항"))
            Text(I18n.t("없어도 모든 기능이 동작합니다. 넣으면 해당 경로가 빨라질 뿐이에요. 키는 Android Keystore로 암호화되어 이 기기에만 저장됩니다."), style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 8.dp))
            KeyRow(vm, "DeepSeek API 키", "deepseek_key", dsKey) { dsKey = it }
            KeyRow(vm, "Gemini API 키", "gemini_key", gKey) { gKey = it }
            KeyRow(vm, "네이버 검색 Client ID", "naver_id", nId) { nId = it }
            KeyRow(vm, "네이버 검색 Client Secret", "naver_secret", nSec) { nSec = it }

            SectionHead("03", I18n.t("권한"))
            var n = 1
            PermRow(n++, I18n.t("알림"), "5분 보고·허락 요청", Build.VERSION.SDK_INT < 33 || Perms.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) { if (Build.VERSION.SDK_INT >= 33) perm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }; Hairline()
            PermRow(n++, I18n.t("다른 앱 위에 표시"), "백그라운드에서 DeepSeek 화면 유지", Perms.overlay(ctx)) { ctx.startActivity(Perms.overlayIntent(ctx)) }; Hairline()
            PermRow(n++, I18n.t("캘린더"), "구글 캘린더(기기 동기화) 읽기·쓰기", Perms.granted(ctx, Manifest.permission.READ_CALENDAR), optional = true) { perm.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }; Hairline()
            PermRow(n++, I18n.t("연락처"), "받는 사람 찾기·처음 보내는 주소 판별", Perms.granted(ctx, Manifest.permission.READ_CONTACTS), optional = true) { perm.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }; Hairline()
            PermRow(n++, I18n.t("배터리 최적화 제외"), "긴 작업이 덜 끊겨요(삼성은 '잠자는 앱'도 확인)", Perms.ignoringBattery(ctx), optional = true) { runCatching { ctx.startActivity(Perms.batteryIntent(ctx)) } }

            SectionHead("04", I18n.t("허락 규칙"))
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(I18n.t("아는 사람에게 보내는 것은 자동 허용"), style = T.head.copy(fontSize = 15.sp))
                    Text(I18n.t("켜도 처음 보내는 주소·링크 포함·삭제는 항상 '실행' 버튼을 거칩니다. 기본은 꺼짐."), style = T.small.copy(fontSize = 12.sp))
                }
                Toggle(s.autoAllowE2) { vm.store.updateSettings { it.copy(autoAllowE2 = !it.autoAllowE2) } }
            }
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(I18n.t("새 메시지는 기본으로 '작업 맡기기'"), style = T.head.copy(fontSize = 15.sp)); Text(I18n.t("끄면 자동 판별(찾아줘·준비해줘 같은 말은 작업으로)"), style = T.small.copy(fontSize = 12.sp)) }
                Toggle(s.defaultMission) { vm.store.updateSettings { it.copy(defaultMission = !it.defaultMission) } }
            }

            SectionHead("05", I18n.t("나"))
            Spacer(Modifier.height(10.dp))
            Field(name, { name = it }, "이름 (보고서·결과물에 사용)"); Spacer(Modifier.height(8.dp))
            Field(city, { city = it }, "사는 곳 (날씨·장소 기본값)"); Spacer(Modifier.height(8.dp))
            GhostButton(I18n.t("저장")) { vm.store.updateSettings { it.copy(userName = name, homeCity = city.ifBlank { "서울" }) } }

            SectionHead("06", I18n.t("데이터"))
            NumRow(1, I18n.t("기억 관리"), "${vm.store.memories.value.size}개", onClick = { vm.go(Route.Memory) }); Hairline()
            NumRow(2, I18n.t("대화 목록 · 내보내기(MD/HTML/JSON)"), "API 키·토큰·경로는 기본으로 가려서 내보냅니다", onClick = { vm.go(Route.Conversations) }); Hairline()
            NumRow(3, I18n.t("사용량"), "AI별 토큰 · 최근 7일", onClick = { vm.go(Route.Usage) }); Hairline()
            NumRow(4, I18n.t("백업 파일 만들기"), backupMsg ?: "대화·작업·기억·루틴·설정을 zip 하나로 · 키와 비밀번호는 빼고", onClick = { backupOut.launch("HJTK-backup-${java.time.LocalDate.now()}.zip") }); Hairline()
            NumRow(5, I18n.t("백업에서 복원"), "지금 데이터를 덮어쓰고 앱을 다시 시작해요", onClick = { backupIn.launch(arrayOf("application/zip", "application/octet-stream")) })

            SectionHead("07", I18n.t("원칙"))
            Text("1. 앱 안에 AI 모델이 없습니다. 이해는 DeepSeek·Gemini가, 실행은 명령어가 합니다.\n2. 밖으로 나가는 일은 '실행' 버튼으로 허락받습니다. 승인은 화면에 보인 그 행동에만 묶입니다.\n3. 못 지킨 것을 숨기지 않습니다. 늦은 보고는 늦었다고, 비평을 못 했으면 못 했다고 씁니다.\n4. 모든 데이터는 이 기기 안에만 있습니다.",
                style = T.small.copy(color = C.Ink2), modifier = Modifier.padding(vertical = 10.dp))
            Spacer(Modifier.height(16.dp)); Label("HJTK · Don't make tool, make innovation")
        }
    }
    }
}

@Composable
private fun KeyRow(vm: MainVm, label: String, key: String, value: String, onChange: (String) -> Unit) {
    val has = vm.store.secrets.has(key)
    var saved by remember { mutableStateOf(has) }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(label + if (saved) " · 저장됨" else "", color = if (saved) C.Ink else C.Mute)
            Spacer(Modifier.height(4.dp))
            Field(value, onChange, if (saved) "••••••• (바꾸려면 입력)" else "선택", secret = true)
        }
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(I18n.t("저장"), style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ if (value.isNotBlank()) { vm.store.secrets.put(key, value.trim()); saved = true; onChange("") } }).padding(6.dp))
            if (saved) Text(I18n.t("삭제"), style = T.small.copy(color = C.Red, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.store.secrets.put(key, null); saved = false }).padding(6.dp))
        }
    }
}
