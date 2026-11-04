package com.utc.color.blockads.core.filter

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import android.content.Context
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Where a catalog comes from; its lists are added as DNS or browser lists. */
enum class CatalogSource(val url: String, val cache: String, val browser: Boolean) {
    /** AdGuard Home's blocklist registry. */
    Dns("https://adguardteam.github.io/HostlistsRegistry/assets/filters.json", "catalog.json", false),
    /** The AdGuard browser extension's filters, in the uBlock Origin syntax the engine reads. */
    Browser(
        "https://filters.adtidy.org/extension/ublock/filters.json",
        "catalog-browser.json",
        true,
    ),
}

/** One list of a catalog; [group] is the catalog's groupId. */
data class CatalogEntry(
    val group: Int,
    val name: String,
    val url: String,
    val recommended: Boolean = false,
    val languages: List<String> = emptyList(),
)

class FilterRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val engine: EngineBridge,
) {
    private val directory = File(context.filesDir, "filters").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compileLock = Mutex()
    private val downloadLock = Mutex()
    private val resourceLock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val dnsReady = CompletableDeferred<Unit>()
    private var compiledSignature: String? = null
    val lists = settings.filterLists
    private val _status = MutableStateFlow(FilterStatus())
    val status = _status.asStateFlow()

    init {
        scope.launch {
            combine(settings.filterLists, settings.customRules) { lists, custom ->
                    lists.map { Triple(it.id, it.enabled, it.updatedAt) } to custom
                }
                .distinctUntilChanged()
                .collect {
                    runCatching { reload() }
                        .onFailure { error ->
                            _status.value =
                                _status.value.copy(
                                    loading = false,
                                    error = error.userMessage(context.resources) ?: context.getString(L10n.string.filters_load_failed),
                                )
                        }
                    if (!ready.isCompleted) ready.complete(Unit)
                }
        }
        scope.launch {
            settings.dns.collect { value ->
                applyDns(value)
                if (!dnsReady.isCompleted) dnsReady.complete(Unit)
            }
        }
        scope.launch {
            // Default lists, and presets added by an update, download on first start.
            lists.first().filter { it.enabled && it.updatedAt == 0L }.forEach { download(it.id) }
        }
        scope.launch {
            resourceLock.withLock { runCatching { installResources() } }
            if (ResourceUrls.keys.any { !resource(it).exists() }) updateResources()
        }
    }

    // Scriptlet (`##+js`) and `$redirect` resources for HTTPS filtering: AdGuard Scriptlets
    // (GPL-3.0), downloaded at runtime like the filter lists and never bundled in the APK.
    private fun resource(name: String) = File(directory, "resources-$name.json")

    private fun resourceTag(name: String) = File(directory, "resources-$name.etag")

    private fun installResources(override: Map<String, String> = emptyMap()) {
        val text = { name: String ->
            override[name] ?: resource(name).takeIf { it.exists() }?.readText().orEmpty()
        }
        val result = JSONObject(engine.setResources(text("scriptlets"), text("redirects")))
        check(!result.has("error")) { result.getString("error") }
    }

    private data class Fetched(val content: String, val etag: String?)

    /** Null when the copy on disk is still current (HTTP 304). */
    private fun fetchResource(name: String, url: String): Fetched? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        if (resource(name).exists() && resourceTag(name).exists()) {
            connection.setRequestProperty("If-None-Match", resourceTag(name).readText())
        }
        return try {
            if (connection.responseCode == 304 && resource(name).exists()) return null
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            Fetched(
                connection.inputStream.use {
                    readLimited(it, 4 * 1024 * 1024, context.getString(L10n.string.resources_too_large))
                },
                connection.getHeaderField("ETag"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads changed resources, installs them, and only then replaces the copies on disk. */
    suspend fun updateResources(): Boolean =
        detached {
            resourceLock.withLock {
                runCatching {
                        val fetched =
                            ResourceUrls.mapNotNull { (name, url) ->
                                fetchResource(name, url)?.let { name to it }
                            }
                        if (fetched.isEmpty()) return@runCatching
                        val contents = fetched.associate { it.first to it.second.content }
                        runCatching { installResources(contents) }
                            .onFailure {
                                // Keep the copies on disk active; the download is discarded.
                                runCatching { installResources() }
                                throw it
                            }
                        fetched.forEach { (name, update) ->
                            val temporary = File(directory, "resources-$name.tmp")
                            temporary.writeText(update.content)
                            check(temporary.renameTo(resource(name))) {
                                context.getString(L10n.string.resources_save_failed)
                            }
                            update.etag?.let { resourceTag(name).writeText(it) }
                                ?: resourceTag(name).delete()
                        }
                    }
                    .isSuccess
            }
        }

    private fun applyDns(value: DnsSettings) {
        val preset = value.upstream
        val custom =
            if (preset == DnsUpstream.Custom)
                parseDnsEndpoint(value.customUrl, value.customBootstrap)
            else null
        val config =
            JSONObject()
                .put("protocol", custom?.protocol ?: preset.protocol)
                .put("ip", custom?.bootstrap ?: preset.ip)
                .put("serverName", custom?.host ?: preset.serverName)
                .put("path", custom?.path ?: "/dns-query")
                .put("port", custom?.port ?: 0)
                .put("logging", value.logging)
                .put(
                    "blockMode",
                    if (value.blockResponse == BlockResponse.Zero) "zero" else "nxdomain",
                )
        check(engine.configureDns(config.toString())) { "DNS configuration rejected" }
    }

    suspend fun refreshDns() {
        applyDns(settings.dns.first())
    }

    suspend fun awaitReady() {
        ready.await()
        dnsReady.await()
    }

    /**
     * Downloads outlive the screen that started them: leaving a screen cancels its scope, and a
     * multi-megabyte browser list cancelled midway left no file and no error.
     */
    private suspend fun <T> detached(block: suspend CoroutineScope.() -> T): T =
        scope.async(block = block).await()

    private fun file(id: String) = File(directory, "$id.txt")

    private suspend fun reload() = compileLock.withLock {
        _status.value = _status.value.copy(loading = true, error = null)
        val active = lists.first().filter { it.enabled }
        require(active.sumOf { file(it.id).length() } <= 32L * 1024 * 1024) {
            context.getString(L10n.string.enabled_filters_too_large)
        }
        fun join(browser: Boolean) = buildString {
            active.filter { it.browser == browser }.forEach { list ->
                val path = file(list.id)
                if (path.exists()) {
                    append(path.readText())
                    append('\n')
                }
            }
        }
        val dns = join(browser = false)
        val browser = join(browser = true)
        val custom = settings.customRules.first()
        val signature =
            MessageDigest.getInstance("SHA-256")
                .digest(listOf(dns, browser, custom).joinToString("\u0000").toByteArray())
                .joinToString("") { "%02x".format(it) }
        if (signature == compiledSignature) {
            _status.value = _status.value.copy(loading = false)
            return@withLock
        }
        val result = JSONObject(engine.replaceRules(dns, browser, custom))
        check(result.optBoolean("applied")) {
            context.getString(L10n.string.custom_rules_compile_invalid, result.optJSONArray("errors"))
        }
        compiledSignature = signature
        _status.value =
            FilterStatus(
                loading = false,
                loaded = result.getInt("loaded"),
                ignored = result.getInt("ignored"),
            )
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        settings.editLists {
            it.map { list -> if (list.id == id) list.copy(enabled = enabled) else list }
        }
        if (enabled && !file(id).exists()) download(id)
    }

    /**
     * Fetched at runtime and cached for 4 days (the registry's expiry), never bundled: AdGuard
     * Home's registry is GPL-3.0 and each list keeps its own license.
     */
    suspend fun catalog(source: CatalogSource): List<CatalogEntry> =
        withContext(Dispatchers.IO) {
            val cache = File(directory, source.cache)
            val fresh = System.currentTimeMillis() - cache.lastModified() < 4 * 24 * 3600 * 1000L
            val text =
                if (cache.exists() && fresh) cache.readText()
                else
                    runCatching { fetchCatalog(source.url) }
                        .onSuccess { cache.writeText(it) }
                        .getOrElse { if (cache.exists()) cache.readText() else throw it }
            val json = JSONObject(text)
            val tags = json.optJSONArray("tags")
            val recommended =
                (0 until (tags?.length() ?: 0))
                    .map { tags!!.getJSONObject(it) }
                    .firstOrNull { it.optString("keyword") == "recommended" }
                    ?.optInt("tagId")
            val filters = json.getJSONArray("filters")
            (0 until filters.length())
                .map { filters.getJSONObject(it) }
                .filter { !it.optBoolean("deprecated") }
                .sortedWith(
                    compareBy({ it.optInt("groupId") }, { it.optInt("displayNumber") })
                )
                .mapNotNull { item ->
                    val url =
                        (if (source.browser) Mirrored[item.optInt("filterId")] else null)
                            ?: item.optString("downloadUrl")
                    if (!url.startsWith("https://")) return@mapNotNull null
                    val tagIds = item.optJSONArray("tags")
                    val languages = item.optJSONArray("languages")
                    CatalogEntry(
                        item.optInt("groupId"),
                        item.getString("name").take(200),
                        url,
                        recommended != null &&
                            (0 until (tagIds?.length() ?: 0)).any {
                                tagIds!!.optInt(it) == recommended
                            },
                        (0 until (languages?.length() ?: 0)).map { languages!!.getString(it) },
                    )
                }
        }

    private fun fetchCatalog(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        return try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { readLimited(it, 2 * 1024 * 1024, context.getString(L10n.string.list_too_large)) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int, message: String): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(output.size() + read <= limit) { message }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    suspend fun remove(id: String) =
        downloadLock.withLock { if (settings.removeList(id)) file(id).delete() }

    suspend fun addUrl(url: String, name: String? = null, browser: Boolean = false) {
        val uri = URI(url.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            context.getString(L10n.string.https_url_invalid)
        }
        val normalized = uri.toASCIIString()
        require(normalized.length <= 4096) { context.getString(L10n.string.url_too_long) }
        val existing = lists.first()
        existing.firstOrNull { it.url == normalized && it.browser != browser }?.let {
            throw IllegalArgumentException(
                if (it.browser) context.getString(L10n.string.browser_filter_exists) else context.getString(L10n.string.dns_filter_exists)
            )
        }
        require(existing.any { it.url == normalized } || existing.size < 30) {
            context.getString(L10n.string.filter_list_limit)
        }
        val id =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        settings.editLists { all ->
            if (all.any { it.url == normalized }) all
            else
                all +
                    FilterList(id, name ?: uri.host, normalized, enabled = true, browser = browser)
        }
        download(id)
    }

    /** Every enabled list, then the scriptlet resources. */
    suspend fun updateAll() = detached {
        lists.first().filter { it.enabled }.forEach { download(it.id) }
        updateResources()
    }

    /** Returns whether the list was downloaded (or is unchanged) and applied. */
    suspend fun download(id: String): Boolean =
        detached {
            downloadLock.withLock {
                val list = lists.first().first { it.id == id }
                _status.value = _status.value.copy(loading = true, error = null)
                val result = runCatching {
                    val connection = URI(list.url).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.instanceFollowRedirects = false
                    if (file(id).exists()) {
                        list.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                        list.lastModified?.let {
                            connection.setRequestProperty("If-Modified-Since", it)
                        }
                    }
                    var etag: String? = null
                    var modified: String? = null
                    val content =
                        try {
                            if (connection.responseCode == 304 && file(id).exists()) {
                                settings.editLists { all ->
                                    all.map {
                                        if (it.id == id)
                                            it.copy(
                                                updatedAt = System.currentTimeMillis(),
                                                error = null,
                                            )
                                        else it
                                    }
                                }
                                return@runCatching
                            }
                            check(connection.responseCode == 200) {
                                "HTTP ${connection.responseCode}"
                            }
                            etag = connection.getHeaderField("ETag")
                            modified = connection.getHeaderField("Last-Modified")
                            connection.inputStream.use {
                                readLimited(it, 20 * 1024 * 1024, context.getString(L10n.string.filter_download_too_large))
                            }
                        } finally {
                            connection.disconnect()
                        }
                    val compiled =
                        JSONObject(
                            if (list.browser) engine.replaceRules("", content, "", apply = false)
                            else engine.replaceRules(content, "", "", apply = false)
                        )
                    check(compiled.getInt("loaded") > 0) { context.getString(L10n.string.filter_rules_unsupported) }
                    val temporary = File(directory, "$id.tmp")
                    temporary.writeText(content)
                    check(temporary.renameTo(file(id))) { context.getString(L10n.string.filter_save_failed) }
                    settings.editLists { all ->
                        all.map {
                            if (it.id == id)
                                it.copy(
                                    updatedAt = System.currentTimeMillis(),
                                    ruleCount = compiled.getInt("loaded"),
                                    error = null,
                                    etag = etag,
                                    lastModified = modified,
                                )
                            else it
                        }
                    }
                }
                    .onFailure { error ->
                        settings.editLists { all ->
                            all.map {
                                if (it.id == id) it.copy(error = error.userMessage(context.resources) ?: context.getString(L10n.string.download_failed))
                                else it
                            }
                        }
                        _status.value =
                            _status.value.copy(
                                loading = false,
                                error = error.userMessage(context.resources) ?: context.getString(L10n.string.filter_download_failed),
                            )
                    }
                reload()
                result.isSuccess
            }
        }

    suspend fun importConfiguration(text: String) {
        val custom = JSONObject(text).optString("custom_rules", "")
        val validation =
            JSONObject(engine.replaceRules("", "", custom, apply = false)).getJSONArray("errors")
        require(validation.length() == 0) { context.getString(L10n.string.custom_rules_invalid, validation) }
        settings.importConfiguration(text)
        reload()
        scope.launch {
            lists.first().filter { it.enabled && !file(it.id).exists() }.forEach { download(it.id) }
        }
    }

    suspend fun saveCustom(text: String) {
        require(text.length <= 1024 * 1024) { context.getString(L10n.string.custom_rules_too_large) }
        val validation =
            JSONObject(engine.replaceRules("", "", text, apply = false)).getJSONArray("errors")
        require(validation.length() == 0) {
            (0 until validation.length()).joinToString("\n") { index ->
                validation.getJSONObject(index).let {
                    context.getString(L10n.string.custom_rule_line_error, it.getInt("line"), it.getString("message"))
                }
            }
        }
        settings.setCustomRules(text)
        reload()
    }

    private companion object {
        /**
         * Browser catalog entries that are a preset under another URL: toggling one switches the
         * preset instead of adding a second copy.
         */
        val Mirrored =
            mapOf(
                101 to "https://easylist.to/easylist/easylist.txt",
                118 to "https://easylist.to/easylist/easyprivacy.txt",
                214 to "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn.txt",
            )
        // Major version pinned: same JSON formats, fixes arrive without an app update.
        val ResourceUrls =
            mapOf(
                "scriptlets" to
                    "https://cdn.jsdelivr.net/npm/@adguard/scriptlets@2/dist/scriptlets.corelibs.json",
                "redirects" to
                    "https://cdn.jsdelivr.net/npm/@adguard/scriptlets@2/dist/redirects.json",
            )
    }
}
