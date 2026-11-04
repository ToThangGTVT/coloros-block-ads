package com.utc.color.blockads

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.common.LocalizedException
import com.utc.color.blockads.core.common.R as L10n
import com.utc.color.blockads.core.common.parseDnsEndpoint
import com.utc.color.blockads.core.common.userMessage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalizationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        val manager = compose.activity.getSystemService(LocaleManager::class.java)
        val original = manager.applicationLocales
        fun awaitText(text: String) {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        fun openLanguage(title: String) {
            compose.onNodeWithTag("page-list").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
        }
        try {
            compose.activityRule.scenario.onActivity {
                it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
            }
            awaitText("Settings")
            compose.onAllNodesWithText("Settings").onLast().performClick()
            openLanguage("Language")
            compose.onNodeWithText("Tiếng Việt").performClick()
            awaitText("Cài đặt")
            compose.onNodeWithTag("page-list").performScrollToNode(hasText("Ngôn ngữ"))
            compose.onNodeWithText("Ngôn ngữ").assertExists()
            assertEquals("vi", manager.applicationLocales[0].language)
            compose.activityRule.scenario.recreate()
            awaitText("Cài đặt")
            openLanguage("Ngôn ngữ")
            compose.onNodeWithText("English").performClick()
            awaitText("Settings")
            assertEquals("en", manager.applicationLocales[0].language)
            openLanguage("Language")
            compose.onAllNodesWithText("System default").onLast().performClick()
            compose.waitUntil(10_000) { manager.applicationLocales.isEmpty }
        } finally {
            compose.activityRule.scenario.onActivity {
                it.getSystemService(LocaleManager::class.java).applicationLocales = original
            }
        }
    }
}
