package com.utc.color.blockads.feature.settings

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
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
    val strings = rememberAppStrings()
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
            .onFailure { message = it.userMessage(strings) ?: strings.getString(L10n.string.installer_open_failed) }
    }
    val installPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else
                message =
                    strings.getString(L10n.string.install_permission_required)
        }

    return {
        AppSectionTitle(strings.getString(L10n.string.app_updates))
        AppPreferenceGroup {
            AppArrowPreference(
                strings.getString(L10n.string.check_github_updates),
                if (busy) strings.getString(L10n.string.processing) else strings.getString(L10n.string.current_version, repository.currentVersion),
            ) {
                if (!busy)
                    scope.launch {
                        busy = true
                        message = strings.getString(L10n.string.checking_releases)
                        try {
                            update = repository.check()
                            downloaded = null
                            message =
                                if (update == null)
                                    strings.getString(L10n.string.no_update)
                                else
                                    strings.getString(L10n.string.update_available, update!!.versionName, "%.1f".format(update!!.size / 1048576.0))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.userMessage(strings) ?: strings.getString(L10n.string.update_check_failed)
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
                            if (downloaded != null) strings.getString(L10n.string.install_update)
                            else if (busy) strings.getString(L10n.string.download_progress, progress)
                            else strings.getString(L10n.string.download_version, available.versionName),
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
                                                message = strings.getString(L10n.string.install_permission_open_failed)
                                            }
                                } else
                                    scope.launch {
                                        busy = true
                                        progress = 0
                                        message = strings.getString(L10n.string.downloading_apk)
                                        try {
                                            downloaded =
                                                repository.download(available) { progress = it }
                                            message =
                                                strings.getString(L10n.string.apk_ready)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            message = error.userMessage(strings) ?: strings.getString(L10n.string.update_download_failed)
                                        } finally {
                                            busy = false
                                        }
                                    }
                            }
                        }
                    }
                }
            AppArrowPreference(strings.getString(L10n.string.view_github), strings.getString(L10n.string.release_history)) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateRepository.ReleasesUrl))
                    )
                }
                    .onFailure { message = strings.getString(L10n.string.github_open_failed) }
            }
        }
    }
}
