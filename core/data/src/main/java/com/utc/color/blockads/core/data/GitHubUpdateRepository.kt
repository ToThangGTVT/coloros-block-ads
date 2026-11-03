package com.utc.color.blockads.core.data

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
                            "GitHub đang giới hạn lượt kiểm tra. Hãy thử lại sau."
                        else "Không lấy được bản phát hành từ GitHub (${connection.responseCode})"
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
                        "Không tải được thông tin cập nhật"
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
                    ?: error("Bản cập nhật chỉ hỗ trợ ARMv7 và ARM64")
            val update =
                GitHubUpdate(
                    descriptor.getLong("versionCode"),
                    descriptor.getString("versionName"),
                    variant.getString("apkUrl"),
                    variant.getString("sha256").lowercase(),
                    variant.getLong("size"),
                )
            require(update.sha256.matches(Regex("[0-9a-f]{64}")) && update.size in 1..MaxApkBytes) {
                "Thông tin cập nhật không hợp lệ"
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
                "Không tìm thấy APK tương ứng trong bản phát hành"
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
                    "Không tải được APK (${connection.responseCode})"
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
                            require(received <= update.size) { "Kích thước APK không hợp lệ" }
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
                require(received == update.size) { "APK tải chưa hoàn tất" }
                require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) {
                    "APK không khớp mã SHA-256. Hãy tải lại."
                }
                check(partial.renameTo(apk)) { "Không lưu được APK" }
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
                ?: error("APK không hợp lệ")
        val current = installed()
        require(
            archive.packageName == context.packageName &&
                archive.longVersionCode == update.versionCode &&
                archive.longVersionCode > current.longVersionCode
        ) {
            "APK không phải bản cập nhật của BlockAds"
        }
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val currentSigners = current.signingInfo?.apkContentsSigners.orEmpty().toSet()
        require(signers.isNotEmpty() && signers == currentSigners) {
            "Khác chữ ký với bản đang cài. Hãy dùng bản release từ GitHub để nhận cập nhật tiếp theo."
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
                "GitHub chuyển hướng tới địa chỉ không hợp lệ"
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
                            ?: error("Thiếu địa chỉ chuyển hướng"),
                    )
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("GitHub chuyển hướng quá nhiều lần")
    }

    private fun readSmall(connection: HttpURLConnection): String =
        connection.inputStream.use {
            val bytes = it.readNBytes(1024 * 1024 + 1)
            require(bytes.size <= 1024 * 1024) { "Thông tin cập nhật quá lớn" }
            bytes.toString(Charsets.UTF_8)
        }

    companion object {
        const val Repository = GitHubReleasePolicy.Repository
        const val ReleasesUrl = GitHubReleasePolicy.ReleasesUrl
        private const val MaxApkBytes = 250L * 1024 * 1024
    }
}
