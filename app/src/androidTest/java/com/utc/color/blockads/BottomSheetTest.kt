package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.designsystem.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BottomSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun thousandChoicesAreLazyAndTheLastChoiceCanBeSelected() {
        var selected = -1
        val labels = (0 until 1000).map { "Lựa chọn $it" }
        compose.setContent {
            AppTheme(ThemeMode.Light) {
                AppTabHost(AppTab.Home, {}) {
                    AppPage("Kiểm tra") {
                        AppChoicePreference("Danh sách dài", labels, 0) { selected = it }
                    }
                }
            }
        }
        compose.onNodeWithText("Danh sách dài").performClick()
        Thread.sleep(500)
        compose.onNodeWithText("Lựa chọn 999").assertDoesNotExist()
        compose.onNodeWithTag("choice-list").performScrollToIndex(999)
        compose.onNodeWithText("Lựa chọn 999").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(999, selected) }
    }

    @Test
    fun sheetBottomButtonReceivesRealTouchAboveBottomNavigation() {
        var pressed = false
        var selected = AppTab.Home
        compose.setContent {
            var shown by remember { mutableStateOf(true) }
            AppTheme(ThemeMode.Light) {
                AppTabHost(AppTab.Home, { selected = it }) {
                    AppPage("Kiểm tra") {
                        AppBottomSheet(shown, "Chi tiết", { shown = false }) {
                            AppAction("Nút cuối sheet") {
                                pressed = true
                                shown = false
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.onNodeWithText("Nút cuối sheet").performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue("Bottom nav intercepted the sheet button", pressed)
            assertEquals(AppTab.Home, selected)
        }
    }

    @Test
    fun preferenceSheetChoiceAndCancelDoNotNavigateToAnUnderlyingTab() {
        var selectedTab = AppTab.Home
        var selectedChoice = -1
        compose.setContent {
            AppTheme(ThemeMode.Light) {
                AppTabHost(AppTab.Home, { selectedTab = it }) {
                    AppPage("Kiểm tra") {
                        AppChoicePreference("Chọn thử", listOf("Một", "Hai", "Ba"), 0) {
                            selectedChoice = it
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Chọn thử").performClick()
        Thread.sleep(500)
        compose.onNodeWithText("Ba").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, selectedChoice)
            assertEquals(AppTab.Home, selectedTab)
        }
        Thread.sleep(500)
        compose.onNodeWithText("Chọn thử").performClick()
        Thread.sleep(500)
        compose.onNodeWithText(appString(L10n.string.cancel)).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(AppTab.Home, selectedTab) }
    }
}
