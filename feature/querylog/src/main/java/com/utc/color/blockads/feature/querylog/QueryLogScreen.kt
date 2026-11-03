package com.utc.color.blockads.feature.querylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.utc.color.blockads.core.data.*
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun QueryLogScreen(
    repository: QueryLogRepository,
    filters: FilterRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val installed by apps.apps.collectAsStateWithLifecycle()
    var appUid by rememberSaveable { mutableIntStateOf(-1) }
    val choices = remember(installed) { installed.distinctBy { it.uid } }
    var status by rememberSaveable { mutableIntStateOf(0) }
    val rows =
        remember(repository, search, status, appUid) {
                repository.searchPages(search, listOf(-1, 1, 0)[status], appUid)
            }
            .collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<QueryEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
        AppTextInput(search, "Tìm domain", onChange = { search = it })
        AppChoicePreference("Trạng thái", listOf("Tất cả", "Đã chặn", "Cho phép"), status) {
            status = it
        }
        AppChoicePreference(
            "Ứng dụng",
            listOf("Tất cả") + choices.map { it.label },
            (choices.indexOfFirst { it.uid == appUid } + 1).coerceAtLeast(0),
        ) { index ->
            appUid = if (index == 0) -1 else choices[index - 1].uid
        }
        if (rows.itemCount == 0)
            AppCard { AppText("Chưa có truy vấn DNS. Bật VPN và mở một ứng dụng để xem nhật ký.") }
        error?.let { AppText(it) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
                rows[index]?.let { row ->
                    val app = installed.firstOrNull { it.uid == row.uid }
                    val icon by
                        produceState<android.graphics.Bitmap?>(null, app?.packageName) {
                            value = app?.let { apps.icon(it.packageName) }
                        }
                    AppQueryRow(
                        row.domain,
                        row.blocked,
                        java.text.DateFormat.getTimeInstance()
                            .format(java.util.Date(row.timestamp)) +
                            " · " +
                            row.protocol.uppercase(),
                        appName = app?.label ?: row.uid?.let { "UID $it" } ?: "Chưa xác định",
                        icon = icon,
                    ) {
                        selected = row
                    }
                }
            }
        }
    }
    val row = selected
    AppBottomSheet(
        show = row != null,
        title = row?.domain ?: "Truy vấn",
        onDismiss = { selected = null },
    ) {
        if (row != null)
            AppColumn {
                AppText(if (row.blocked) "Đã chặn" else "Cho phép", prominent = true)
                AppText("Rule: ${row.matchedRule ?: "Không có rule khớp"}")
                AppText(
                    "Ứng dụng: " +
                        (installed
                            .filter { it.uid == row.uid }
                            .joinToString { it.label }
                            .takeIf { it.isNotBlank() }
                            ?: row.uid?.let { "UID $it" }
                            ?: "Chưa xác định") +
                        " · " +
                        row.protocol.uppercase()
                )
                AppAction("Chặn domain này") {
                    scope.launch {
                        runCatching {
                            filters.saveCustom(settings.customRules.first() + "\n||${row.domain}^")
                        }
                            .onSuccess { selected = null }
                            .onFailure { error = it.message }
                    }
                }
                AppAction("Cho phép domain này") {
                    scope.launch {
                        runCatching {
                            filters.saveCustom(
                                settings.customRules.first() + "\n@@||${row.domain}^"
                            )
                        }
                            .onSuccess { selected = null }
                            .onFailure { error = it.message }
                    }
                }
            }
    }
}
