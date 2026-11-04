package com.utc.color.blockads.feature.apps

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.designsystem.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(
    repository: AppsRepository,
    settings: com.utc.color.blockads.core.data.SettingsRepository,
    firewallOnly: Boolean = false,
) {
    val strings = rememberAppStrings()
    val apps by repository.apps.collectAsStateWithLifecycle()
    val https by settings.https.collectAsStateWithLifecycle(initialValue = HttpsSettings())
    val policies by repository.policies.collectAsStateWithLifecycle()
    val blockedApps by repository.blockedApps.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uidCounts = remember(apps) { apps.groupingBy { it.uid }.eachCount() }
    val modeTitles = AppFilterMode.entries.map { strings.getString(it.titleRes) }
    val blockedUids =
        remember(apps, policies, blockedApps) {
            resolveAppPolicy(apps, policies, blockedApps).blockedUids
        }
    val visible =
        remember(apps, search, system) {
            apps.filter {
                (system || !it.system) &&
                    (it.label.contains(search, true) || it.packageName.contains(search, true))
            }
        }
    Column(
        Modifier.fillMaxSize().padding(horizontal = AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            Box(Modifier.weight(1f)) {
                AppTextInput(search, strings.getString(L10n.string.search_apps), onChange = { search = it })
            }
            if (firewallOnly) AppHelpTooltip(
                text = strings.getString(L10n.string.firewall_help),
                label = strings.getString(L10n.string.firewall_help_label),
            )
        }
        AppPreferenceGroup {
            AppSwitchPreference(strings.getString(L10n.string.show_system_apps), strings.getString(L10n.string.network_apps_only), system) {
                system = it
            }
        }
        error?.let { AppText(it) }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
            contentPadding = PaddingValues(bottom = AppSpacing.page),
        ) {
            items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
                val icon by
                    produceState(repository.cachedIcon(app.packageName), app.packageName) {
                        value = repository.icon(app.packageName)
                    }
                AppPreferenceGroup {
                    AppItemHeader(app.label, app.packageName, icon)
                    if (firewallOnly) {
                        AppSwitchPreference(
                            strings.getString(L10n.string.allow_network),
                            if ((uidCounts[app.uid] ?: 0) > 1)
                                strings.getString(L10n.string.shared_uid_policy)
                            else if (app.uid in blockedUids) strings.getString(L10n.string.network_blocked)
                            else strings.getString(L10n.string.network_allowed),
                            app.uid !in blockedUids,
                        ) { allowed ->
                            scope.launch {
                                runCatching { repository.setNetworkAllowed(app, allowed) }
                                    .onSuccess { error = null }
                                    .onFailure { error = strings.getString(L10n.string.firewall_save_failed) }
                            }
                        }
                    } else if (app.uid in blockedUids) {
                        AppGroupContent {
                            AppHint(
                                strings.getString(L10n.string.network_blocked_policy_hint)
                            )
                        }
                    }
                    if (!firewallOnly) {
                        AppSwitchPreference(
                            strings.getString(L10n.string.https_filtering),
                            strings.getString(L10n.string.ca_required),
                            app.packageName in https.apps,
                        ) { checked ->
                            scope.launch {
                                runCatching {
                                    val saved = settings.https.first()
                                    settings.setHttps(
                                        saved.copy(
                                            apps =
                                                if (checked)
                                                    (saved.apps + app.packageName).distinct()
                                                else saved.apps - app.packageName
                                        )
                                    )
                                }
                                    .onFailure { error = it.userMessage(strings) }
                            }
                        }
                        AppChoicePreference(
                            strings.getString(L10n.string.app_policy_title, app.label),
                            modeTitles,
                            (policies[app.packageName] ?: AppFilterMode.Filter).ordinal,
                        ) { index ->
                            scope.launch {
                                runCatching {
                                    repository.setMode(app, AppFilterMode.entries[index])
                                }
                                    .onFailure { error = strings.getString(L10n.string.app_policy_save_failed) }
                            }
                        }
                    }
                }
            }
        }
    }
}
