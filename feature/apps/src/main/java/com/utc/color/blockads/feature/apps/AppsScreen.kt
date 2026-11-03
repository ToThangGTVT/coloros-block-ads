package com.utc.color.blockads.feature.apps

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
    val apps by repository.apps.collectAsStateWithLifecycle()
    val https by settings.https.collectAsStateWithLifecycle(initialValue = HttpsSettings())
    val policies by repository.policies.collectAsStateWithLifecycle()
    val blockedApps by repository.blockedApps.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uidCounts = remember(apps) { apps.groupingBy { it.uid }.eachCount() }
    val modeTitles = remember { AppFilterMode.entries.map { it.title } }
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
        AppTextInput(search, "Tìm ứng dụng", onChange = { search = it })
        if (firewallOnly)
            AppHint(
                "Tường lửa chỉ có hiệu lực khi VPN BlockAds đang bật. Áp dụng cho Wi-Fi và dữ liệu di động."
            )
        AppPreferenceGroup {
            AppSwitchPreference("Hiện app hệ thống", "Chỉ app có dùng mạng", system) {
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
                            "Cho phép truy cập mạng",
                            if ((uidCounts[app.uid] ?: 0) > 1)
                                "Áp dụng cho tất cả ứng dụng dùng chung UID"
                            else if (app.uid in blockedUids) "Đã chặn toàn bộ mạng khi VPN bật"
                            else "Cho phép Wi-Fi và dữ liệu di động",
                            app.uid !in blockedUids,
                        ) { allowed ->
                            scope.launch {
                                runCatching { repository.setNetworkAllowed(app, allowed) }
                                    .onSuccess { error = null }
                                    .onFailure { error = "Không lưu được cấu hình tường lửa" }
                            }
                        }
                    } else if (app.uid in blockedUids) {
                        AppGroupContent {
                            AppHint(
                                "Mạng đang bị chặn trong tab Tường lửa. Cấu hình lọc sẽ áp dụng khi cho phép mạng lại."
                            )
                        }
                    }
                    if (!firewallOnly) {
                        AppSwitchPreference(
                            "Lọc HTTPS",
                            "Cần cài CA",
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
                                    .onFailure { error = it.message }
                            }
                        }
                        AppChoicePreference(
                            "Chính sách · ${app.label}",
                            modeTitles,
                            (policies[app.packageName] ?: AppFilterMode.Filter).ordinal,
                        ) { index ->
                            scope.launch {
                                runCatching {
                                    repository.setMode(app, AppFilterMode.entries[index])
                                }
                                    .onFailure { error = "Không lưu được chính sách ứng dụng" }
                            }
                        }
                    }
                }
            }
        }
    }
}
