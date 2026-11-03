package com.utc.color.blockads

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.enginebridge.EngineBridge
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseZeroTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeBridgeAndThemePickerWork() {
        assertEquals("0.1.0-phase1", EngineBridge().version())
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("Rust JNI · 0.1.0-phase1"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Cài đặt").performClick()
        for (mode in listOf("Sáng", "Tối", "Monet", "Theo hệ thống")) {
            compose.onNodeWithText("Chủ đề").performClick()
            compose.onNodeWithText(mode).performClick()
            compose.onNodeWithText("Chủ đề").assertExists()
            compose.onNodeWithText("Trang chủ").performClick()
            compose.waitForIdle()
            shell("mkdir -p /sdcard/Download/blockads-phase0")
            val index = listOf("Sáng", "Tối", "Monet", "Theo hệ thống").indexOf(mode)
            shell("screencap -p /sdcard/Download/blockads-phase0/theme-$index.png")
            compose.onNodeWithText("Cài đặt").performClick()
        }
        compose.onNodeWithText("Ứng dụng").performClick()
        compose
            .onNodeWithText("Lọc, bỏ qua lọc và bỏ qua tunnel theo ứng dụng sẽ có ở Phase 3.")
            .assertExists()
        compose.activityRule.scenario.recreate()
        compose
            .onNodeWithText("Lọc, bỏ qua lọc và bỏ qua tunnel theo ứng dụng sẽ có ở Phase 3.")
            .assertExists()
        val metrics = compose.activity.resources.displayMetrics
        shell(
            "input swipe 1 ${metrics.heightPixels / 2} ${metrics.widthPixels * 2 / 3} ${metrics.heightPixels / 2} 500"
        )
        compose.waitForIdle()
        compose.onNodeWithText("Chủ đề").assertExists()
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
