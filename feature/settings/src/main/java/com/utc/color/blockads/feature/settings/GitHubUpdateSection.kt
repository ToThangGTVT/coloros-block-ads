package com.utc.color.blockads.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.utc.color.blockads.core.data.GitHubUpdate
import com.utc.color.blockads.core.data.GitHubUpdateRepository
import com.utc.color.blockads.core.designsystem.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun rememberGitHubUpdateContent(): @Composable () -> Unit {
    val context = LocalContext.current
    val repository = remember(context) { GitHubUpdateRepository(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<GitHubUpdate?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    fun install() {
        val apk = downloaded ?: return
        val release = update ?: return
        runCatching {
            repository.validateApk(apk, release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
            .onFailure { message = it.message ?: "Không mở được trình cài đặt" }
    }
    val installPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else
                message =
                    "Cần cho phép BlockAds cài ứng dụng để cập nhật. APK đã tải vẫn được giữ lại."
        }

    return {
        AppSectionTitle("Cập nhật ứng dụng")
        AppPreferenceGroup {
            AppArrowPreference(
                "Kiểm tra cập nhật GitHub",
                if (busy) "Đang xử lý…" else "Đang dùng ${repository.currentVersion}",
            ) {
                if (!busy)
                    scope.launch {
                        busy = true
                        message = "Đang kiểm tra bản phát hành…"
                        try {
                            update = repository.check()
                            downloaded = null
                            message =
                                if (update == null)
                                    "Bạn đang dùng bản mới nhất hoặc GitHub chưa có bản phát hành."
                                else
                                    "Có bản ${update!!.versionName} · ${"%.1f".format(update!!.size / 1048576.0)} MB"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: "Không kiểm tra được cập nhật"
                        } finally {
                            busy = false
                        }
                    }
            }
            if (message != null || update != null)
                AppGroupContent {
                    message?.let { AppHint(it) }
                    val available = update
                    if (available != null) {
                        AppAction(
                            if (downloaded != null) "Cài đặt bản cập nhật"
                            else if (busy) "Đang tải $progress%"
                            else "Tải bản ${available.versionName}",
                            primary = true,
                        ) {
                            if (!busy) {
                                if (downloaded != null) {
                                    if (context.packageManager.canRequestPackageInstalls())
                                        install()
                                    else
                                        runCatching {
                                            installPermission.launch(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                            .onFailure {
                                                message = "Không mở được quyền cài ứng dụng"
                                            }
                                } else
                                    scope.launch {
                                        busy = true
                                        progress = 0
                                        message = "Đang tải APK từ GitHub…"
                                        try {
                                            downloaded =
                                                repository.download(available) { progress = it }
                                            message =
                                                "Đã tải và xác minh APK. Nhấn cài đặt để cập nhật."
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            message = error.message ?: "Tải bản cập nhật thất bại"
                                        } finally {
                                            busy = false
                                        }
                                    }
                            }
                        }
                    }
                }
            AppArrowPreference("Xem trên GitHub", "Lịch sử phiên bản và APK") {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateRepository.ReleasesUrl))
                    )
                }
                    .onFailure { message = "Không mở được GitHub" }
            }
        }
    }
}
