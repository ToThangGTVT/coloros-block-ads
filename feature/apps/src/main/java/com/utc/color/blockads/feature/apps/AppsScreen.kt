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
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(repository: AppsRepository) {
    val apps by repository.apps.collectAsStateWithLifecycle()
    val policies by repository.policies.collectAsStateWithLifecycle(initialValue = emptyMap())
    var search by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val visible =
        remember(apps, search, system) {
            apps.filter {
                (system || !it.system) &&
                    (it.label.contains(search, true) || it.packageName.contains(search, true))
            }
        }
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
        AppTextInput(search, "Tìm ứng dụng", onChange = { search = it })
        AppSwitchPreference("Ứng dụng hệ thống", "Hiển thị app có quyền truy cập mạng", system) {
            system = it
        }
        AppText(
            "Bỏ qua tunnel áp dụng lại VPN và ngắt kết nối đang mở. App chung UID dùng chung chính sách."
        )
        error?.let { AppText(it) }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            items(visible, key = { it.packageName }) { app ->
                val icon by
                    produceState<android.graphics.Bitmap?>(null, app.packageName) {
                        value = repository.icon(app.packageName)
                    }
                AppCard {
                    AppIdentity(app.label, icon)
                    AppText(app.packageName)
                    AppChoicePreference(
                        "Chính sách · ${app.label}",
                        AppFilterMode.entries.map { it.title },
                        (policies[app.packageName] ?: AppFilterMode.Filter).ordinal,
                    ) { index ->
                        scope.launch {
                            runCatching { repository.setMode(app, AppFilterMode.entries[index]) }
                                .onFailure { error = "Không lưu được chính sách ứng dụng" }
                        }
                    }
                }
            }
        }
    }
}
