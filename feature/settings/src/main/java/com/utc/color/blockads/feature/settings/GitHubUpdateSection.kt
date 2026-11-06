package com.utc.color.blockads.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.GitHubUpdate
import com.utc.color.blockads.core.data.GitHubUpdateRepository
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.feature.settings.databinding.ScreenSettingsBinding
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.utc.color.blockads.core.common.R as Strings

internal class GitHubUpdateSection(
    private val context: Context,
    private val views: ScreenSettingsBinding,
    private val scope: CoroutineScope,
) {
    private val repository = GitHubUpdateRepository(context)
    val version get() = repository.currentVersion
    private var update: GitHubUpdate? = null
    private var downloaded: File? = null
    private var busy = false
    private var progress = 0

    fun bind(launchPermission: (Intent) -> Unit) {
        views.updateAction.setOnClickListener {
            val available = update ?: return@setOnClickListener
            if (downloaded == null) {
                download(available)
            } else if (context.packageManager.canRequestPackageInstalls()) {
                install()
            } else {
                runCatching {
                    launchPermission(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }.onFailure { message(Strings.string.install_permission_open_failed) }
            }
        }
        views.viewGithub.root.bind(context.getString(Strings.string.view_github), context.getString(Strings.string.release_history), trailingIcon = PreferenceIcon.Arrow) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateRepository.ReleasesUrl)))
            }.onFailure { message(Strings.string.github_open_failed) }
        }
        render()
    }

    fun onPermissionResult() {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else message(Strings.string.install_permission_required)
    }

    private fun install() {
        val apk = downloaded ?: return
        val release = update ?: return
        runCatching {
            repository.validateApk(apk, release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { showError(it, Strings.string.installer_open_failed) }
    }

    private fun check() = run(Strings.string.checking_releases, Strings.string.update_check_failed) {
        val available = repository.check()
        update = available
        downloaded = null
        if (available == null) message(Strings.string.no_update)
        else message(Strings.string.update_available, available.versionName, "%.1f".format(available.size / 1048576.0))
    }

    private fun download(available: GitHubUpdate) = run(Strings.string.downloading_apk, Strings.string.update_download_failed) {
        progress = 0
        downloaded = repository.download(available) { value ->
            scope.launch { progress = value; render() }
        }
        message(Strings.string.apk_ready)
    }

    private fun run(start: Int, failure: Int, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        message(start)
        render()
        scope.launch {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error, failure)
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun render() {
        val summary = if (busy) context.getString(Strings.string.processing)
        else context.getString(Strings.string.current_version, version)
        views.checkUpdates.root.bind(context.getString(Strings.string.check_github_updates), summary, trailingIcon = PreferenceIcon.Arrow, enabled = !busy) {
            check()
        }
        val release = update
        views.updateAction.isVisible = release != null
        views.updateAction.isEnabled = !busy
        if (release != null) views.updateAction.text = when {
            downloaded != null -> context.getString(Strings.string.install_update)
            busy -> context.getString(Strings.string.download_progress, progress)
            else -> context.getString(Strings.string.download_version, release.versionName)
        }
    }

    private fun message(id: Int, vararg args: Any) {
        views.updateMessage.showMessage(context.getString(id, *args))
    }

    private fun showError(error: Throwable, fallback: Int) {
        views.updateMessage.showMessage(error.userMessage(context.resources) ?: context.getString(fallback))
    }
}
