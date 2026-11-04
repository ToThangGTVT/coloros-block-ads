package com.utc.color.blockads.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.utc.color.blockads.core.common.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun liveUpdatesDefaultToEnabledAndPersistWhenDisabled() = runTest {
        val file = File(temporaryFolder.root, "live-updates.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
            produceFile = { file },
        )
        try {
            val repository = SettingsRepository(store)
            assertEquals(true, repository.liveUpdates.first())
            repository.setLiveUpdates(false)
            assertEquals(false, repository.liveUpdates.first())
        } finally {
            job.cancelAndJoin()
        }
        val reopenedJob = SupervisorJob()
        try {
            val reopened = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + reopenedJob),
                produceFile = { file },
            )
            val repository = SettingsRepository(reopened)
            assertEquals(false, repository.liveUpdates.first())
            repository.setLiveUpdates(true)
            assertEquals(true, repository.liveUpdates.first())
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test
    fun themeSurvivesReopeningStoreAndUnknownValuesFallBackToSystem() = runTest {
        val file = File(temporaryFolder.root, "settings.preferences_pb")
        val firstJob = SupervisorJob()
        val first =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + firstJob),
                produceFile = { file },
            )
        val repository = SettingsRepository(first)
        assertEquals(ThemeMode.System, repository.theme.first())
        repository.setTheme(ThemeMode.Monet)
        assertEquals(ThemeMode.Monet, repository.theme.first())
        firstJob.cancelAndJoin()

        val reopenedJob = SupervisorJob()
        try {
            val reopened =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler) + reopenedJob),
                    produceFile = { file },
                )
            assertEquals(ThemeMode.Monet, SettingsRepository(reopened).theme.first())
            reopened.edit { it[stringPreferencesKey("theme_mode")] = "removed-future-mode" }
            assertEquals(ThemeMode.System, SettingsRepository(reopened).theme.first())
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }
}
