package com.utc.color.blockads

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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
        val catalog = model.filters.catalog()
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
}
