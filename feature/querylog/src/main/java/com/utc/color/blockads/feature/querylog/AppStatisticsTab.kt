package com.utc.color.blockads.feature.querylog

import com.utc.color.blockads.core.common.R as L10n
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
internal fun AppStatisticsTab(
    repository: QueryLogRepository,
    apps: AppsRepository,
    onAppRequests: (Int?, String) -> Unit,
) {
    val strings = rememberAppStrings()
    var period by rememberSaveable { mutableIntStateOf(0) }
    val hours = if (period == 0) 24 else 168
    val statsFlow = remember(repository, hours) { repository.appStatistics(hours) }
    // Key the collector so changing period immediately clears the previous window.
    val stats =
        key(hours) {
            val value by statsFlow.collectAsStateWithLifecycle(initialValue = null)
            value
        }
    val installed by apps.apps.collectAsStateWithLifecycle()
    val appsByUid = remember(installed) { installed.groupBy { it.uid } }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val ranked =
        remember(stats, sort) {
            stats.orEmpty().sortedWith(
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
                strings.getString(L10n.string.time_range),
                listOf(strings.getString(L10n.string.last_day), strings.getString(L10n.string.seven_days)),
                period,
                compact = true,
                showSelectedValue = true,
                modifier = Modifier.weight(1f),
            ) {
                period = it
            }
            AppChoicePreference(
                strings.getString(L10n.string.sort_by),
                listOf(strings.getString(L10n.string.most_requests), strings.getString(L10n.string.most_blocks)),
                sort,
                compact = true,
                showSelectedValue = true,
                modifier = Modifier.weight(1f),
            ) {
                sort = it
            }
        }
        AppHint(strings.getString(L10n.string.app_statistics_hint))
        Spacer(Modifier.height(AppSpacing.gap))
        if (ranked.isEmpty())
            AppCard {
                AppHint(
                    if (stats == null) strings.getString(L10n.string.statistics_loading)
                    else strings.getString(L10n.string.logs_period_empty)
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
                        .ifBlank { item.uid?.let { "UID $it" } ?: strings.getString(L10n.string.unknown) }
                val icon by
                    produceState(app?.let { apps.cachedIcon(it.packageName) }, app?.packageName) {
                        value = app?.let { apps.icon(it.packageName) }
                    }
                AppCard(onClick = { onAppRequests(item.uid, label) }) {
                    AppColumn {
                        AppIdentity(label, icon)
                        AppRequestProgress(total = item.total, blocked = item.blocked)
                    }
                }
            }
        }
    }
}
