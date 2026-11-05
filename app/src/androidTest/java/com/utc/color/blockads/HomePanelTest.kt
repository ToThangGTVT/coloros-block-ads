package com.utc.color.blockads

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.designsystem.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.utc.color.blockads.core.common.R as L10n

class HomePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun panelExpandsFromContentAndCollapsesFromHandle() {
        var dnsChange: Boolean? = null
        var httpsChange: Boolean? = null
        compose.setContent {
            AppTheme(ThemeMode.Light) {
                AppHomeDashboard(
                    title = "Protection active", checked = true, canToggle = true,
                    rules = "100 rules", total = 100, blocked = 10,
                    onProtection = { dnsChange = it }, httpsEnabled = false, httpsToggleEnabled = true,
                    onHttpsProtection = { httpsChange = it }, onSettings = {},
                    notices = {}, statistics = {},
                )
            }
        }
        val panel = compose.onNodeWithTag("home-panel")
        val dns = compose.onNodeWithText(compose.activity.getString(L10n.string.home_dns_protection))
        val https = compose.onNodeWithText(compose.activity.getString(L10n.string.home_https_protection))
        compose.onNodeWithText(compose.activity.getString(L10n.string.home_dns_description)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(L10n.string.home_https_description)).assertIsDisplayed()
        dns.assertIsDisplayed().performTouchInput { click(center) }
        https.assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(false, dnsChange)
            assertEquals(true, httpsChange)
        }
        val collapsedTop = panel.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("home-panel-content").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = panel.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Swiping content should raise the panel", expandedTop < collapsedTop - 50f)
        panel.performTouchInput {
            swipe(Offset(width / 2f, 12f), Offset(width / 2f, height * 0.85f), 400)
        }
        compose.waitForIdle()
        assertEquals(collapsedTop, panel.fetchSemanticsNode().boundsInRoot.top, 2f)
    }
}
