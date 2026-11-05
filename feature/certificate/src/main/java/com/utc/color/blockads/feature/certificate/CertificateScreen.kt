package com.utc.color.blockads.feature.certificate

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.*
import com.utc.color.blockads.core.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
fun CertificateScreen(
    repository: CertificateRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
    onApps: () -> Unit,
) {
    val strings = rememberAppStrings()
    val state by repository.state.collectAsStateWithLifecycle()
    val config by settings.https.collectAsStateWithLifecycle(initialValue = HttpsSettings())
    val installed by apps.apps.collectAsStateWithLifecycle()
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun action(success: String? = null, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { feedback = success }
                .onFailure { feedback = it.userMessage(strings) ?: strings.getString(L10n.string.action_failed) }
        }
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")
        ) { uri ->
            if (uri != null)
                action(strings.getString(L10n.string.ca_exported)) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(repository.exportCertificate())
                        } ?: error(strings.getString(L10n.string.file_open_failed))
                    }
                }
        }
    AppColumn {
        AppCard {
            AppHint(strings.getString(L10n.string.https_trust_hint))
            AppHint(strings.getString(L10n.string.https_sensitive_hint))
            AppHint(strings.getString(L10n.string.https_css_hint))
        }
        (feedback ?: state.error)?.let { AppCard { AppText(it) } }
        AppSectionTitle(strings.getString(L10n.string.ca_step_create))
        AppCard {
            if (!state.created) {
                AppHint(strings.getString(L10n.string.ca_key_hint))
                AppAction(strings.getString(L10n.string.ca_create), primary = true) { action(strings.getString(L10n.string.ca_created)) { repository.create() } }
            } else {
                AppText(
                    if (state.hardwareBacked) strings.getString(L10n.string.ca_hardware_key)
                    else strings.getString(L10n.string.ca_keystore_key)
                )
                AppHint("SHA-256 ${state.fingerprint}")
            }
        }
        AppSectionTitle(strings.getString(L10n.string.ca_step_install))
        AppPreferenceGroup {
            if (state.created)
                AppArrowPreference(strings.getString(L10n.string.ca_export), strings.getString(L10n.string.ca_export_hint)) {
                    export.launch("OpenAdBlock-CA.crt")
                }
            AppArrowPreference(strings.getString(L10n.string.open_settings), strings.getString(L10n.string.ca_install_path)) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    )
                }
                    .onFailure { feedback = strings.getString(L10n.string.settings_open_failed) }
            }
            AppArrowPreference(
                strings.getString(L10n.string.check_again),
                if (state.installed) strings.getString(L10n.string.ca_installed) else strings.getString(L10n.string.ca_not_installed),
            ) {
                action { repository.refresh() }
            }
        }
        AppSectionTitle(strings.getString(L10n.string.ca_step_apps))
        AppPreferenceGroup {
            installed
                .filter { it.packageName in HttpsSettings().apps || it.packageName in config.apps }
                .forEach { app ->
                    AppSwitchPreference(
                        app.label,
                        app.packageName,
                        app.packageName in config.apps,
                    ) { checked ->
                        action {
                            val saved = settings.https.first()
                            settings.setHttps(
                                saved.copy(
                                    apps =
                                        if (checked) (saved.apps + app.packageName).distinct()
                                        else saved.apps - app.packageName
                                )
                            )
                        }
                    }
                }
            AppArrowPreference(strings.getString(L10n.string.add_apps), onClick = onApps)
        }
        AppPreferenceGroup {
            AppSwitchPreference(
                strings.getString(L10n.string.https_enable),
                strings.getString(L10n.string.https_enable_hint),
                config.enabled && state.installed,
            ) { enabled ->
                action { repository.setEnabled(enabled) }
            }
        }
        AppPreferenceGroup {
            AppArrowPreference(
                if (deleting) strings.getString(L10n.string.ca_deleting) else strings.getString(L10n.string.ca_delete_saved),
                strings.getString(L10n.string.ca_delete_hint),
            ) {
                if (!deleting) confirmDelete = true
            }
        }
        AppHint(strings.getString(L10n.string.ca_uninstall_hint))
    }
    AppBottomSheet(
        show = confirmDelete,
        title = strings.getString(L10n.string.ca_delete_confirm),
        onDismiss = { confirmDelete = false },
    ) {
        AppColumn {
            AppText(strings.getString(L10n.string.ca_delete_warning))
            AppHint(strings.getString(L10n.string.ca_delete_system_hint))
            AppHint(strings.getString(L10n.string.ca_recreate_hint))
            AppButtonRow {
                AppButton(strings.getString(L10n.string.cancel)) { confirmDelete = false }
                AppButton(strings.getString(L10n.string.ca_delete), primary = true) {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            try {
                                repository.delete()
                                feedback = strings.getString(L10n.string.ca_deleted)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                feedback = error.userMessage(strings) ?: strings.getString(L10n.string.ca_delete_failed)
                            } finally {
                                deleting = false
                            }
                        }
                    }
                }
            }
        }
    }
}
