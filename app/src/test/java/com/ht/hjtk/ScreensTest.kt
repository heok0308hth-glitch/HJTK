package com.ht.hjtk

import com.ht.ensemble.agent.StageBus.S as StS
import com.ht.ensemble.agent.StageBus.Card as SCard
import com.ht.ensemble.agent.StageBus.Stage as SStage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ht.hjtk.call.AiCaller.Say as CSay
import com.ht.hjtk.call.AiCaller.Turn as Tn
import com.ht.hjtk.data.JobRec
import com.ht.hjtk.data.Role
import com.ht.hjtk.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var vm: MainVm

    @Before fun seed() {
        Motion.enabled = false; Motion.test = true
        val h = Hjtk.get(ApplicationProvider.getApplicationContext())
        val st = h.store
        st.updateSettings { it.copy(lang = "ko", onboarded = true, userName = "허태혁") }
        vm = MainVm(h)
        val c = st.newConversation("2026 디자인 공모전 준비"); vm.convId.value = c.id
        st.msg(c.id, Role.USER, "디자인 공모전 찾아서 준비해줘. 무료이고 12월 안에 마감되는 걸로.", meta = mapOf("mission" to "true"))
        st.msg(c.id, Role.SYSTEM, "작업을 맡았어요 · DeepSeek · 스킬 '공모전 준비'", "note")
        st.msg(c.id, Role.AI, "공모전 포털 4곳과 네이버를 동시에 확인할게요. 마감이 12월 안이고 참가비가 없는 것만 추립니다.", meta = mapOf("via" to "P0_STREAM", "step" to "1"))
        st.msg(c.id, Role.SYSTEM, "공모전 찾기", "cmd", mapOf("cmd" to "contest.search", "ok" to "true", "ms" to "2140", "args" to "q=디자인 n=10", "out" to "1. 2026 서울 공공디자인 공모전 …"))
        st.msg(c.id, Role.SYSTEM, "다중 페이지 읽기", "cmd", mapOf("cmd" to "web.multi", "ok" to "true", "ms" to "5320", "args" to "urls=https://www.wevity.com/…"))
        st.msg(c.id, Role.SYSTEM, "3개 실행 · 지금: 요강 정독\n진행 45% · 경과 9분 · 남은 시간 약 8~14분\n한 일: 공모전 찾기, 다중 페이지 읽기×2\n다음: 후보 3개를 표로 비교\n예정: 사용자 선택 → 초안 작성 → 검증", "report", mapOf("n" to "1", "ai" to "false"))
        st.msg(c.id, Role.AI, "후보 3개를 찾았어요. 어떤 공모전으로 준비할까요?\n\n| 공모전 | 마감 | 상금 |\n|---|---|---|\n| 서울 공공디자인 | 12-12 | 500만 |\n| 한글 타이포 | 12-20 | 300만 |", "ask", mapOf("pid" to "q1", "choices" to "서울 공공디자인|한글 타이포|둘 다", "status" to "pending"))
        st.msg(c.id, Role.SYSTEM, "기본 계정에서 contest@seoul.go.kr에게 메일을 보냅니다.\n제목: 참가 자격 문의\n본문 412자", "approval",
            mapOf("pid" to "p1", "cmd" to "mail.send", "title" to "메일 보내기", "effect" to "E2 밖으로 보냄", "warn" to "처음 보내는 상대입니다.", "digest" to "3fa19c0e72b4", "status" to "pending"))
        st.putJob(JobRec("7F3K9Q2M4XZ", c.id, "디자인 공모전 찾아서 준비해줘. 무료이고 12월 안에 마감되는 걸로.", "PAUSED_APPROVAL", System.currentTimeMillis() - 540_000, System.currentTimeMillis(),
            0.45, "약 8~14분", "후보 3개를 표로 비교", "요강 정독",
            """[{"t":"포털 4곳 검색","eta":40,"s":"DONE"},{"t":"요강 정독","eta":90,"s":"DOING"},{"t":"후보 비교표","eta":60,"s":"TODO"},{"t":"제출물 초안","eta":240,"s":"TODO"},{"t":"교차 검토","eta":120,"s":"TODO"}]""",
            "P0_STREAM", "", "메일 보내기", 1, 3))
        st.putJob(JobRec("A2B3C4D5E6F", c.id, "이번 주 일정이랑 날씨, 안 읽은 메일로 아침 브리핑", "COMPLETED", System.currentTimeMillis() - 86_400_000, System.currentTimeMillis() - 86_000_000, 1.0, "", "", "", "[]", "P0_STREAM", "## 아침 브리핑\n- 일정 3개", "완료", 1, 4))
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { HjtkTheme { content() } }
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() = this.onNode(androidx.compose.ui.test.isRoot())

    @Test fun s01_chat() = shot("01_chat") { HjtkApp(vm) }
    @Test fun s02_chatEmpty() { vm.newChat(); shot("02_chat_empty") { HjtkApp(vm) } }
    @Test fun s03_tasks() { vm.tab(Route.Tasks); shot("03_tasks") { HjtkApp(vm) } }
    @Test fun s04_job() { vm.go(Route.Job("7F3K9Q2M4XZ")); shot("04_job") { HjtkApp(vm) } }
    @Test fun s05_hub() { vm.tab(Route.Hub); shot("05_hub") { HjtkApp(vm) } }
    @Test fun s06_tools() { vm.tab(Route.Tools); shot("06_tools") { HjtkApp(vm) } }
    @Test fun s07_command() { vm.go(Route.Command("mail.send")); shot("07_command") { HjtkApp(vm) } }
    @Test fun s08_settings() { vm.go(Route.Settings); shot("08_settings") { HjtkApp(vm) } }
    @Test fun s09_onboarding() { vm.go(Route.Onboarding); shot("09_onboarding") { HjtkApp(vm) } }
    @Test fun s10_mail() { vm.go(Route.MailAdd); shot("10_mail") { HjtkApp(vm) } }
    @Test fun s11_conversations() { vm.go(Route.Conversations); shot("11_conversations") { HjtkApp(vm) } }
    /** 키보드가 열린 상태: 모드 칩 줄·탭 띠가 접히고 입력창이 키보드 바로 위에 붙는다(키보드 영역은 회색 판으로 흉내) */
    @Test fun s13_keyboard() {
        vm.draft.value = "이번 주 서울 전시회 3개 추천하고 내 캘린더에"
        shot("13_keyboard") {
            androidx.compose.runtime.CompositionLocalProvider(LocalForceIme provides true) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { HjtkApp(vm) }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(300.dp).background(androidx.compose.ui.graphics.Color(0xFFD9DCE1)))
                }
            }
        }
    }
    @Test fun s14_errors() {
        val c = vm.store.newConversation("연결 문제"); vm.convId.value = c.id
        vm.store.msg(c.id, Role.USER, "내일 오후 일정 알려줘")
        vm.store.msg(c.id, Role.SYSTEM, "DeepSeek 로그인이 필요해요. '조종석'을 눌러 한 번만 로그인해 주세요. 로그인을 마치면 이 질문을 자동으로 이어서 보낼게요.", "error", mapOf("via" to "NEEDS_LOGIN", "waiting" to "1"))
        vm.store.msg(c.id, Role.SYSTEM, "DeepSeek에 연결할 수 없어요. 인터넷 연결을 확인한 뒤 다시 보내 주세요.", "error", mapOf("via" to "OFFLINE"))
        vm.store.msg(c.id, Role.SYSTEM, "지난번 앱이 예기치 않게 종료됐어요. 같은 문제가 반복되면 아래 내용을 복사해 알려 주세요.\nIllegalStateException: example", "error", mapOf("via" to "CRASH", "detail" to "trace"))
        shot("14_errors") { HjtkApp(vm) }
    }
    @Test fun s16_foundry() {
        val c = vm.store.newConversation("전시회 추천"); vm.convId.value = c.id
        val st = vm.store
        st.msg(c.id, Role.USER, "이번 주 서울 전시회 3개 추천하고 일정에 넣어줘", meta = mapOf("mission" to "true"))
        st.msg(c.id, Role.SYSTEM, "작업을 맡았어요 · 작가 DeepSeek · 검토 DeepSeek 검토창", "note")
        st.msg(c.id, Role.SYSTEM, "A 정석 — 전시 포털 검색 후 일정 확인 (4단계)\nB 결합 — 큐레이터 리뷰 + 동선 최적화 (5단계)\nC 전복 — 내 빈 시간부터 역산 (4단계)", "collab", mapOf("role" to "구상", "who" to "DeepSeek"))
        st.msg(c.id, Role.SYSTEM, "C 전복 채택 — 빈 시간을 먼저 보니 실제로 갈 수 있는 전시만 남는다\n점수 A 정 6.8 · B 결 7.1 · C 전 8.2\n보완: 휴관일 확인", "collab", mapOf("role" to "선택", "who" to "DeepSeek 검토창"))
        st.msg(c.id, Role.SYSTEM, "이번 주 빈 시간", "cmd", mapOf("cmd" to "cal.free", "ok" to "true", "ms" to "420", "args" to "days=7"))
        st.msg(c.id, Role.SYSTEM, "전시 검색", "cmd", mapOf("cmd" to "web.search", "ok" to "true", "ms" to "1830", "args" to "q=서울 전시 이번주"))
        st.msg(c.id, Role.SYSTEM, "고칠 점 1개 (6.5/10)\n· 국립현대미술관 월요일 휴관인데 월요일에 배치함", "collab", mapOf("role" to "검증", "who" to "DeepSeek 검토창"))
        st.msg(c.id, Role.SYSTEM, "검토 의견 1개 반영 · 1,420자", "collab", mapOf("role" to "수정", "who" to "DeepSeek"))
        st.putJob(JobRec("FDRY01", c.id, "이번 주 서울 전시회 3개 추천하고 일정에 넣어줘", "RUNNING", System.currentTimeMillis() - 300_000, System.currentTimeMillis(),
            0.82, "약 1~2분", "결과물 검증", "검토 의견 반영(1회)", "[]", "P0_STREAM", "", "검증", 0, 2))
        shot("16_foundry") { HjtkApp(vm) }
    }
    @Test fun s17_picker() { vm.pickerOpen.value = true; shot("17_picker") { HjtkApp(vm) } }
    @Test fun s18_ai_settings() { vm.go(Route.Ai); shot("18_ai_settings") { HjtkApp(vm) } }
    @Test fun s19_accounts() { vm.go(Route.Accounts); shot("19_accounts") { HjtkApp(vm) } }
    @Test fun s20_phone() { vm.go(Route.Phone); shot("20_phone") { HjtkApp(vm) } }
    @Test fun s21_live() { vm.go(Route.Live); shot("21_live") { HjtkApp(vm) } }
    @Test fun s22_pc() { vm.go(Route.Pc); shot("22_pc") { HjtkApp(vm) } }
    @Test fun s23_language() { vm.store.updateSettings { it.copy(lang = "") }; shot("23_language") { HjtkApp(vm) } }
    @Config(sdk = [34], qualifiers = "w1100dp-h760dp-mdpi")
    @Test fun s24_wide() { shot("24_wide") { HjtkApp(vm) } }
    @Test fun s25_english() { vm.store.updateSettings { it.copy(lang = "en") }; vm.go(Route.Hub); shot("25_english_hub") { HjtkApp(vm) } }
    @Test fun s12_cockpit() { vm.go(Route.Cockpit); shot("12_cockpit") { HjtkApp(vm) } }

    // 2.1 — 루틴 · 사용량 · 사진 첨부
    @Test fun s26_routinesEmpty() { com.ht.hjtk.engine.Routines.list.value = emptyList(); vm.go(Route.Routines); shot("26_routines_empty") { HjtkApp(vm) } }
    @Test fun s27_routines() {
        com.ht.hjtk.engine.Routines.list.value = listOf(
            com.ht.hjtk.engine.Routine("r1", "아침 브리핑", "오늘 일정과 날씨, 안 읽은 중요한 메일을 한 화면으로 정리해 줘.", "daily", 1, 8, 0, convId = "x"),
            com.ht.hjtk.engine.Routine("r2", "하루 마무리", "끝난 일과 남은 일을 나눠 정리해 줘.", "weekdays", 1, 18, 30),
            com.ht.hjtk.engine.Routine("r3", "주간 계획", "이번 주 계획을 세워 줘.", "weekly", 1, 9, 0, enabled = false))
        vm.go(Route.Routines); shot("27_routines") { HjtkApp(vm) }
    }
    @Test fun s28_usage() {
        repeat(6) { com.ht.hjtk.engine.UsageLog.add("DeepSeek", 1800, 2400) }
        repeat(3) { com.ht.hjtk.engine.UsageLog.add("Claude Sonnet 5.5", 3100, 1900) }
        com.ht.hjtk.engine.UsageLog.add("Gemini 3.8 Flash", 2200, 700); com.ht.hjtk.engine.UsageLog.add("ChatGPT 앱", 900, 1400)
        vm.go(Route.Usage); shot("28_usage") { HjtkApp(vm) }
    }
    @Test fun s29_imageChat() {
        val f = java.io.File(vm.h.store.workspace, "images/test.jpg").apply { parentFile?.mkdirs() }
        val bmp = android.graphics.Bitmap.createBitmap(640, 420, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply { drawColor(0xFFE9E6DF.toInt()); drawRect(60f, 60f, 580f, 140f, android.graphics.Paint().apply { color = 0xFF16161A.toInt() }); drawRect(60f, 180f, 380f, 210f, android.graphics.Paint().apply { color = 0xFF8A8A8A.toInt() }); drawRect(60f, 300f, 240f, 360f, android.graphics.Paint().apply { color = 0xFFD8432B.toInt() }) }
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        val c = vm.h.store.newConversation("사진 질문"); vm.convId.value = c.id
        vm.h.store.msg(c.id, Role.USER, "이 화면에서 버튼 배치 어때?", meta = mapOf("image" to f.absolutePath))
        vm.h.store.msg(c.id, Role.AI, "제목 바가 화면 폭을 거의 다 차지해서 시선이 거기서 멈춰요. 빨간 버튼은 왼쪽 아래에 있어 엄지가 닿기 어렵습니다. 오른쪽 아래로 옮기고 크기를 1.5배로 키우는 걸 권해요.", meta = mapOf("via" to "API_CLAUDE"))
        vm.pendingImage.value = f.absolutePath
        shot("29_image_chat") { HjtkApp(vm) }
    }

    // 3.0 — AI 전화(내 목소리) · 통화 화면
    @Test fun s30_phone() { vm.h.caller.session.value = com.ht.hjtk.call.AiCaller.Session(); vm.go(Route.Phone); shot("30_phone") { HjtkApp(vm) } }
    @Test fun s31_phoneScript() {
        val h = vm.h
        h.caller.session.value = com.ht.hjtk.call.AiCaller.Session(number = "02-6205-1180", goal = "토요일 저녁 7시 4명 예약 가능한지 묻고, 되면 예약", phase = com.ht.hjtk.call.AiCaller.Phase.RENDERING,
            opening = CSay("open", "첫 인사", "안녕하세요, 저는 허태혁님을 대신해 전화드리는 AI 비서입니다. 이번 주 토요일 저녁 7시에 4명 예약 가능할까요?", ready = true),
            branches = listOf(CSay("b0", "가능하다", "좋아요, 허태혁 이름으로 4명 예약 부탁드립니다.", ready = true), CSay("b1", "자리가 없다", "혹시 8시나 일요일 같은 시간은 어떨까요?", ready = true),
                CSay("b2", "연락처 물음", "연락처는 이 번호로 남겨 주시면 됩니다."), CSay("b3", "보증금 안내", "보증금은 본인이 직접 확인하고 다시 연락드릴게요.")),
            closing = CSay("close", "마무리", "확인 감사합니다. 좋은 하루 보내세요."), rendered = 9, total = 16)
        vm.go(Route.Phone); shot("31_phone_script") { HjtkApp(vm) }
    }
    @Test fun s32_callIncoming() {
        com.ht.hjtk.call.CallCenter.lines.value = listOf(com.ht.hjtk.call.CallCenter.Line(1, "010-2345-6789", "김민서", android.telecom.Call.STATE_RINGING, 0L, true))
        shot("32_call_incoming") { com.ht.hjtk.ui.CallScreen(vm.h, "", onClose = {}) }
        com.ht.hjtk.call.CallCenter.lines.value = emptyList()
    }
    @Test fun s33_callAi() {
        com.ht.hjtk.call.CallCenter.lines.value = listOf(com.ht.hjtk.call.CallCenter.Line(2, "0262051180", null, android.telecom.Call.STATE_ACTIVE, System.currentTimeMillis() - 83_000, false, ai = true))
        vm.h.caller.session.value = com.ht.hjtk.call.AiCaller.Session(number = "0262051180", phase = com.ht.hjtk.call.AiCaller.Phase.LIVE, mind = com.ht.hjtk.call.AiCaller.Mind.THINKING,
            transcript = listOf(Tn("AI", "안녕하세요, 저는 허태혁님을 대신해 전화드리는 AI 비서입니다. 이번 주 토요일 저녁 7시에 4명 예약 가능할까요?"),
                Tn("상대", "토요일 7시는 다 찼고요, 8시 반은 돼요."), Tn("AI", "그럼 8시 반으로 4명 부탁드려요. 예약자는 허태혁입니다."), Tn("상대", "연락처 하나 남겨 주시겠어요?")))
        shot("33_call_ai") { com.ht.hjtk.ui.CallScreen(vm.h, "", onClose = {}) }
        com.ht.hjtk.call.CallCenter.lines.value = emptyList()
    }
    @Test fun s35_hubLight() { com.ht.hjtk.ui.C.dark = false; vm.go(Route.Hub); shot("35_hub_light") { HjtkApp(vm) }; com.ht.hjtk.ui.C.dark = true }
    @Test fun s36_emptyChat() { vm.newChat(); shot("36_empty_chat") { HjtkApp(vm) } }
    @Test fun s37_picker() { vm.pickerOpen.value = true; shot("37_model_picker") { HjtkApp(vm) }; vm.pickerOpen.value = false }
    /** 3.3 지휘자 무대: 센터 Claude + 병렬로 일하는 팀원 카드(교체·완료·실행) */
    private fun seedStage(id: String) {
        val now = System.currentTimeMillis()
        com.ht.ensemble.agent.StageBus.put(com.ht.ensemble.agent.StageBus.Stage(id, "디자인 공모전 준비", "Claude", "CLAUDE", "실행", listOf(
            com.ht.ensemble.agent.StageBus.Card("t1", "공모전 4곳 조사", "Gemini", "GEMINI", StS.DONE, "12개 후보 · 2,140자", now - 95_000, now - 51_000, peer = "ChatGPT 확인 · 주의 남김"),
            com.ht.ensemble.agent.StageBus.Card("t2", "요강 정독 · 자격 확인", "GPT", "GPT", StS.RUN, "읽는 중 · 서울 공공디자인 요강", now - 38_000),
            com.ht.ensemble.agent.StageBus.Card("t3", "마감·상금 비교표", "DeepSeek", "DEEPSEEK", StS.RUN, "표로 정리하는 중", now - 21_000),
            com.ht.ensemble.agent.StageBus.Card("t4", "최신 수상작 경향", "Grok", "GROK", StS.SWAP, "GPT 사용량 한도 → Grok로 교체", now - 9_000),
            com.ht.ensemble.agent.StageBus.Card("t5", "제출물 초안", "ChatGPT", "GPT", StS.WAIT, "t2·t3 끝나면 시작"),
        ), progress = 0.46, note = "후보 비교표", board = listOf("Gemini · 사실 · 서울 공공디자인 마감 12월 12일(공고문)", "ChatGPT · 주의 · 한글 타이포 상금 300만→200만으로 정정", "DeepSeek · 질문 · 팀 출품 허용 여부 확인 필요")))
        com.ht.hjtk.engine.LiveBus.put(id, "GPT", "참가 자격은 만 19세 이상 개인 또는 3인 이하 팀이며, 출품작은 미발표작이어야 합니다")
    }
    @Test fun s38_stageChat() { seedStage("7F3K9Q2M4XZ"); shot("38_stage_chat") { HjtkApp(vm) }; com.ht.ensemble.agent.StageBus.drop("7F3K9Q2M4XZ") }
    @Test fun s39_stageJob() { seedStage("7F3K9Q2M4XZ"); vm.go(Route.Job("7F3K9Q2M4XZ")); shot("39_stage_job") { HjtkApp(vm) }; com.ht.ensemble.agent.StageBus.drop("7F3K9Q2M4XZ") }
    /** 3.5 회사 모드: 스프린트 3 · 남은 시간 · 품질 · 티켓 보드 · 산출물 */
    private fun seedProject(id: String) {
        val now = System.currentTimeMillis()
        com.ht.ensemble.agent.StageBus.put(SStage(id, "청주 카페 창업 사업계획서", "Claude", "CLAUDE", "스프린트 3", listOf(
            SCard("S3-1", "재무 민감도 분석", "ChatGPT", "GPT", StS.RUN, "분석하는 중 · 03_재무계획.md", now - 140_000, round = 3),
            SCard("S3-2", "경쟁 카페 5곳 사례", "Gemini", "GEMINI", StS.CHECK, "Grok가 채점하는 중", now - 260_000, round = 3, peer = ""),
            SCard("S3-3", "리스크와 대응", "DeepSeek", "DEEPSEEK", StS.RUN, "리뷰 반영해 다시 쓰는 중 (1)", now - 90_000, round = 3, peer = "ChatGPT 리뷰 7.2 · 수정 2"),
            SCard("S3-4", "12주 실행 일정 다듬기", "Grok", "GROK", StS.WAIT, "앞 티켓 기다리는 중 · 04_실행계획.md", round = 3),
            SCard("T2", "메뉴·가격", "DeepSeek", "DEEPSEEK", StS.DONE, "02_사업모델.md · 8.7점", now - 2_400_000, now - 1_900_000, round = 1, peer = "Gemini 리뷰 8.7 · 통과"),
            SCard("T1", "상권 조사", "Gemini", "GEMINI", StS.DONE, "01_시장조사.md · 8.4점", now - 2_600_000, now - 2_000_000, round = 1, peer = "ChatGPT 리뷰 8.4 · 통과"),
        ), progress = 0.48, note = "청주 카페 사업계획", board = listOf("Gemini · 사실 · 성안길 평일 유동인구 하루 약 4.2만 명(상권정보)", "ChatGPT · 결정 · 임대료는 성안길 1층 평균으로 가정"),
            sprint = 3, deadline = now + 58 * 60_000L + 12_000, quality = 8.3, budgetMin = 120,
            files = listOf("01_시장조사.md|6214|8.4", "02_사업모델.md|4870|8.7", "03_재무계획.md|5102|7.6", "04_실행계획.md|2980|8.1", "05_리스크.md|0|")))
    }
    @Test fun s41_projectChat() { seedProject("7F3K9Q2M4XZ"); shot("41_project_chat") { HjtkApp(vm) }; com.ht.ensemble.agent.StageBus.drop("7F3K9Q2M4XZ") }
    @Test fun s42_projectJob() { seedProject("7F3K9Q2M4XZ"); vm.go(Route.Job("7F3K9Q2M4XZ")); shot("42_project_job") { HjtkApp(vm) }; com.ht.ensemble.agent.StageBus.drop("7F3K9Q2M4XZ") }
    @Test fun s43_projectPicker() { vm.mode.value = Mode.MISSION; vm.difficulty.value = com.ht.ensemble.ai.Difficulty.PROJECT; vm.store.updateSettings { it.copy(projectMinutes = 120) }; shot("43_project_time") { HjtkApp(vm) }; vm.difficulty.value = com.ht.ensemble.ai.Difficulty.STANDARD; vm.mode.value = Mode.AUTO }
    /** 3.6 결과물 카드: 앱 → 설치·미리보기, 완성본, ZIP */
    @Test fun s44_deliver() {
        val c = vm.store.newConversation("할 일 정원 앱"); vm.convId.value = c.id
        vm.store.msg(c.id, Role.USER, "물 주듯 할 일을 키우는 앱 만들어줘")
        vm.store.msg(c.id, Role.SYSTEM, "프로젝트를 맡았어요 · 60분 · PM이 헌장을 쓰고 스프린트로 진행해요\n팀 Claude · ChatGPT · Gemini · DeepSeek | 빠짐 Grok(로그인 안 됨) → 허브 → AI 상태 점검", "note", mapOf("job" to "DLV1"))
        val d = vm.store.jobDir("DLV1"); java.io.File(d, "dist").mkdirs(); java.io.File(d, "app").mkdirs()
        java.io.File(d, "dist/할_일_정원.apk").writeBytes(ByteArray(48_000)); java.io.File(d, "dist/할_일_정원.html").writeText("<h1>x</h1>"); java.io.File(d, "dist/할_일_정원.zip").writeBytes(ByteArray(9_000)); java.io.File(d, "app/index.html").writeText("<p>x</p>")
        vm.store.msg(c.id, Role.AI, "할 일 정원", "deliver", mapOf("job" to "DLV1", "title" to "할 일 정원", "apk" to "dist/할_일_정원.apk", "apk_label" to "설치 파일 · 46KB", "preview" to "app/index.html",
            "html" to "dist/할_일_정원.html", "html_label" to "완성본 · 4장", "zip" to "dist/할_일_정원.zip", "zip_label" to "프로젝트 전체 · 8KB", "saved" to "다운로드/HJTK/할_일_정원"))
        shot("44_deliver") { HjtkApp(vm) }
    }
    /** 3.7.1 3D 스튜디오: 예시 장면(배달 로봇)이 실제로 그려진 뷰포트 · 도구 · 덱 */
    @Test fun s47_studio() { vm.go(Route.Studio()); shot("47_studio") { HjtkApp(vm) } }
    @Test fun s48_studioChair() {
        val f = com.ht.hjtk.core.three.Studio3D.save(vm.store.workspace, com.ht.mesh.Scene.parse(com.ht.mesh.Samples.CHAIR))
        vm.go(Route.Studio(f.absolutePath)); shot("48_studio_chair") { HjtkApp(vm) }
    }
    @Test fun s49_studioLight() { com.ht.hjtk.ui.C.dark = false; vm.go(Route.Studio()); shot("49_studio_light") { HjtkApp(vm) }; com.ht.hjtk.ui.C.dark = true }
    /** AI 팀이 만든 3D 결과물 카드: 미리보기 그림 · 3D 스튜디오에서 열기 · .glb */
    @Test fun s50_deliver3d() {
        val c = vm.store.newConversation("탁상 램프 3D"); vm.convId.value = c.id
        vm.store.msg(c.id, Role.USER, "황동 탁상 램프를 3D로 모델링해줘. 블렌더에서 열 수 있게")
        val d = vm.store.jobDir("D3D1")
        val sf = java.io.File(d, "model/scene.json").apply { parentFile.mkdirs(); writeText(com.ht.mesh.Samples.LAMP) }
        val b = com.ht.hjtk.core.three.Studio3D.build(com.ht.mesh.Scene.parse(sf.readText()), java.io.File(d, "dist"), "Desk_lamp")
        vm.store.msg(c.id, Role.AI, "Desk lamp", "deliver", mapOf("job" to "D3D1", "title" to "탁상 램프", "glb" to "dist/Desk_lamp.glb", "glb_label" to "${b.stats} · .glb·.obj",
            "scene" to "model/scene.json", "png" to "dist/Desk_lamp.png", "saved" to "다운로드/HJTK/탁상_램프"))
        shot("50_deliver_3d") { HjtkApp(vm) }
    }
    @Test fun s45_aiHealth() {
        com.ht.hjtk.engine.AiHealth.results.value = mapOf(
            com.ht.ensemble.ai.Provider.GPT to com.ht.hjtk.engine.AiHealth.Check(com.ht.ensemble.ai.Provider.GPT, false, 75_000, "", "보냈지만 답을 읽지 못했어요(답 위치를 못 찾음)",
                """{"url":"https://chatgpt.com/","input":"#prompt-textarea","inputGeneric":true,"send":"button[data-testid=\"send-button\"]","answersCfg":0,"answersAll":0,"login":false,"challenge":false}"""),
            com.ht.ensemble.ai.Provider.DEEPSEEK to com.ht.hjtk.engine.AiHealth.Check(com.ht.ensemble.ai.Provider.DEEPSEEK, true, 6_400, "2", "", null))
        vm.go(Route.AiHealth); shot("45_ai_health") { HjtkApp(vm) }
        com.ht.hjtk.engine.AiHealth.results.value = emptyMap()
    }
    @Test fun s40_github() { vm.go(Route.GitHub); shot("40_github") { HjtkApp(vm) } }
    @Test fun s34_dialer() { shot("34_dialer") { com.ht.hjtk.ui.CallScreen(vm.h, "0262051180", onClose = {}) } }
}
