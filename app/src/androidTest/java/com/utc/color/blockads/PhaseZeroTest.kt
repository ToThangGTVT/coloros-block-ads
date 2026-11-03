package com.utc.color.blockads

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.enginebridge.EngineBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseZeroTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeBridgeAndThemePickerWork() {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        val originalTheme = runBlocking { model.settings.theme.first() }
        try {
            assertEquals("0.5.0-phase5", EngineBridge().version())
            compose.onNodeWithText("Cài đặt").performClick()
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(androidx.compose.ui.test.hasText("Rust JNI · 0.5.0-phase5"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            Thread.sleep(600)
            for (mode in listOf("Sáng", "Tối", "Monet", "Theo hệ thống")) {
                compose.onNodeWithText("Chủ đề").performScrollTo()
                Thread.sleep(600)
                compose.onNodeWithText("Chủ đề").performClick()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasText(mode))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText(mode).performClick()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasText("Chủ đề"))
                        .fetchSemanticsNodes()
                        .size == 1
                }
                compose.onNodeWithText("Chủ đề").assertExists()
                compose.onNodeWithText("Trang chủ").performClick()
                compose.waitForIdle()
                Thread.sleep(600)
                shell("mkdir -p /sdcard/Download/blockads-phase0")
                val index = listOf("Sáng", "Tối", "Monet", "Theo hệ thống").indexOf(mode)
                shell("screencap -p /sdcard/Download/blockads-phase0/theme-$index.png")
                compose.onNodeWithText("Cài đặt").performClick()
                Thread.sleep(600)
            }
            compose.onNodeWithText("Ứng dụng").performScrollTo()
            Thread.sleep(600)
            compose.onNodeWithText("Ứng dụng").performClick()
            compose.waitUntil(10000) {
                compose
                    .onAllNodes(androidx.compose.ui.test.hasText("Tìm ứng dụng"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Tìm ứng dụng").assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Tìm ứng dụng").assertExists()
            val metrics = compose.activity.resources.displayMetrics
            shell(
                "input swipe 1 ${metrics.heightPixels / 2} ${metrics.widthPixels * 2 / 3} ${metrics.heightPixels / 2} 500"
            )
            compose.waitForIdle()
            compose.onNodeWithText("Chủ đề").assertExists()
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/Download/blockads-ui-failure.png")
            throw failure
        } finally {
            runBlocking { model.settings.setTheme(originalTheme) }
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes() }
    }
}
