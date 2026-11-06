package com.utc.color.blockads

import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.utc.color.blockads.core.common.R as Strings

class HomePanelTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun panelExpandsFromContentAndCollapsesFromHandle() {
        text(appString(Strings.string.home_dns_protection))
        text(appString(Strings.string.home_https_protection))
        text(appString(Strings.string.home_dns_description))
        text(appString(Strings.string.home_https_description))
        val content = visible(res("home_panel_content")).visibleBounds
        fun panelTop() = visible(res("home_panel")).visibleBounds.top
        val collapsedTop = panelTop()
        val x = content.centerX()
        device.swipe(x, content.bottom - 25, x, content.top - 240, 20)
        await { panelTop() < collapsedTop - 50 }
        val handle = visible(res("panel_handle")).visibleBounds
        device.swipe(handle.centerX(), handle.centerY(), handle.centerX(), collapsedTop + 80, 25)
        await { panelTop() == collapsedTop }
        assertEquals(collapsedTop, panelTop())
        clickText(appString(Strings.string.home_statistics))
        await { panelTop() < collapsedTop - 50 }
        assertTrue(text(appString(Strings.string.last_seven_days)).visibleBounds.top > panelTop())
    }
}
