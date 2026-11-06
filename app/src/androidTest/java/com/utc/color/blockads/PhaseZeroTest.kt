package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
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
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun nativeBridgeAndThemePickerWork() {
        lateinit var model: MainViewModel
        activity.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        val originalTheme = runBlocking { model.settings.theme.first() }
        try {
            assertEquals("0.5.0-phase5", EngineBridge().version())
            openTab("settings")
            scrollToText("page_list", "Rust JNI · 0.5.0-phase5")
            val modes = listOf(appString(L10n.string.theme_light), appString(L10n.string.theme_dark),
                "Monet", appString(L10n.string.follow_system))
            val values = listOf(
                com.utc.color.blockads.core.common.ThemeMode.Light, com.utc.color.blockads.core.common.ThemeMode.Dark,
                com.utc.color.blockads.core.common.ThemeMode.Monet, com.utc.color.blockads.core.common.ThemeMode.System,
            )
            for ((index, mode) in modes.withIndex()) {
                scrollToText("page_list", appString(L10n.string.theme)).click()
                visible(res("choice_list")).findObject(By.text(mode)).click()
                await { runBlocking { model.settings.theme.first() } == values[index] }
                openTab("home")
                text(appString(L10n.string.home_device_protection))
                shell("mkdir -p /sdcard/Download/blockads-phase0")
                shell("screencap -p /sdcard/Download/blockads-phase0/theme-$index.png")
                openTab("settings")
            }
            scrollToText("page_list", appString(L10n.string.apps)).click()
            text(appString(L10n.string.search_apps))
            activity.scenario.recreate()
            text(appString(L10n.string.search_apps))
            device.pressBack()
            scrollToText("page_list", appString(L10n.string.theme))
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
