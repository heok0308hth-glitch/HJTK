package com.ht.hjtk.ui

import com.ht.hjtk.core.I18n
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.hjtk.R
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.Perms

@Composable
fun HjtkApp(vm: MainVm) {
    val route by vm.route.collectAsState()
    val palette by vm.paletteOpen.collectAsState()
    val pending by vm.runner.pending.collectAsState()
    BackHandler(enabled = palette || route !in listOf(Route.Chat)) {
        if (vm.pickerOpen.value) vm.pickerOpen.value = false else if (palette) vm.paletteOpen.value = false else if (!vm.back()) vm.tab(Route.Chat)
    }
    val settings by vm.store.settings.collectAsState()
    HjtkTheme {
        // 첫 실행: 언어부터
        if (settings.lang.isBlank()) { LanguageScreen { en -> I18n.en = en; vm.store.updateSettings { it.copy(lang = if (en) "en" else "ko") } }; return@HjtkTheme }
        I18n.en = settings.lang == "en"
        key(settings.lang) {
        Box(Modifier.fillMaxSize().background(C.Paper)) {
            val tabbed = route in listOf(Route.Chat, Route.Tasks, Route.Hub, Route.Tools)
            val ime = imeOpen()
            // 키보드 높이는 여기서 한 번만 뺀다. 자식의 navigationBarsPadding은 키보드가 덮은 만큼 자동으로 0이 된다.
            Column(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.weight(1f)) {
                    when (val r = route) {
                        Route.Chat -> androidx.compose.foundation.layout.BoxWithConstraints {
                            // 넓은 화면(태블릿·크롬북·PC 창): 대화 목록을 옆에 고정
                            if (maxWidth >= 840.dp) Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp).fillMaxHeight()) { ConversationsScreen(vm) }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(C.Hair))
                                Box(Modifier.weight(1f)) { ChatScreen(vm) }
                            } else ChatScreen(vm)
                        }
                        Route.Tasks -> TasksScreen(vm)
                        Route.Hub -> HubScreen(vm)
                        Route.Tools -> ToolsScreen(vm)
                        Route.Settings -> SettingsScreen(vm)
                        Route.Cockpit -> CockpitScreen(vm)
                        Route.Conversations -> ConversationsScreen(vm)
                        Route.Onboarding -> Onboarding(vm)
                        Route.Memory -> MemoryScreen(vm)
                        Route.MailAdd -> MailAddScreen(vm)
                        Route.GitHub -> GitHubScreen(vm)
                        Route.AiHealth -> AiHealthScreen(vm)
                        Route.Ai -> AiSettingsScreen(vm)
                        Route.Accounts -> AccountsScreen(vm)
                        Route.Browser -> BrowserScreen(vm)
                        Route.Phone -> PhoneScreen(vm)
                        Route.Live -> LiveScreen(vm)
                        Route.Pc -> PcScreen(vm)
                        Route.Routines -> RoutinesScreen(vm)
                        Route.Usage -> UsageScreen(vm)
                        Route.AiLogin -> AiLoginScreen(vm)
                        is Route.Job -> JobScreen(vm, r.id)
                        is Route.Preview -> PreviewScreen(vm, r.path, r.title)
                        is Route.Command -> CommandScreen(vm, r.id)
                        is Route.PipelineEdit -> PipelineEditScreen(vm, r.id)
                    }
                }
                // 타이핑 중에는 탭을 숨긴다: 입력창이 키보드 바로 위에 붙어야 대화가 가장 많이 보인다
                if (!ime) {
                    if (tabbed && route != Route.Chat) BottomBar(vm, route, pending.size)
                    else if (route == Route.Chat) BottomBarSlim(vm, pending.size)
                }
            }
            if (palette) Palette(vm)
            ModelPickerSheet(vm)
        }
        }
    }
}

// 3.1: 탭 세 개로 줄였다(도구·설정은 허브 안). 자주 쓰는 것만 손 닿는 곳에
private val tabs = listOf(Triple(Route.Chat, I.Chat, "대화"), Triple(Route.Tasks, I.Tasks, "작업"), Triple(Route.Hub, I.Hub, "허브"))

@Composable
private fun BottomBar(vm: MainVm, route: Route, pending: Int) {
    Column(Modifier.fillMaxWidth().background(C.Paper).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            tabs.forEach { (r, ic, l0) -> val l = I18n.t(l0)
                val on = r == route
                Column(Modifier.weight(1f).fillMaxHeight().press({ vm.tab(r) }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box { Ico(ic, 22.dp, if (on) C.Ink else C.Mute); if (r == Route.Tasks && pending > 0) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp)) { SignalDot(C.Red, 7.dp) } }
                    Spacer(Modifier.height(3.dp))
                    Text(l, style = T.label.copy(fontSize = 10.sp, color = if (on) C.Ink else C.Mute, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.5.sp))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(18.dp).height(2.dp).background(if (on) C.Ink else C.Paper))
                }
            }
        }
    }
}

