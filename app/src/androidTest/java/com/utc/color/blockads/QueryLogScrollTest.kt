package com.utc.color.blockads

import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import com.utc.color.blockads.core.data.QueryLogRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.utc.color.blockads.core.common.R as Strings
import com.utc.color.blockads.feature.querylog.R as Log

class QueryLogScrollTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun newestRequestsAppearAtTopAndButtonReturnsFromAnOlderPage() {
        lateinit var queries: QueryLogRepository
        activity.scenario.onActivity {
            queries = ViewModelProvider(it)[MainViewModel::class.java].queries
        }
        val now = System.currentTimeMillis()
        fun domain(index: Int) = "scroll-$now-$index.test"
        fun event(index: Int) = JSONObject()
            .put("domain", domain(index))
            .put("timestamp", now - index)
            .put("blocked", index == -1)
        fun insert(indices: IntRange) {
            queries.onDnsEvents(JSONObject().put("events", JSONArray(indices.map(::event))).put("totals", JSONArray()).toString())
        }
        fun rowCount(): Int {
            var count = 0
            activity.scenario.onActivity { count = it.findViewById<RecyclerView>(Log.id.query_list).adapter!!.itemCount }
            return count
        }

        insert(0..419)
        openTab("log")
        text(domain(0))
        val backToTop = By.desc(appString(Strings.string.back_to_top))
        assertFalse(device.hasObject(backToTop))

        insert(-3..-1)
        text(domain(-3))
        insert(-4..-4)
        val tops = (-4..0).map { text(domain(it)).visibleBounds.top }
        assertTrue(tops.zipWithNext().all { (newer, older) -> newer < older })
        assertFalse(device.hasObject(backToTop))

        activity.scenario.onActivity { it.findViewById<RecyclerView>(Log.id.query_list).scrollToPosition(350) }
        text(domain(346))
        val before = rowCount()
        insert(-5..-5)
        await { rowCount() > before }
        text(domain(346))
        visible(backToTop).click()
        text(domain(-5))
        await { !device.hasObject(backToTop) }

        insert(-17..-6)
        text(domain(-17))
    }
}
