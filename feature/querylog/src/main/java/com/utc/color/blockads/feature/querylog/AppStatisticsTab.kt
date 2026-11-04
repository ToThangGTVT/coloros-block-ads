package com.utc.color.blockads.feature.querylog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.data.AppQueryStats
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.designsystem.*

@Composable
internal fun AppStatisticsTab(repository: QueryLogRepository, apps: AppsRepository) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    val hours = if (period == 0) 24 else 168
    val historyFlow = remember(repository, hours) { repository.appStatistics(hours) }
    // Key the collector so changing period immediately clears the previous window.
    val history =
        key(hours) {
            val value by historyFlow.collectAsStateWithLifecycle(initialValue = null)
            value
        }
    val stats =
        remember(history) {
            history
                ?.buckets
                .orEmpty()
                .groupBy { it.uid }
                .map { (uid, buckets) ->
                    AppQueryStats(uid, buckets.sumOf { it.total }, buckets.sumOf { it.blocked })
                }
        }
    val installed by apps.apps.collectAsStateWithLifecycle()
    val appsByUid = remember(installed) { installed.groupBy { it.uid } }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val ranked =
        remember(stats, sort) {
            stats.sortedWith(
                compareByDescending<AppQueryStats> { if (sort == 0) it.total else it.blocked }
                    .thenByDescending { it.total }
                    .thenBy { it.uid ?: -1 }
            )
        }
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = AppSpacing.gap),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            AppChoicePreference(
                "Thời gian",
                listOf("24 giờ", "7 ngày"),
                period,
                compact = true,
                showSelectedValue = true,
                modifier = Modifier.weight(1f),
            ) {
                period = it
            }
            AppChoicePreference(
                "Sắp xếp theo",
                listOf("Nhiều request", "Nhiều lượt chặn"),
                sort,
                compact = true,
                showSelectedValue = true,
                modifier = Modifier.weight(1f),
            ) {
                sort = it
            }
        }
        AppHint("Dựa trên nhật ký còn lưu trong khoảng đã chọn. Các app cùng UID được tính chung.")
        Spacer(Modifier.height(AppSpacing.gap))
        if (ranked.isEmpty())
            AppCard {
                AppHint(
                    if (history == null) "Đang tải thống kê…"
                    else "Chưa có nhật ký trong khoảng này."
                )
            }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
            contentPadding = PaddingValues(bottom = AppSpacing.gap),
        ) {
            items(ranked, key = { item -> item.uid ?: -1 }, contentType = { "app-stats" }) { item ->
                val matches = appsByUid[item.uid].orEmpty()
                val app = matches.firstOrNull()
                val label =
                    matches
                        .joinToString { it.label }
                        .ifBlank { item.uid?.let { "UID $it" } ?: "Chưa xác định" }
                val icon by
                    produceState(app?.let { apps.cachedIcon(it.packageName) }, app?.packageName) {
                        value = app?.let { apps.icon(it.packageName) }
                    }
                AppCard {
                    AppColumn {
                        AppIdentity(label, icon)
                        AppRequestProgress(total = item.total, blocked = item.blocked)
                    }
                }
            }
        }
    }
}
