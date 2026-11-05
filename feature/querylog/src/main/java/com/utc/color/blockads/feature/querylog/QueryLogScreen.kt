package com.utc.color.blockads.feature.querylog

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val strings = rememberAppStrings()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = AppSpacing.page, end = AppSpacing.page, top = 12.dp)) {
            AppContentTabs(listOf(strings.getString(L10n.string.queries), strings.getString(L10n.string.app_statistics)), tab) { tab = it }
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
    val strings = rememberAppStrings()
    val installed by apps.apps.collectAsStateWithLifecycle()
    var appUid by rememberSaveable { mutableIntStateOf(-1) }
    val choices = remember(installed) { installed.distinctBy { it.uid } }
    var status by rememberSaveable { mutableIntStateOf(0) }
    val appByUid = remember(installed) { installed.associateBy { it.uid } }
    val choiceLabels = remember(choices, strings.configuration.locales[0]) { listOf(strings.getString(L10n.string.all)) + choices.map { it.label } }
    val timeFormat = remember(strings.configuration.locales[0]) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.DEFAULT, strings.configuration.locales[0]) }
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
                strings.getString(L10n.string.status), listOf(strings.getString(L10n.string.all), strings.getString(L10n.string.blocked), strings.getString(L10n.string.allowed)), status,
                compact = true, modifier = Modifier.weight(1f),
            ) { status = it }
            AppChoicePreference(
                strings.getString(L10n.string.apps),
                choiceLabels,
                (choices.indexOfFirst { it.uid == appUid } + 1).coerceAtLeast(0),
                compact = true, modifier = Modifier.weight(1f),
            ) { index ->
                appUid = if (index == 0) -1 else choices[index - 1].uid
            }
        }
        if (rows.itemCount == 0) AppCard { AppHint(strings.getString(L10n.string.logs_empty)) }
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
                        appName = app?.label ?: row.uid?.let { "UID $it" } ?: strings.getString(L10n.string.unknown),
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
        title = row?.domain ?: strings.getString(L10n.string.queries),
        onDismiss = { selected = null },
    ) {
        if (row != null)
            AppColumn {
                AppText(if (row.blocked) strings.getString(L10n.string.blocked) else strings.getString(L10n.string.allowed), prominent = true)
                AppHint(strings.getString(L10n.string.matched_rule, row.matchedRule ?: strings.getString(L10n.string.no_matching_rule)))
                AppHint(
                    strings.getString(L10n.string.app_prefix) +
                        (installed
                            .filter { it.uid == row.uid }
                            .joinToString { it.label }
                            .takeIf { it.isNotBlank() }
                            ?: row.uid?.let { "UID $it" }
                            ?: strings.getString(L10n.string.unknown)) +
                        " · " +
                        row.protocol.uppercase()
                )
                AppButtonRow {
                    AppButton(strings.getString(L10n.string.allow_action)) {
                        scope.launch {
                            runCatching {
                                filters.saveCustom(
                                    settings.customRules.first() + "\n@@||${row.domain}^"
                                )
                            }
                                .onSuccess { selected = null }
                                .onFailure { error = it.userMessage(strings) }
                        }
                    }
                    AppButton(strings.getString(L10n.string.block_action), primary = true) {
                        scope.launch {
                            runCatching {
                                filters.saveCustom(
                                    settings.customRules.first() + "\n||${row.domain}^"
                                )
                            }
                                .onSuccess { selected = null }
                                .onFailure { error = it.userMessage(strings) }
                        }
                    }
                }
            }
    }
}
