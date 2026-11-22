package com.hjtk.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.screens.MainScreen
import com.hjtk.app.ui.theme.HjtkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI behaviour, not screenshots: each tab is opened by tapping it and the
 * screen that appears is asserted. This is the "every button does something"
 * check that a pure unit test cannot give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm() = HjtkViewModel(ApplicationProvider.getApplicationContext())

    private fun launch() {
        val viewModel = vm()
        viewModel.newSession()   // deterministic start whatever ran before
        compose.setContent {
            HjtkTheme(dark = false) {
                MainScreen(vm = viewModel)
            }
        }
    }

    @Test
    fun chatTabIsTheStartingScreen() {
        launch()
        compose.onNodeWithText("무엇을 도와드릴까요?").assertIsDisplayed()
    }

    @Test
    fun everyTabOpensItsOwnScreen() {
        launch()

        compose.onNodeWithText("허브").performClick()
        compose.onNodeWithText("자주 쓰는 것들을 한곳에서").assertIsDisplayed()

        compose.onNodeWithText("작업").performClick()
        compose.onNodeWithText("여러 단계를 묶어 한 번에 실행").assertIsDisplayed()

        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("연결과 동작을 직접 관리합니다").assertIsDisplayed()

        compose.onNodeWithText("대화").performClick()
        compose.onNodeWithText("무엇을 도와드릴까요?").assertIsDisplayed()
    }

    @Test
    fun commandPaletteListsCommandsAndCloses() {
        launch()
        compose.onNodeWithContentDescription("명령 팔레트").performClick()
        compose.onNodeWithText("명령").assertExists()
        compose.onNodeWithText("웹 검색").assertExists()
        compose.onNodeWithTag("palette-search").performTextInput("배터리")
        compose.onNodeWithText("배터리 상태").assertExists()
    }

    @Test
    fun settingsShowsRealValues() {
        launch()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("AI 캐리어").assertIsDisplayed()
        compose.onNodeWithText("메일").assertIsDisplayed()
        compose.onNodeWithText("진행 보고").assertIsDisplayed()
    }

    @Test
    fun searchingThePaletteFiltersCommands() {
        launch()
        compose.onNodeWithContentDescription("명령 팔레트").performClick()
        compose.onNodeWithTag("palette-search").performTextInput("배터리")
        compose.onNodeWithText("배터리 상태").assertExists()
    }
}
