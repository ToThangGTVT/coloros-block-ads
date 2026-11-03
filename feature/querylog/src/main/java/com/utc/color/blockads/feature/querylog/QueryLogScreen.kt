package com.utc.color.blockads.feature.querylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
) {
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableIntStateOf(0) }
    val rows =
        remember(repository, search, status) {
                repository.searchPages(search, listOf(-1, 1, 0)[status])
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
        if (rows.itemCount == 0)
            AppCard { AppText("Chưa có truy vấn DNS. Bật VPN và mở một ứng dụng để xem nhật ký.") }
        error?.let { AppText(it) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
                rows[index]?.let { row ->
                    AppQueryRow(
                        row.domain,
                        row.blocked,
                        java.text.DateFormat.getTimeInstance().format(java.util.Date(row.timestamp)),
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
                AppText("Ứng dụng: chưa xác định")
                AppAction("Chặn domain này") {
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
