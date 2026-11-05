package com.utc.color.blockads

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.R as L10n
import com.utc.color.blockads.core.data.QueryLogRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueryLogScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun newestRequestsAppearAtTopAndButtonReturnsFromAnOlderPage() {
        lateinit var queries: QueryLogRepository
        compose.activityRule.scenario.onActivity {
            queries = ViewModelProvider(it)[MainViewModel::class.java].queries
        }
        val now = System.currentTimeMillis()
        fun domain(index: Int) = "scroll-$now-$index.test"
        fun event(index: Int) = JSONObject()
            .put("domain", domain(index))
            .put("timestamp", now - index)
            .put("blocked", index == -1)
        fun insert(events: JSONArray) {
            queries.onDnsEvents(JSONObject().put("events", events).put("totals", JSONArray()).toString())
        }
        fun awaitRow(index: Int) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(domain(index)).fetchSemanticsNodes().isNotEmpty()
            }
        }

        insert(JSONArray(List(420) { event(it) }))
        compose.onNodeWithText(appString(L10n.string.log)).performClick()
        awaitRow(0)
        val backToTop = compose.onNodeWithContentDescription(appString(L10n.string.back_to_top))
        backToTop.assertDoesNotExist()
        val list = compose.onNode(
            hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )

        fun topOf(index: Int) = compose.onNodeWithText(domain(index)).fetchSemanticsNode().boundsInRoot.top
        val previousTop = topOf(0)
        compose.mainClock.autoAdvance = false
        try {
            insert(JSONArray().put(event(-1)).put(event(-2)).put(event(-3)))
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText(domain(-3)).fetchSemanticsNodes().isNotEmpty()
            }
            // The whole burst is in the list at once: newest at the top, oldest just above old rows.
            val indexForKey = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            assertEquals(0, indexForKey(runBlocking { checkNotNull(queries.latest(domain(-3))).id }))
            assertEquals(2, indexForKey(runBlocking { checkNotNull(queries.latest(domain(-1))).id }))
            // New slots open from (one pixel) height, so older rows do not jump.
            assertEquals(previousTop, topOf(0), 4f)
            compose.mainClock.advanceTimeBy(160)
            val pushedTop = topOf(0)
            assertTrue(pushedTop > previousTop)
            insert(JSONArray().put(event(-4)))
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText(domain(-4)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue(topOf(0) > pushedTop)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val tops = (-4..0).map(::topOf)
        assertTrue(tops.zipWithNext().all { (newer, older) -> newer < older })
        backToTop.assertDoesNotExist()

        list.performScrollToIndex(350)
        // The four new requests occupy the first four indices.
        awaitRow(346)
        val rowCount = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        insert(JSONArray().put(event(-5)))
        compose.waitUntil(10_000) {
            list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount > rowCount
        }
        compose.onNodeWithText(domain(346)).assertIsDisplayed()
        backToTop.assertIsDisplayed().performClick()
        awaitRow(-5)
        compose.onNodeWithText(domain(-5)).assertIsDisplayed()
        backToTop.assertDoesNotExist()

        // Large bursts are revealed at once instead of queueing behind animations.
        insert(JSONArray((-17..-6).map { event(it) }))
        awaitRow(-17)
        compose.onNodeWithText(domain(-17)).assertIsDisplayed()
    }
}
