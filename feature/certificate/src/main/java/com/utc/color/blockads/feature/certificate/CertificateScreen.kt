package com.utc.color.blockads.feature.certificate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.common.HttpsSettings
import com.utc.color.blockads.core.common.InstalledApp
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.FormSheet
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.preference
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.feature.certificate.databinding.ScreenCertificateBinding
import com.utc.color.blockads.feature.certificate.databinding.SheetDeleteCertificateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.utc.color.blockads.core.common.R as Strings

class CertificateScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenCertificateBinding,
    state: Bundle,
    private val repository: CertificateRepository,
    private val settings: SettingsRepository,
    apps: AppsRepository,
    onApps: () -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, repository: CertificateRepository,
        settings: SettingsRepository, apps: AppsRepository, onApps: () -> Unit,
    ) : this(activity, ScreenCertificateBinding.inflate(parent.inflater, parent, false), state, repository, settings, apps, onApps)

    private var config = HttpsSettings()
    private var installed = apps.apps.value
    private var unsupported = apps.httpsUnsupported.value
    private var feedback: String? = null
    private var confirmingDelete = state.getBoolean("confirmDelete")
    private var deleting = false
    private var creating = false
    private val export = result("certificate.export", ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
        if (uri != null) perform(Strings.string.ca_exported) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(repository.exportCertificate()) }
                    ?: error(context.getString(Strings.string.file_open_failed))
            }
        }
    }

    init {
        views.addApps.root.bind(context.getString(Strings.string.add_apps), trailingIcon = PreferenceIcon.Arrow, action = onApps)
        views.openSettings.root.bind(
            context.getString(Strings.string.open_settings), context.getString(Strings.string.ca_install_path), trailingIcon = PreferenceIcon.Arrow,
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }.onFailure {
                feedback = context.getString(Strings.string.settings_open_failed)
                renderCertificate()
            }
        }
        views.createCa.setOnClickListener {
            creating = true
            renderCertificate()
            perform(Strings.string.ca_created, onFinished = { creating = false }) { repository.create() }
        }
        views.exportCa.root.bind(
            context.getString(Strings.string.ca_export), context.getString(Strings.string.ca_export_hint), trailingIcon = PreferenceIcon.Arrow,
        ) { export.launch("OpenAdBlock-CA.crt") }
        renderCertificate()
        renderApps()
        observe(repository.state) { renderCertificate() }
        observe(settings.https) {
            config = it
            renderCertificate()
            renderApps()
        }
        observe(apps.apps) {
            installed = it
            renderApps()
        }
        observe(apps.httpsUnsupported) {
            unsupported = it
            renderApps()
        }
        if (confirmingDelete) view.doOnAttach {
            it.post { if (it.isAttachedToWindow && confirmingDelete) confirmDelete() }
        }
    }

    private fun renderCertificate() {
        val state = repository.state.value
        views.feedback.showMessage(feedback ?: state.error)
        views.createHint.isVisible = !state.created
        views.createCa.isVisible = !state.created
        views.createCa.isEnabled = !creating && !deleting
        views.keyStatus.isVisible = state.created
        views.keyStatus.setText(if (state.hardwareBacked) Strings.string.ca_hardware_key else Strings.string.ca_keystore_key)
        views.fingerprint.showMessage(state.fingerprint?.takeIf { state.created }?.let { "SHA-256 $it" })
        views.exportCa.root.isVisible = state.created
        views.checkAgain.root.bind(
            context.getString(Strings.string.check_again),
            context.getString(if (state.installed) Strings.string.ca_installed else Strings.string.ca_not_installed),
            trailingIcon = PreferenceIcon.Arrow,
        ) { perform { repository.refresh() } }
        val enabled = config.enabled && state.installed
        views.httpsEnable.root.bind(
            context.getString(Strings.string.https_enable), context.getString(Strings.string.https_enable_hint),
            checked = enabled, enabled = !deleting,
        ) { perform { repository.setEnabled(!enabled) } }
        views.deleteSaved.root.bind(
            context.getString(if (deleting) Strings.string.ca_deleting else Strings.string.ca_delete_saved),
            context.getString(Strings.string.ca_delete_hint), trailingIcon = PreferenceIcon.Arrow, enabled = !creating && !deleting,
        ) { confirmDelete() }
    }

    private fun renderApps() {
        views.selectedApps.removeAllViews()
        installed.filter { it.packageName in HttpsSettings().apps || it.packageName in config.apps }.forEach(::addApp)
    }

    private fun addApp(app: InstalledApp) {
        val supported = app.packageName !in unsupported
        val selected = app.packageName in config.apps
        views.selectedApps.preference().bind(
            app.label,
            if (supported) app.packageName else context.getString(Strings.string.https_app_unsupported),
            checked = selected && supported, enabled = supported || selected,
        ) {
            perform {
                val saved = settings.https.first()
                val packages = if (selected || !supported) saved.apps - app.packageName
                    else (saved.apps + app.packageName).distinct()
                settings.setHttps(saved.copy(apps = packages))
            }
        }
    }

    private fun confirmDelete() {
        confirmingDelete = true
        val sheet = FormSheet(context, context.getString(Strings.string.ca_delete_confirm))
        val content = SheetDeleteCertificateBinding.inflate(sheet.content.inflater, sheet.content, true)
        sheet.dialog.setOnDismissListener { confirmingDelete = false }
        content.cancel.setOnClickListener { sheet.dialog.dismiss() }
        content.delete.setOnClickListener {
            sheet.dialog.dismiss()
            deleting = true
            renderCertificate()
            perform(Strings.string.ca_deleted, Strings.string.ca_delete_failed, onFinished = { deleting = false }) {
                repository.delete()
            }
        }
        show(sheet.dialog)
    }

    override fun saveState() = super.saveState().apply {
        putBoolean("confirmDelete", confirmingDelete)
    }

    private fun perform(
        success: Int? = null,
        fallback: Int = Strings.string.action_failed,
        onFinished: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        fun finish(message: String?) {
            onFinished()
            feedback = message
            renderCertificate()
        }
        action({ finish(success?.let(context::getString)) }, {
            finish(it.userMessage(resources) ?: context.getString(fallback))
        }, block)
    }
}
