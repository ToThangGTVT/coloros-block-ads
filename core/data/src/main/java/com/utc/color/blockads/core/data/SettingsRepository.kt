package com.utc.color.blockads.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.utc.color.blockads.core.common.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    val theme =
        store.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences ->
                ThemeMode.entries.firstOrNull { it.name == preferences[ThemeKey] }
                    ?: ThemeMode.System
            }
            .distinctUntilChanged()

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[ThemeKey] = mode.name }
    }

    private companion object {
        val ThemeKey = stringPreferencesKey("theme_mode")
    }
}