/** 대화 화면에서는 입력창이 아래를 차지하므로 탭을 얇은 띠로 */
@Composable
private fun BottomBarSlim(vm: MainVm, pending: Int) {
    // 다크에서는 먹색 띠가 밝게 뒤집히므로 한 톤 밝은 바탕으로
    val bg = if (C.dark) C.Paper2 else C.Ink; val on = if (C.dark) C.Ink else C.OnInk; val off = if (C.dark) C.Mute else C.OnInkMute
    Row(Modifier.fillMaxWidth().background(bg).navigationBarsPadding().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { (r, ic, l0) -> val l = I18n.t(l0)
            Row(Modifier.weight(1f).fillMaxHeight().press({ vm.tab(r) }), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Ico(ic, 16.dp, if (r == Route.Chat) on else off); Spacer(Modifier.width(6.dp))
                Text(l, style = T.label.copy(fontSize = 10.5.sp, color = if (r == Route.Chat) on else off, letterSpacing = 0.6.sp))
                if (r == Route.Tasks && pending > 0) { Spacer(Modifier.width(4.dp)); SignalDot(C.Red, 6.dp, pulse = true) }
            }
        }
    }
}

@Composable
fun Onboarding(vm: MainVm) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }
    val ds by vm.h.deepseek.status.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var name by remember { mutableStateOf("") }; var city by remember { mutableStateOf("서울") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.hjtk_mark_ink), null, Modifier.size(34.dp)); Spacer(Modifier.width(10.dp)); Label("HJTK · ${step + 1}/3", color = C.Ink)
            Spacer(Modifier.weight(1f)); Text(I18n.t("건너뛰기"), style = T.small.copy(color = C.Graphite), modifier = Modifier.press({ vm.completeOnboarding() }).padding(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Text("0${step + 1}", style = T.display.copy(color = C.Red))
            Spacer(Modifier.height(10.dp))
            key(tick) {
            when (step) {
                0 -> {
                    Text("AI를 넣지 않았어요.\n이미 쓰는 AI를 부립니다.", style = T.display)
                    Spacer(Modifier.height(12.dp))
                    Text("HJTK는 폰의 DeepSeek에게 일을 맡기고, 명령어 ${CommandCatalog.ALL.size}개로 실행하고, Gemini에게 교차 검토를 받고, 5분마다 보고합니다. 중요한 일은 '실행' 버튼으로 허락받아요.", style = T.body.copy(color = C.Graphite))
                    Spacer(Modifier.height(20.dp)); Hairline(color = C.Ink)
                    NumRow(1, I18n.t("DeepSeek 연결"), if (ds == DeepSeekWeb.Status.READY) "로그인됨 — 키 없이 연결됐어요" else "조종석에서 한 번만 로그인(API 키 불필요)", dot = if (ds == DeepSeekWeb.Status.READY) C.Ink else C.Red, onClick = { vm.go(Route.Cockpit) })
                }
                1 -> {
                    Text("일하는 동안\n곁에 있을게요.", style = T.display)
                    Spacer(Modifier.height(12.dp))
                    Text(I18n.t("앱을 나가 있어도 작업은 계속되고, 5분마다 알림으로 보고를 전해요. 아래를 허용하면 더 매끄러워요(모두 나중에 바꿀 수 있음)."), style = T.body.copy(color = C.Graphite))
                    Spacer(Modifier.height(20.dp)); Hairline(color = C.Ink)
                    NumRow(1, I18n.t("알림"), "5분 보고와 허락 요청", dot = if (Build.VERSION.SDK_INT < 33 || Perms.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) C.Ink else C.Red,
                        onClick = { if (Build.VERSION.SDK_INT >= 33) perm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }); Hairline()
                    NumRow(2, I18n.t("다른 앱 위에 표시"), "백그라운드 진행 안정화", dot = if (Perms.overlay(ctx)) C.Ink else C.Red, onClick = { ctx.startActivity(Perms.overlayIntent(ctx)) }); Hairline()
                    NumRow(3, I18n.t("캘린더 · 연락처 (선택)"), "구글 캘린더를 OAuth 없이 기기 동기화로", dot = if (Perms.granted(ctx, Manifest.permission.READ_CALENDAR)) C.Ink else C.Hair,
                        onClick = { perm.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS)) }); Hairline()
                }
                else -> {
                    Text("어떻게\n불러 드릴까요?", style = T.display)
                    Spacer(Modifier.height(18.dp))
                    Field(name, { name = it }, "이름"); Spacer(Modifier.height(8.dp)); Field(city, { city = it }, "사는 곳")
                    Spacer(Modifier.height(8.dp)); Text(I18n.t("날씨·장소의 기본값과 결과물 표기에만 씁니다. 기기 밖으로 나가지 않아요."), style = T.small)
                }
            }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) GhostButton(I18n.t("이전")) { step-- }
            InkButton(if (step < 2) "다음" else "시작하기", Modifier.weight(1f), accent = step == 2) {
                if (step < 2) step++ else { vm.store.updateSettings { it.copy(userName = name, homeCity = city.ifBlank { "서울" }) }; vm.completeOnboarding() }
            }
        }
    }
}
