package com.utc.color.blockads

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.R as L10n
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class AppRequestsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun statisticsRowOpensOnlyItsUidAndIncludesOlderRetainedRequests() {
        val uid = 900_000_001
        val appLabel = "UID $uid"
        val now = System.currentTimeMillis()
        val current = "current-app-request-$now.test"
        val older = "older-app-request-$now.test"
        val other = "other-app-request-$now.test"

        fun event(domain: String, owner: Int, timestamp: Long) = JSONObject()
            .put("domain", domain)
            .put("uid", owner)
            .put("timestamp", timestamp)
            .put("blocked", false)

        compose.activityRule.scenario.onActivity {
            val queries = ViewModelProvider(it)[MainViewModel::class.java].queries
            queries.onDnsEvents(
                JSONObject()
                    .put("events", JSONArray()
                        .put(event(current, uid, now))
                        .put(event(older, uid, now - 25 * 3_600_000L))
                        .put(event(other, uid + 1, now)))
                    .put("totals", JSONArray())
                    .toString()
            )
        }

        compose.onNodeWithText(appString(L10n.string.log)).performClick()
        compose.onNodeWithText(appString(L10n.string.app_statistics)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(appLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(appLabel).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(older).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(current).assertExists()
        compose.onNodeWithText(other).assertDoesNotExist()

        val durationLabel = appString(L10n.string.request_capture_duration)
        val saveLabel = appString(L10n.string.save)
        compose.onNodeWithText(durationLabel).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("121")
        compose.onNodeWithText(saveLabel).performClick()
        compose.onNodeWithText(appString(L10n.string.request_capture_invalid_minutes, 120)).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("120")
        compose.onNodeWithText(saveLabel).performClick()
        val durationSummary = compose.activity.resources.getQuantityString(L10n.plurals.request_capture_minutes, 120, 120)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(durationSummary).fetchSemanticsNodes().isNotEmpty()
        }
        val captureLabel = appString(L10n.string.request_capture)
        val nextDurationLabel = appString(L10n.string.request_capture_next_duration)
        compose.onNodeWithText(captureLabel).performClick()
        compose.onNodeWithText(nextDurationLabel).assertExists()
        compose.mainClock.advanceTimeBy(119 * 60_000L)
        compose.onNodeWithText(nextDurationLabel).assertExists()
        compose.mainClock.advanceTimeBy(61_000L)
        compose.onNodeWithText(durationLabel).assertExists()

        compose.onNodeWithText(captureLabel).performClick()
        compose.onNodeWithText(nextDurationLabel).assertExists()
        compose.onNodeWithText(captureLabel).performClick()
        compose.onNodeWithText(durationLabel).assertExists()

        compose.onNodeWithText(older).performClick()
        compose.onNodeWithText(appString(L10n.string.request_preview_unavailable)).assertExists()
    }
}
