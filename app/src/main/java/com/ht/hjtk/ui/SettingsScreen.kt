package com.ht.hjtk.ui

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
import com.ht.hjtk.engine.A11yBridge
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.Perms

@Composable
private fun Toggle(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(46.dp, 26.dp).clip(RoundedCornerShape(50)).background(if (on) C.Ink else C.Paper3).press(onClick).padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(if (on) C.Red else C.Paper))
    }
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
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
    var name by remember { mutableStateOf(s.userName) }; var city by remember { mutableStateOf(s.homeCity) }
    key(tick) {
    Column(Modifier.fillMaxSize()) {
        TopBar("설정", "v${BuildConfig.VERSION_NAME} · 온디바이스 AI 0 · 명령어 ${CommandCatalog.ALL.size}", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            SectionHead("01", "AI 연결 — 메인 2개")
            Spacer(Modifier.height(10.dp)); Label("DeepSeek · 두뇌(계획·판단)")
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("web" to "내장 웹(권장)", "app" to "DeepSeek 앱", "api" to "API 키"), s.dsMode) { m -> vm.store.updateSettings { it.copy(dsMode = m) } }
            Text(when (s.dsMode) {
                "web" -> "앱 안의 DeepSeek 화면에 한 번 로그인하면, 키 없이 답 전문을 스트림에서 직접 읽습니다. 가장 자동적인 경로."
                "app" -> "설치된 DeepSeek 앱으로 보냅니다. 답은 화면 읽기(접근성) 또는 '공유 → HJTK'로 돌아옵니다. 단계마다 사람 손이 들 수 있어요."
                else -> "DeepSeek Platform 키가 있을 때만 동작합니다(선택). 가장 빠르고 안정적이지만 필수가 아닙니다."
            }, style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))
            NumRow(1, "조종석 — DeepSeek 화면", when (ds) { DeepSeekWeb.Status.READY -> "로그인됨"; DeepSeekWeb.Status.LOGIN -> "로그인이 필요해요"; DeepSeekWeb.Status.CHALLENGE -> "확인이 필요해요"; else -> "열어서 확인" },
                dot = if (ds == DeepSeekWeb.Status.READY) C.Ink else C.Red, onClick = { vm.go(Route.Cockpit) })
            Hairline()
            NumRow(2, "DeepSeek 로그아웃", "쿠키·저장소를 지웁니다", onClick = { vm.h.deepseek.logout() })
            Spacer(Modifier.height(14.dp)); Label("Gemini · 검토자(교차 검증)")
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("app" to "Gemini 앱", "web" to "웹(브라우저)", "api" to "API 키"), s.geminiMode) { m -> vm.store.updateSettings { it.copy(geminiMode = m) } }
            Text("Google 정책상 앱 안 화면(WebView)에서는 Gemini에 로그인할 수 없어요. 그래서 Gemini는 앱·브라우저로 보내고, 답은 화면 읽기 또는 '공유 → HJTK' 1탭으로 받습니다. 작업당 최대 2번까지만 사람을 부릅니다." +
                (if (vm.h.bridge.installed(ExternalAiBridge.GEMINI)) "" else "\n(Gemini 앱이 설치되어 있지 않아요)"), style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Gemini 답 기다리는 시간", style = T.body, modifier = Modifier.weight(1f))
                listOf(5, 10, 20).forEach { m -> Spacer(Modifier.width(6.dp)); Chip("${m}분", s.geminiWaitMin == m) { vm.store.updateSettings { it.copy(geminiWaitMin = m) } } }
            }

            SectionHead("02", "API 키 — 선택 사항")
            Text("없어도 모든 기능이 동작합니다. 넣으면 해당 경로가 빨라질 뿐이에요. 키는 Android Keystore로 암호화되어 이 기기에만 저장됩니다.", style = T.small.copy(fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 8.dp))
            KeyRow(vm, "DeepSeek API 키", "deepseek_key", dsKey) { dsKey = it }
            KeyRow(vm, "Gemini API 키", "gemini_key", gKey) { gKey = it }
            KeyRow(vm, "네이버 검색 Client ID", "naver_id", nId) { nId = it }
            KeyRow(vm, "네이버 검색 Client Secret", "naver_secret", nSec) { nSec = it }

            SectionHead("03", "펫 · 쥬티")
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.mascot_wave), null, Modifier.size(72.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("화면을 돌아다니며 5분 보고를 전해요", style = T.head.copy(fontSize = 15.sp))
                    Text("앱을 나가 있을 때 작업 중에만 나타나요. 누르면 앱으로, '화면 공유 요청' 중에는 누르면 그 화면을 AI에게 보냅니다. 길게 누르면 잠들어요.", style = T.small.copy(fontSize = 12.sp))
                }
                Toggle(s.petEnabled) { vm.store.updateSettings { it.copy(petEnabled = !it.petEnabled) } }
            }
            if (s.petEnabled && !Perms.overlay(ctx)) InkButton("다른 앱 위에 표시 허용하기", Modifier.fillMaxWidth(), accent = true) { ctx.startActivity(Perms.overlayIntent(ctx)) }

            SectionHead("04", "권한")
            var n = 1
            PermRow(n++, "알림", "5분 보고·허락 요청", Build.VERSION.SDK_INT < 33 || Perms.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) { if (Build.VERSION.SDK_INT >= 33) perm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }; Hairline()
            PermRow(n++, "다른 앱 위에 표시", "쥬티 + 백그라운드에서 DeepSeek 화면 유지", Perms.overlay(ctx)) { ctx.startActivity(Perms.overlayIntent(ctx)) }; Hairline()
            if (com.ht.hjtk.engine.Caps.a11y) {
            PermRow(n++, "화면 읽기(접근성)", if (A11yBridge.enabled(ctx) && !A11yBridge.bound()) "켜졌는데 연결 안 됨 → 앱 정보 ⋮ '제한된 설정 허용'" else "Gemini 답 자동 회수 · 화면 공유", A11yBridge.bound(), optional = true) {
                ctx.startActivity(if (A11yBridge.enabled(ctx) && !A11yBridge.bound()) Perms.appInfo(ctx) else Perms.a11yIntent()) }; Hairline()
            } else { NumRow(n++, "화면 읽기(접근성)", "이 빌드(standard)에는 없어요. Gemini 답은 '공유 → HJTK' 1탭으로 받습니다. 필요하면 full 빌드를 설치하세요.", trailing = "없음", dot = C.Hair); Hairline() }
            PermRow(n++, "캘린더", "구글 캘린더(기기 동기화) 읽기·쓰기", Perms.granted(ctx, Manifest.permission.READ_CALENDAR), optional = true) { perm.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }; Hairline()
            PermRow(n++, "연락처", "받는 사람 찾기·처음 보내는 주소 판별", Perms.granted(ctx, Manifest.permission.READ_CONTACTS), optional = true) { perm.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }; Hairline()
            PermRow(n++, "배터리 최적화 제외", "긴 작업이 덜 끊겨요(삼성은 '잠자는 앱'도 확인)", Perms.ignoringBattery(ctx), optional = true) { runCatching { ctx.startActivity(Perms.batteryIntent(ctx)) } }

            SectionHead("05", "허락 규칙")
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("아는 사람에게 보내는 것은 자동 허용", style = T.head.copy(fontSize = 15.sp))
                    Text("켜도 처음 보내는 주소·링크 포함·삭제는 항상 '실행' 버튼을 거칩니다. 기본은 꺼짐.", style = T.small.copy(fontSize = 12.sp))
                }
                Toggle(s.autoAllowE2) { vm.store.updateSettings { it.copy(autoAllowE2 = !it.autoAllowE2) } }
            }
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("새 메시지는 기본으로 '작업 맡기기'", style = T.head.copy(fontSize = 15.sp)); Text("끄면 자동 판별(찾아줘·준비해줘 같은 말은 작업으로)", style = T.small.copy(fontSize = 12.sp)) }
                Toggle(s.defaultMission) { vm.store.updateSettings { it.copy(defaultMission = !it.defaultMission) } }
            }

            SectionHead("06", "나")
            Spacer(Modifier.height(10.dp))
            Field(name, { name = it }, "이름 (보고서·결과물에 사용)"); Spacer(Modifier.height(8.dp))
            Field(city, { city = it }, "사는 곳 (날씨·장소 기본값)"); Spacer(Modifier.height(8.dp))
            GhostButton("저장") { vm.store.updateSettings { it.copy(userName = name, homeCity = city.ifBlank { "서울" }) } }

            SectionHead("07", "데이터")
            NumRow(1, "기억 관리", "${vm.store.memories.value.size}개", onClick = { vm.go(Route.Memory) }); Hairline()
            NumRow(2, "대화 목록 · 내보내기(MD/HTML/JSON)", "API 키·토큰·경로는 기본으로 가려서 내보냅니다", onClick = { vm.go(Route.Conversations) })

            SectionHead("08", "원칙")
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
            Text("저장", style = T.small.copy(color = C.Ink, fontWeight = FontWeight.Bold), modifier = Modifier.press({ if (value.isNotBlank()) { vm.store.secrets.put(key, value.trim()); saved = true; onChange("") } }).padding(6.dp))
            if (saved) Text("삭제", style = T.small.copy(color = C.Red, fontWeight = FontWeight.Bold), modifier = Modifier.press({ vm.store.secrets.put(key, null); saved = false }).padding(6.dp))
        }
    }
}
