package com.utc.color.blockads.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.common.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    val https =
        preferences
            .map { prefs ->
                runCatching {
                        val json = JSONObject(prefs[Https] ?: "{}")
                        fun names(key: String, default: List<String>): List<String> =
                            json.optJSONArray(key)?.let { array ->
                                (0 until array.length()).map { array.getString(it) }
                            } ?: default
                        HttpsSettings(
                            json.optBoolean("enabled", false),
                            names("apps", HttpsSettings().apps),
                            names("domains", emptyList()),
                            names("excluded", emptyList()),
                        )
                    }
                    .getOrDefault(HttpsSettings())
            }
            .distinctUntilChanged()

    suspend fun setHttps(value: HttpsSettings) {
        require(
            value.apps.size <= 100 && value.domains.size <= 100 && value.excluded.size <= 1000
        ) {
            "Danh sách HTTPS quá dài"
        }
        require(value.apps.all { it.matches(Regex("[A-Za-z0-9_.]+")) }) {
            "Package ứng dụng không hợp lệ"
        }
        val domains = value.domains.map(::normalizeHttpsDomain).distinct()
        val excluded = value.excluded.map(::normalizeHttpsDomain).distinct()
        store.edit {
            it[Https] =
                JSONObject()
                    .put("enabled", value.enabled)
                    .put("apps", JSONArray(value.apps.distinct()))
                    .put("domains", JSONArray(domains))
                    .put("excluded", JSONArray(excluded))
                    .toString()
        }
    }

    val autoStart = preferences.map { it[AutoStart] ?: false }.distinctUntilChanged()
    val liveUpdates = preferences.map { it[LiveUpdates] ?: true }.distinctUntilChanged()
    val ipv6 = preferences.map { it[Ipv6] ?: true }.distinctUntilChanged()
    val logRetentionDays =
        preferences.map { (it[Retention] ?: 7).coerceIn(1, 30) }.distinctUntilChanged()

    suspend fun setAutoStart(enabled: Boolean) {
        store.edit { it[AutoStart] = enabled }
    }

    suspend fun setLiveUpdates(enabled: Boolean) {
        store.edit { it[LiveUpdates] = enabled }
    }

    suspend fun setIpv6(enabled: Boolean) {
        store.edit { it[Ipv6] = enabled }
    }

    suspend fun setLogRetentionDays(days: Int) {
        require(days in listOf(1, 7, 30))
        store.edit { it[Retention] = days }
    }

    val blockQuic = preferences.map { it[BlockQuic] ?: true }.distinctUntilChanged()
    val appPolicies = preferences.map { parsePolicies(it[AppPolicies]) }.distinctUntilChanged()
    val blockedApps = preferences.map { parseBlockedApps(it[BlockedApps]) }.distinctUntilChanged()

    private fun parseBlockedApps(json: String?): Set<String> {
        val array = JSONArray(json ?: "[]")
        require(array.length() <= 2000) { "Tối đa 2000 ứng dụng trong tường lửa" }
        return (0 until array.length())
            .map { array.getString(it) }
            .toSet()
            .also { packages ->
                require(packages.all { it.matches(Regex("[A-Za-z0-9_.]+")) }) {
                    "Package ứng dụng trong tường lửa không hợp lệ"
                }
            }
    }

    suspend fun setNetworkAllowed(packages: List<String>, allowed: Boolean) {
        require(packages.all { it.matches(Regex("[A-Za-z0-9_.]+")) })
        store.edit { prefs ->
            val blocked = parseBlockedApps(prefs[BlockedApps]).toMutableSet()
            if (allowed) blocked.removeAll(packages.toSet()) else blocked.addAll(packages)
            require(blocked.size <= 2000) { "Tối đa 2000 ứng dụng trong tường lửa" }
            prefs[BlockedApps] = JSONArray(blocked.sorted()).toString()
        }
    }

    private fun parsePolicies(json: String?): Map<String, AppFilterMode> = runCatching {
        val value = JSONObject(json ?: "{}")
        value
            .keys()
            .asSequence()
            .mapNotNull { key ->
                AppFilterMode.entries
                    .firstOrNull { it.name == value.optString(key) }
                    ?.let { key to it }
            }
            .toMap()
    }
        .getOrDefault(emptyMap())

    suspend fun setBlockQuic(enabled: Boolean) {
        store.edit { it[BlockQuic] = enabled }
    }

    suspend fun setAppMode(packages: List<String>, mode: AppFilterMode) {
        store.edit { prefs ->
            val policies = parsePolicies(prefs[AppPolicies]).toMutableMap()
            packages.forEach {
                if (mode == AppFilterMode.Filter) policies.remove(it) else policies[it] = mode
            }
            prefs[AppPolicies] = JSONObject(policies.mapValues { it.value.name }).toString()
        }
    }

    val dns =
        preferences
            .map { value ->
                DnsSettings(
                    DnsUpstream.entries.firstOrNull { it.name == value[Upstream] }
                        ?: DnsUpstream.System,
                    BlockResponse.entries.firstOrNull { it.name == value[BlockMode] }
                        ?: BlockResponse.Zero,
                    value[Logging] ?: true,
                    value[CustomDnsUrl] ?: "https://cloudflare-dns.com/dns-query",
                    value[CustomDnsIp] ?: "1.1.1.1",
                )
            }
            .distinctUntilChanged()
    val filterLists = preferences.map { parseLists(it[Lists]) }.distinctUntilChanged()

    suspend fun setCustomRules(text: String) {
        store.edit { it[CustomRules] = text }
    }

    suspend fun setDns(value: DnsSettings) {
        if (value.upstream == DnsUpstream.Custom)
            parseDnsEndpoint(value.customUrl, value.customBootstrap)
        store.edit {
            it[Upstream] = value.upstream.name
            it[BlockMode] = value.blockResponse.name
            it[Logging] = value.logging
            it[CustomDnsUrl] = value.customUrl
            it[CustomDnsIp] = value.customBootstrap
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
                        .put("etag", list.etag)
                        .put("lastModified", list.lastModified)
                )
            }
            preferences[Lists] = array.toString()
        }
    }

    /**
     * Removes a list; presets are re-added by [parseLists], so one is turned off instead. Returns
     * whether the list was removed.
     */
    suspend fun removeList(id: String): Boolean {
        val preset = presets.any { it.id == id }
        editLists { all ->
            if (preset) all.map { if (it.id == id) it.copy(enabled = false) else it }
            else all.filterNot { it.id == id }
        }
        return !preset
    }

    private fun parseLists(json: String?): List<FilterList> {
        if (json == null) return presets
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
                            item.optString("etag").takeIf { it.isNotBlank() && it != "null" },
                            item.optString("lastModified").takeIf {
                                it.isNotBlank() && it != "null"
                            },
                        )
                    }
                }
            }
            .getOrDefault(emptyList())
            .let { stored ->
                stored + presets.filter { preset -> stored.none { it.id == preset.id } }
            }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[ThemeKey] = mode.name }
    }

    suspend fun exportConfiguration(): String {
        val prefs = preferences.first()
        val json = JSONObject().put("schema", 1)
        stringKeys.forEach { key -> prefs[key]?.let { json.put(key.name, it) } }
        boolKeys.forEach { key -> prefs[key]?.let { json.put(key.name, it) } }
        json.put(Retention.name, prefs[Retention] ?: 7)
        return json.toString(2)
    }

    /**
     * All fields are validated before one atomic edit. FilterRepository validates custom syntax.
     */
    suspend fun importConfiguration(text: String) {
        require(text.length <= 2 * 1024 * 1024) { "Cấu hình vượt quá 2 MB" }
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "Phiên bản cấu hình không hỗ trợ" }
        require((json.optString(CustomRules.name)).length <= 1024 * 1024) {
            "Custom rules vượt quá 1 MB"
        }
        val dns =
            DnsSettings(
                DnsUpstream.valueOf(json.optString(Upstream.name, "System")),
                BlockResponse.valueOf(json.optString(BlockMode.name, "Zero")),
                json.optBoolean(Logging.name, true),
                json.optString(CustomDnsUrl.name, "https://cloudflare-dns.com/dns-query"),
                json.optString(CustomDnsIp.name, "1.1.1.1"),
            )
        if (dns.upstream == DnsUpstream.Custom) parseDnsEndpoint(dns.customUrl, dns.customBootstrap)
        ThemeMode.valueOf(json.optString(ThemeKey.name, "System"))
        val retention = json.optInt(Retention.name, 7)
        require(retention in listOf(1, 7, 30)) { "Thời gian giữ log không hợp lệ" }
        if (json.has(Lists.name)) org.json.JSONArray(json.getString(Lists.name))
        if (json.has(AppPolicies.name)) org.json.JSONObject(json.getString(AppPolicies.name))
        parseBlockedApps(if (json.has(BlockedApps.name)) json.getString(BlockedApps.name) else null)
        val lists = parseLists(json.optString(Lists.name).takeIf { it.isNotBlank() })
        require(lists.size <= 30) { "Tối đa 30 danh sách" }
        lists.forEach { list ->
            require(list.id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "ID danh sách không hợp lệ" }
            val url = java.net.URI(list.url)
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null) {
                "Filter cần URL HTTPS"
            }
        }
        val policies = parsePolicies(json.optString(AppPolicies.name))
        require(
            policies.size <= 2000 && policies.keys.all { it.matches(Regex("[A-Za-z0-9_.]+")) }
        ) {
            "Chính sách ứng dụng không hợp lệ"
        }
        // Imported download metadata must not reference cached files for a different URL.
        val cleanLists = JSONArray()
        lists.forEach { list ->
            val id =
                if (presets.any { it.id == list.id && it.url == list.url }) list.id
                else
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(list.url.toByteArray())
                        .joinToString("") { "%02x".format(it) }
            cleanLists.put(
                JSONObject()
                    .put("id", id)
                    .put("name", list.name.take(200))
                    .put("url", list.url)
                    .put("enabled", list.enabled)
            )
        }
        store.edit { prefs ->
            stringKeys.forEach { key ->
                if (json.has(key.name)) prefs[key] = json.getString(key.name) else prefs.remove(key)
            }
            boolKeys.forEach { key ->
                if (json.has(key.name)) prefs[key] = json.getBoolean(key.name)
                else prefs.remove(key)
            }
            prefs[Lists] = cleanLists.toString()
            prefs[Retention] = retention
        }
    }

    private companion object {
        val Https = stringPreferencesKey("https_local_settings")
        val presets =
            listOf(
                // AdGuard Home's default blocklist, same URL as in its registry.
                FilterList(
                    "adguard-dns",
                    "AdGuard DNS filter",
                    "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt",
                    enabled = true,
                ),
                FilterList(
                    "stevenblack",
                    "StevenBlack hosts",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                ),
                FilterList(
                    "adguard-base",
                    "AdGuard Base · Network subset",
                    "https://filters.adtidy.org/extension/ublock/filters/2.txt",
                ),
                FilterList(
                    "easylist",
                    "EasyList · Network subset",
                    "https://easylist.to/easylist/easylist.txt",
                ),
                FilterList(
                    "easyprivacy",
                    "EasyPrivacy · Network subset",
                    "https://easylist.to/easylist/easyprivacy.txt",
                ),
                FilterList(
                    "abpvn",
                    "ABPVN · Tiếng Việt",
                    "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn.txt",
                ),
            )
        val AutoStart = booleanPreferencesKey("auto_start")
        val LiveUpdates = booleanPreferencesKey("live_updates")
        val Ipv6 = booleanPreferencesKey("ipv6")
        val Retention = intPreferencesKey("retention_days")
        val CustomDnsUrl = stringPreferencesKey("custom_dns_url")
        val CustomDnsIp = stringPreferencesKey("custom_dns_ip")
        val AppPolicies = stringPreferencesKey("app_policies")
        val BlockedApps = stringPreferencesKey("blocked_apps")
        val BlockQuic = booleanPreferencesKey("block_quic")
        val CustomRules = stringPreferencesKey("custom_rules")
        val Upstream = stringPreferencesKey("dns_upstream")
        val BlockMode = stringPreferencesKey("dns_block_mode")
        val Logging = booleanPreferencesKey("dns_logging")
        val Lists = stringPreferencesKey("filter_lists")
        val ThemeKey = stringPreferencesKey("theme_mode")
        val stringKeys =
            listOf(
                CustomRules,
                Upstream,
                BlockMode,
                Lists,
                ThemeKey,
                AppPolicies,
                BlockedApps,
                CustomDnsUrl,
                CustomDnsIp,
            )
        val boolKeys = listOf(AutoStart, Ipv6, Logging, BlockQuic, LiveUpdates)
    }
}
