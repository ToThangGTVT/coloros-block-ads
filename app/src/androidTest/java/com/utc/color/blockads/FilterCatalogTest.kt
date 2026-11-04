package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.filter.CatalogSource
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FilterCatalogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun adGuardRegistryListCanBeAddedAndRemoved() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalNetworkChecks") == "true"
        )
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        val catalog = model.filters.catalog(CatalogSource.Dns)
        assertEquals(setOf(1, 2, 3, 4), catalog.map { it.group }.toSet())
        // AdGuard Home's default blocklist is the app's default too.
        val default = catalog.first { it.name == "AdGuard DNS filter" }
        assertTrue(model.filters.lists.first().single { it.url == default.url }.enabled)
        val entry = catalog.first { it.group == 4 && it.name == "Phishing Army" }
        try {
            model.filters.addUrl(entry.url, entry.name)
            val list = model.filters.lists.first().single { it.url == entry.url }
            assertEquals(entry.name, list.name)
            assertTrue(list.error, list.ruleCount > 0)
        } finally {
            model.filters.lists.first().singleOrNull { it.url == entry.url }?.let {
                model.filters.remove(it.id)
            }
        }
        assertTrue(model.filters.lists.first().none { it.url == entry.url })
    }

    @Test
    fun adGuardExtensionFiltersAreBrowserLists() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalNetworkChecks") == "true"
        )
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        val catalog = model.filters.catalog(CatalogSource.Browser)
        assertEquals((1..7).toSet(), catalog.map { it.group }.toSet())
        // Mirrors of presets point at the preset, which is a browser list.
        val abpvn = catalog.first { "vi" in it.languages }
        assertTrue(model.filters.lists.first().single { it.url == abpvn.url }.browser)
        val entry = catalog.first { it.name == "AdGuard Widgets filter" }
        try {
            // Leaving the catalog cancels the screen's scope; the download must still finish.
            val job = launch { model.filters.addUrl(entry.url, entry.name, browser = true) }
            model.filters.lists.first { all -> all.any { it.url == entry.url } }
            job.cancelAndJoin()
            val list =
                withTimeout(60_000) {
                    model.filters.lists
                        .map { all -> all.single { it.url == entry.url } }
                        .first { it.updatedAt > 0 || it.error != null }
                }
            assertTrue(list.browser)
            assertTrue(list.error, list.ruleCount > 0)
            val refused = runCatching { model.filters.addUrl(entry.url) }
            assertEquals(appString(L10n.string.browser_filter_exists), refused.exceptionOrNull()?.message)
        } finally {
            model.filters.lists.first().singleOrNull { it.url == entry.url }?.let {
                model.filters.remove(it.id)
            }
        }
    }
}
