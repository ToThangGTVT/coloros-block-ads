package com.utc.color.blockads.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.common.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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

    private val preferences =
        store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
    val customRules = preferences.map { it[CustomRules] ?: "" }.distinctUntilChanged()
    val dns =
        preferences
            .map { value ->
                DnsSettings(
                    DnsUpstream.entries.firstOrNull { it.name == value[Upstream] }
                        ?: DnsUpstream.System,
                    BlockResponse.entries.firstOrNull { it.name == value[BlockMode] }
                        ?: BlockResponse.Zero,
                    value[Logging] ?: true,
                )
            }
            .distinctUntilChanged()
    val filterLists = preferences.map { parseLists(it[Lists]) }.distinctUntilChanged()

    suspend fun setCustomRules(text: String) {
        store.edit { it[CustomRules] = text }
    }

    suspend fun setDns(value: DnsSettings) {
        store.edit {
            it[Upstream] = value.upstream.name
            it[BlockMode] = value.blockResponse.name
            it[Logging] = value.logging
        }
    }

    suspend fun editLists(change: (List<FilterList>) -> List<FilterList>) {
        store.edit { preferences ->
            val array = JSONArray()
            change(parseLists(preferences[Lists])).forEach { list ->
                array.put(
                    JSONObject()
                        .put("id", list.id)
                        .put("name", list.name)
                        .put("url", list.url)
                        .put("enabled", list.enabled)
                        .put("updatedAt", list.updatedAt)
                        .put("ruleCount", list.ruleCount)
                        .put("error", list.error)
                )
            }
            preferences[Lists] = array.toString()
        }
    }

    private fun parseLists(json: String?): List<FilterList> {
        if (json == null)
            return listOf(
                FilterList(
                    "stevenblack",
                    "StevenBlack hosts",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                    enabled = true,
                )
            )
        return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { index ->
                    array.getJSONObject(index).let { item ->
                        FilterList(
                            item.getString("id"),
                            item.getString("name"),
                            item.getString("url"),
                            item.optBoolean("enabled"),
                            item.optLong("updatedAt"),
                            item.optInt("ruleCount"),
                            item.optString("error").takeIf { it.isNotBlank() && it != "null" },
                        )
                    }
                }
            }
            .getOrDefault(emptyList())
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[ThemeKey] = mode.name }
    }

    private companion object {
        val CustomRules = stringPreferencesKey("custom_rules")
        val Upstream = stringPreferencesKey("dns_upstream")
        val BlockMode = stringPreferencesKey("dns_block_mode")
        val Logging = booleanPreferencesKey("dns_logging")
        val Lists = stringPreferencesKey("filter_lists")
        val ThemeKey = stringPreferencesKey("theme_mode")
    }
}
