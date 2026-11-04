package com.utc.color.blockads.core.data

import com.utc.color.blockads.core.common.R as L10n
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.utc.color.blockads.core.common.GitHubReleasePolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GitHubUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
)

/** Public, stable GitHub Releases only. No token is shipped in the APK. */
class GitHubUpdateRepository(context: Context) {
    private val context = context.applicationContext
    private val manager = context.packageManager
    private val signingFlags =
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    private fun installed() = manager.getPackageInfo(context.packageName, signingFlags)

    val currentVersion: String
        get() = installed().versionName.orEmpty()

    suspend fun check(): GitHubUpdate? =
        withContext(Dispatchers.IO) {
            val connection = open("https://api.github.com/repos/$Repository/releases/latest")
            val release =
                try {
                    if (connection.responseCode == 404) return@withContext null
                    require(connection.responseCode == 200) {
                        if (connection.responseCode == 403 || connection.responseCode == 429)
                            context.getString(L10n.string.github_rate_limited)
                        else context.getString(L10n.string.github_release_failed, connection.responseCode)
                    }
                    JSONObject(readSmall(connection))
                } finally {
                    connection.disconnect()
                }
            if (release.optBoolean("draft") || release.optBoolean("prerelease"))
                return@withContext null
            val assets = release.getJSONArray("assets")
            val metadata =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "update.json" }
                    ?: return@withContext null
            val metadataUrl = metadata.getString("browser_download_url")
            requireDownloadUrl(metadataUrl)
            val descriptorConnection = open(metadataUrl)
            val descriptor =
                try {
                    require(descriptorConnection.responseCode == 200) {
                        context.getString(L10n.string.update_info_failed)
                    }
                    JSONObject(readSmall(descriptorConnection))
                } finally {
                    descriptorConnection.disconnect()
                }
            if (descriptor.getLong("versionCode") <= installed().longVersionCode)
                return@withContext null
            val variants = descriptor.getJSONArray("apks")
            val byAbi =
                (0 until variants.length())
                    .map { variants.getJSONObject(it) }
                    .associateBy { it.getString("abi") }
            val variant =
                Build.SUPPORTED_ABIS.firstNotNullOfOrNull { byAbi[it] }
                    ?: error(context.getString(L10n.string.update_arm_only))
            val update =
                GitHubUpdate(
                    descriptor.getLong("versionCode"),
                    descriptor.getString("versionName"),
                    variant.getString("apkUrl"),
                    variant.getString("sha256").lowercase(),
                    variant.getLong("size"),
                )
            require(update.sha256.matches(Regex("[0-9a-f]{64}")) && update.size in 1..MaxApkBytes) {
                context.getString(L10n.string.update_info_invalid)
            }
            requireDownloadUrl(update.apkUrl)
            // Metadata and APK must belong to this same release, not a different tag.
            require(update.apkUrl.substringBeforeLast('/') == metadataUrl.substringBeforeLast('/'))
            require(
                (0 until assets.length()).any {
                    val asset = assets.getJSONObject(it)
                    asset.optString("browser_download_url") == update.apkUrl &&
                        asset.optLong("size") == update.size
                }
            ) {
                context.getString(L10n.string.update_apk_missing)
            }
            update.takeIf { it.versionCode > installed().longVersionCode }
        }

    suspend fun download(update: GitHubUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            requireDownloadUrl(update.apkUrl)
            require(update.size in 1..MaxApkBytes)
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "blockads.apk.part")
            val apk = File(directory, "blockads.apk")
            apk.delete()
            val connection = open(update.apkUrl)
            try {
                require(connection.responseCode == 200) {
                    context.getString(L10n.string.apk_download_failed, connection.responseCode)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var progress = -1
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= update.size) { context.getString(L10n.string.apk_size_invalid) }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val next = (received * 100 / update.size).toInt()
                            if (next != progress) {
                                progress = next
                                onProgress(next)
                            }
                        }
                    }
                }
                require(received == update.size) { context.getString(L10n.string.apk_incomplete) }
                require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) {
                    context.getString(L10n.string.apk_checksum_mismatch)
                }
                check(partial.renameTo(apk)) { context.getString(L10n.string.apk_save_failed) }
                validateApk(apk, update)
                apk
            } catch (error: Exception) {
                apk.delete()
                throw error
            } finally {
                partial.delete()
                connection.disconnect()
            }
        }

    fun validateApk(apk: File, update: GitHubUpdate) {
        val archive =
            manager.getPackageArchiveInfo(apk.absolutePath, signingFlags)
                ?: error(context.getString(L10n.string.apk_invalid))
        val current = installed()
        require(
            archive.packageName == context.packageName &&
                archive.longVersionCode == update.versionCode &&
                archive.longVersionCode > current.longVersionCode
        ) {
            context.getString(L10n.string.apk_wrong_app)
        }
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val currentSigners = current.signingInfo?.apkContentsSigners.orEmpty().toSet()
        require(signers.isNotEmpty() && signers == currentSigners) {
            context.getString(L10n.string.apk_signature_mismatch)
        }
    }

    private fun requireDownloadUrl(value: String) {
        GitHubReleasePolicy.validateDownloadUrl(value)
    }

    private fun open(value: String): HttpURLConnection {
        var url = URL(value)
        repeat(6) {
            require(
                url.protocol == "https" &&
                    url.userInfo == null &&
                    (url.host in setOf("github.com", "api.github.com") ||
                        url.host.endsWith(".githubusercontent.com"))
            ) {
                context.getString(L10n.string.github_redirect_invalid)
            }
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "BlockAds-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                url =
                    URL(
                        url,
                        connection.getHeaderField("Location")
                            ?: error(context.getString(L10n.string.redirect_missing)),
                    )
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error(context.getString(L10n.string.github_redirect_limit))
    }

    private fun readSmall(connection: HttpURLConnection): String =
        connection.inputStream.use {
            val bytes = it.readNBytes(1024 * 1024 + 1)
            require(bytes.size <= 1024 * 1024) { context.getString(L10n.string.update_info_too_large) }
            bytes.toString(Charsets.UTF_8)
        }

    companion object {
        const val Repository = GitHubReleasePolicy.Repository
        const val ReleasesUrl = GitHubReleasePolicy.ReleasesUrl
        private const val MaxApkBytes = 250L * 1024 * 1024
    }
}
