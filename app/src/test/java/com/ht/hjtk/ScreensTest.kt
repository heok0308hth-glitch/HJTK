package com.ht.hjtk

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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
        Motion.enabled = false
        val h = Hjtk.get(ApplicationProvider.getApplicationContext())
        val st = h.store
        st.updateSettings { it.copy(onboarded = true, userName = "허태혁") }
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
    @Test fun s12_cockpit() { vm.go(Route.Cockpit); shot("12_cockpit") { HjtkApp(vm) } }
}
