package com.utc.color.blockads

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import com.utc.color.blockads.core.common.R as L10n
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class AppRequestsTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

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

        lateinit var model: MainViewModel
        activity.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
            val queries = model.queries
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

        openTab("log")
        clickText(appString(L10n.string.app_statistics))
        clickText(appLabel)
        text(older)
        text(current)
        assertFalse(device.hasObject(By.text(other)))

        val originalDuration = runBlocking { model.settings.requestCaptureMinutes.first() }
        val durationLabel = appString(L10n.string.request_capture_duration)
        val saveLabel = appString(L10n.string.save)
        val captureLabel = appString(L10n.string.request_capture)
        val nextDurationLabel = appString(L10n.string.request_capture_next_duration)
        try {
            clickText(durationLabel)
            enterText("121")
            clickText(saveLabel)
            text(appString(L10n.string.request_capture_invalid_minutes, 120))
            enterText("120")
            clickText(saveLabel)
            text(targetContext.resources.getQuantityString(L10n.plurals.request_capture_minutes, 120, 120))
            clickText(captureLabel)
            text(nextDurationLabel)
            clickText(captureLabel)
            text(durationLabel)

            runBlocking { model.settings.setRequestCaptureMinutes(1) }
            text(targetContext.resources.getQuantityString(L10n.plurals.request_capture_minutes, 1, 1))
            clickText(captureLabel)
            text(nextDurationLabel)
            visible(By.text(durationLabel), timeout = 65_000)

            clickText(older)
            text(appString(L10n.string.request_preview_unavailable))
        } finally {
            runBlocking { model.settings.setRequestCaptureMinutes(originalDuration) }
        }
    }
}
