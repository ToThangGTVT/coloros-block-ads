package com.utc.color.blockads.feature.querylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = AppSpacing.page)) {
            AppContentTabs(listOf("Truy vấn", "Thống kê ứng dụng"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            states.SaveableStateProvider(tab) {
                if (tab == 0) QueryHistory(repository, filters, settings, apps)
                else AppStatisticsTab(repository, apps)
            }
        }
    }
}

@Composable
private fun QueryHistory(
    repository: QueryLogRepository,
    filters: FilterRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
) {
    val installed by apps.apps.collectAsStateWithLifecycle()
    var appUid by rememberSaveable { mutableIntStateOf(-1) }
    val choices = remember(installed) { installed.distinctBy { it.uid } }
    var status by rememberSaveable { mutableIntStateOf(0) }
    val appByUid = remember(installed) { installed.associateBy { it.uid } }
    val choiceLabels = remember(choices) { listOf("Tất cả") + choices.map { it.label } }
    val timeFormat = remember { java.text.DateFormat.getTimeInstance() }
    val rows =
        remember(repository, status, appUid) {
                repository.searchPages("", listOf(-1, 1, 0)[status], appUid)
            }
            .collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<QueryEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = AppSpacing.gap),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            AppChoicePreference(
                "Trạng thái", listOf("Tất cả", "Đã chặn", "Cho phép"), status,
                compact = true, modifier = Modifier.weight(1f),
            ) { status = it }
            AppChoicePreference(
                "Ứng dụng",
                choiceLabels,
                (choices.indexOfFirst { it.uid == appUid } + 1).coerceAtLeast(0),
                compact = true, modifier = Modifier.weight(1f),
            ) { index ->
                appUid = if (index == 0) -1 else choices[index - 1].uid
            }
        }
        if (rows.itemCount == 0) AppCard { AppHint("Chưa có nhật ký. Bật VPN để bắt đầu.") }
        error?.let { AppText(it) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            items(
                count = rows.itemCount,
                key = rows.itemKey { it.id },
                contentType = { "query" },
            ) { index ->
                rows[index]?.let { row ->
                    val app = appByUid[row.uid]
                    val icon by
                        produceState(
                            app?.let { apps.cachedIcon(it.packageName) },
                            app?.packageName,
                        ) {
                            value = app?.let { apps.icon(it.packageName) }
                        }
                    AppQueryRow(
                        row.domain,
                        row.blocked,
                        timeFormat.format(java.util.Date(row.timestamp)) +
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
                AppHint("Rule: ${row.matchedRule ?: "Không có rule khớp"}")
                AppHint(
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
                AppButtonRow {
                    AppButton("Cho phép") {
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
                    AppButton("Chặn", primary = true) {
                        scope.launch {
                            runCatching {
                                filters.saveCustom(
                                    settings.customRules.first() + "\n||${row.domain}^"
                                )
                            }
                                .onSuccess { selected = null }
                                .onFailure { error = it.message }
                        }
                    }
                }
            }
    }
}
