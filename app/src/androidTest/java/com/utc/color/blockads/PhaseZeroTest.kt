package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            compose.onNodeWithText(appString(L10n.string.settings)).performClick()
            compose
                .onNodeWithTag("page-list")
                .performScrollToNode(hasText("Rust JNI · 0.5.0-phase5"))
            Thread.sleep(600)
            for (mode in listOf(appString(L10n.string.theme_light), appString(L10n.string.theme_dark), "Monet", appString(L10n.string.follow_system))) {
                compose.onNodeWithTag("page-list").performScrollToNode(hasText(appString(L10n.string.theme)))
                Thread.sleep(600)
                compose.onNodeWithText(appString(L10n.string.theme)).performClick()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasText(mode))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onAllNodesWithText(mode).onLast().performClick()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasText(appString(L10n.string.theme)))
                        .fetchSemanticsNodes()
                        .size == 1
                }
                compose.onNodeWithText(appString(L10n.string.theme)).assertExists()
                compose.onNodeWithText(appString(L10n.string.home)).performClick()
                compose.waitForIdle()
                Thread.sleep(600)
                shell("mkdir -p /sdcard/Download/blockads-phase0")
                val index = listOf(appString(L10n.string.theme_light), appString(L10n.string.theme_dark), "Monet", appString(L10n.string.follow_system)).indexOf(mode)
                shell("screencap -p /sdcard/Download/blockads-phase0/theme-$index.png")
                compose.onNodeWithText(appString(L10n.string.settings)).performClick()
                Thread.sleep(600)
            }
            compose.onNodeWithTag("page-list").performScrollToNode(hasText(appString(L10n.string.apps)))
            Thread.sleep(600)
            compose.onNodeWithText(appString(L10n.string.apps)).performClick()
            compose.waitUntil(10000) {
                compose
                    .onAllNodes(androidx.compose.ui.test.hasText(appString(L10n.string.search_apps)))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(appString(L10n.string.search_apps)).assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(appString(L10n.string.search_apps)).assertExists()
            val metrics = compose.activity.resources.displayMetrics
            shell(
                "input swipe 1 ${metrics.heightPixels / 2} ${metrics.widthPixels * 2 / 3} ${metrics.heightPixels / 2} 500"
            )
            compose.waitForIdle()
            compose.onNodeWithTag("page-list").performScrollToNode(hasText(appString(L10n.string.theme)))
            compose.onNodeWithText(appString(L10n.string.theme)).assertExists()
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
