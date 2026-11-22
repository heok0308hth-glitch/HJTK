package com.hjtk.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.screens.MainScreen
import com.hjtk.app.ui.theme.HjtkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end through the UI: the text a person types reaches the router, the
 * executor and the stores, and the result comes back as a chat card. This is
 * the closest a JVM test can get to "I tapped it and it really happened".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(): HjtkViewModel {
        val vm = HjtkViewModel(ApplicationProvider.getApplicationContext())
        vm.newSession()
        compose.setContent { HjtkTheme(dark = false) { MainScreen(vm = vm) } }
        return vm
    }

    @Test
    fun rememberedFactLandsInTheMemoryStore() {
        val vm = launch()
        compose.onNodeWithTag("composer").performTextInput("내 취향은 아이스 아메리카노라고 기억해")
        compose.onNodeWithContentDescription("보내기").performClick()

        awaitUntil(6_000, "기억이 저장되지 않았습니다") {
            vm.engine.memory.all().any { it.value.contains("아이스 아메리카노") }
        }
        val entry = vm.engine.memory.all().first { it.value.contains("아이스 아메리카노") }
        assertTrue(entry.key.isNotBlank(), "기억 키가 비었습니다")
        assertTrue(
            entry.key.contains("취향") || entry.value.contains("취향"),
            "무엇을 기억했는지 알 수 없습니다: key=${entry.key} value=${entry.value}"
        )
        compose.onNodeWithText("내 취향은 아이스 아메리카노라고 기억해").assertIsDisplayed()
    }

    @Test
    fun appOpenRequestAsksForApprovalAndCancelStopsIt() {
        val vm = launch()
        compose.onNodeWithTag("composer").performTextInput("카카오톡 앱 실행해줘")
        compose.onNodeWithContentDescription("보내기").performClick()

        awaitUntil(6_000, "승인 카드가 나타나지 않았습니다") {
            vm.engine.orchestrator.state.value.jobs.any { it.pendingApproval != null }
        }
        compose.onNodeWithText("확인 필요").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        awaitUntil(6_000, "취소 후에도 작업이 남아 있습니다") {
            vm.engine.orchestrator.state.value.jobs.none { it.pendingApproval != null }
        }
    }

    @Test
    fun workTabRunsAPipelineWithoutParameters() {
        val vm = launch()
        compose.onNodeWithText("작업").performClick()
        compose.onNodeWithText("집중 모드").assertIsDisplayed()
        // The play icon belongs to the pipeline card; the first one is enough.
        compose.onAllNodes(hasContentDescription("실행"))[0].performClick()
        awaitUntil(8_000, "파이프라인이 시작되지 않았습니다") {
            vm.engine.orchestrator.state.value.jobs.isNotEmpty() ||
                vm.engine.journal.records().any { it.kind == "plan_started" && it.detail == "집중 모드" }
        }
    }

    @Test
    fun unknownRequestGetsAnHonestAnswerNotAnExecution() {
        val vm = launch()
        val before = vm.engine.journal.records().count { it.kind == "plan_started" }
        compose.onNodeWithTag("composer").performTextInput("우주선 조립 매뉴얼")
        compose.onNodeWithContentDescription("보내기").performClick()
        Thread.sleep(500)
        val after = vm.engine.journal.records().count { it.kind == "plan_started" }
        assertEquals(before, after, "라우팅 실패인데 계획이 실행되었습니다")
    }
}
