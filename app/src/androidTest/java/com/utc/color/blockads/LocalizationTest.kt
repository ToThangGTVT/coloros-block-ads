package com.utc.color.blockads

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.common.LocalizedException
import com.utc.color.blockads.core.common.R as L10n
import com.utc.color.blockads.core.common.parseDnsEndpoint
import com.utc.color.blockads.core.common.userMessage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalizationTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun resources(language: String) =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(language))
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun englishVietnameseAndUnsupportedLanguageHaveCompleteMessages() {
        val english = resources("en")
        val vietnamese = resources("vi")
        val fallback = resources("fr")
        assertEquals("Settings", english.getString(L10n.string.settings))
        assertEquals("1 request", english.getQuantityString(L10n.plurals.requests_count, 1, "1"))
        assertEquals("2 requests", english.getQuantityString(L10n.plurals.requests_count, 2, "2"))
        assertEquals("Cài đặt", vietnamese.getString(L10n.string.settings))
        assertEquals("Settings", fallback.getString(L10n.string.settings))
        assertEquals("Downloading 42%", english.getString(L10n.string.download_progress, 42))
        assertEquals("Đang tải 42%", vietnamese.getString(L10n.string.download_progress, 42))
        assertEquals("Line 3: invalid syntax", english.getString(L10n.string.custom_rule_line_error, 3, "invalid syntax"))
        assertTrue(vietnamese.getString(L10n.string.custom_rules_help).contains("\n||domain^"))
        assertTrue(english.getString(L10n.string.custom_rules_help).contains("\n||domain^"))
    }

    @Test
    fun validationErrorsUseTheLanguageAtDisplayTime() {
        val error = runCatching { parseDnsEndpoint("http://example.com", "1.1.1.1") }.exceptionOrNull()
        assertTrue(error is LocalizedException)
        assertEquals(
            "Enter a valid https:// (DoH) or tls:// (DoT) URL.",
            error!!.userMessage(resources("en")),
        )
        assertEquals(
            "Cần URL https:// (DoH) hoặc tls:// (DoT) hợp lệ",
            error.userMessage(resources("vi")),
        )
    }

    @Test
    fun languagePickerSwitchesBothWaysAndPersistsAcrossRecreation() {
        val manager = targetContext.getSystemService(LocaleManager::class.java)
        val original = manager.applicationLocales
        fun openLanguage(title: String) = scrollToText("page_list", title).click()
        try {
            activity.scenario.onActivity {
                if (it.resources.configuration.locales[0].language != "en") {
                    it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
                }
            }
            await {
                var language = ""
                activity.scenario.onActivity { language = it.resources.configuration.locales[0].language }
                language == "en"
            }
            visible(By.desc("Settings"))
            openTab("settings")
            openLanguage("Language")
            clickText("Tiếng Việt")
            text("Cài đặt")
            scrollToText("page_list", "Ngôn ngữ")
            assertEquals("vi", manager.applicationLocales[0].language)
            activity.scenario.recreate()
            text("Cài đặt")
            openLanguage("Ngôn ngữ")
            clickText("English")
            text("Settings")
            assertEquals("en", manager.applicationLocales[0].language)
            openLanguage("Language")
            visible(res("choice_list")).findObject(By.text("System default")).click()
            await { manager.applicationLocales.isEmpty }
        } finally {
            activity.scenario.onActivity {
                it.getSystemService(LocaleManager::class.java).applicationLocales = original
            }
        }
    }
}
