package com.utc.color.blockads.core.filter

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

class FilterRepository(
    context: Context,
    private val settings: SettingsRepository,
    private val engine: EngineBridge,
) {
    private val directory = File(context.filesDir, "filters").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compileLock = Mutex()
    private val downloadLock = Mutex()
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
                                    error = error.message ?: "Không nạp được bộ lọc",
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
            if (lists.first().any { it.id == "stevenblack" && it.enabled && it.updatedAt == 0L })
                download("stevenblack")
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

    private fun file(id: String) = File(directory, "$id.txt")

    private suspend fun reload() = compileLock.withLock {
        _status.value = _status.value.copy(loading = true, error = null)
        val active = lists.first().filter { it.enabled }
        require(active.sumOf { file(it.id).length() } <= 32L * 1024 * 1024) {
            "Tổng filter bật vượt quá 32 MB"
        }
        val system = buildString {
            active.forEach { list ->
                val path = file(list.id)
                if (path.exists()) {
                    append(path.readText())
                    append('\n')
                }
            }
        }
        val custom = settings.customRules.first()
        val signature =
            MessageDigest.getInstance("SHA-256")
                .digest((system + "\u0000" + custom).toByteArray())
                .joinToString("") { "%02x".format(it) }
        if (signature == compiledSignature) {
            _status.value = _status.value.copy(loading = false)
            return@withLock
        }
        val result = JSONObject(engine.replaceRules(system, custom))
        check(result.optBoolean("applied")) {
            "Custom rules không hợp lệ: ${result.optJSONArray("errors")}"
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

    suspend fun addUrl(url: String) {
        val uri = URI(url.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Cần URL HTTPS hợp lệ"
        }
        val normalized = uri.toASCIIString()
        require(normalized.length <= 4096) { "URL quá dài" }
        val existing = lists.first()
        require(existing.any { it.url == normalized } || existing.size < 30) {
            "Tối đa 30 danh sách"
        }
        val id =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        settings.editLists { all ->
            if (all.any { it.url == normalized }) all
            else all + FilterList(id, uri.host, normalized, enabled = true)
        }
        download(id)
    }

    suspend fun download(id: String) =
        withContext(Dispatchers.IO) {
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
                            connection.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(16384)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    check(output.size() + read <= 20 * 1024 * 1024) {
                                        "List vượt quá 20 MB"
                                    }
                                    output.write(buffer, 0, read)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                        } finally {
                            connection.disconnect()
                        }
                    val compiled = JSONObject(engine.replaceRules(content, "", apply = false))
                    check(compiled.getInt("loaded") > 0) { "List không có rule DNS được hỗ trợ" }
                    val temporary = File(directory, "$id.tmp")
                    temporary.writeText(content)
                    check(temporary.renameTo(file(id))) { "Không lưu được filter" }
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
                                if (it.id == id) it.copy(error = error.message ?: "Tải thất bại")
                                else it
                            }
                        }
                        _status.value =
                            _status.value.copy(
                                loading = false,
                                error = error.message ?: "Tải bộ lọc thất bại",
                            )
                    }
                reload()
                result.isSuccess
            }
        }

    suspend fun importConfiguration(text: String) {
        val custom = JSONObject(text).optString("custom_rules", "")
        val validation =
            JSONObject(engine.replaceRules("", custom, apply = false)).getJSONArray("errors")
        require(validation.length() == 0) { "Custom rules không hợp lệ: $validation" }
        settings.importConfiguration(text)
        reload()
        scope.launch {
            lists.first().filter { it.enabled && !file(it.id).exists() }.forEach { download(it.id) }
        }
    }

    suspend fun saveCustom(text: String) {
        require(text.length <= 1024 * 1024) { "Custom rules vượt quá 1 MB" }
        val validation =
            JSONObject(engine.replaceRules("", text, apply = false)).getJSONArray("errors")
        require(validation.length() == 0) {
            (0 until validation.length()).joinToString("\n") { index ->
                validation.getJSONObject(index).let {
                    "Dòng ${it.getInt("line")}: ${it.getString("message")}"
                }
            }
        }
        settings.setCustomRules(text)
        reload()
    }
}
