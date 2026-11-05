package com.utc.color.blockads.feature.querylog

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.utc.color.blockads.core.data.*
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun QueryLogScreen(
    repository: QueryLogRepository,
    filters: FilterRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
    onAppRequests: (Int?, String) -> Unit,
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
                else AppStatisticsTab(repository, apps, onAppRequests)
            }
        }
    }
}

@Composable
fun AppRequestsScreen(
    repository: QueryLogRepository,
    filters: FilterRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
    uid: Int?,
    isCurrent: Boolean,
) {
    val preferredMinutes by settings.requestCaptureMinutes.collectAsStateWithLifecycle(
        initialValue = DEFAULT_REQUEST_CAPTURE_MINUTES,
    )
    var activeMinutes by remember(uid) { mutableStateOf<Int?>(null) }
    val duration = activeMinutes
    if (duration != null && uid != null && isCurrent) {
        DisposableEffect(repository, uid, duration) {
            repository.setRequestCapture(uid, duration)
            onDispose { repository.setRequestCapture(null) }
        }
        LaunchedEffect(uid, duration) {
            delay(duration * 60_000L)
            activeMinutes = null
        }
    }
    val previews by repository.requestPreviews.collectAsStateWithLifecycle()
    QueryHistory(
        repository, filters, settings, apps,
        appOnly = true,
        fixedUid = uid,
        previews = if (duration != null && isCurrent) previews else emptyMap(),
        captureControls = if (uid != null) {
            {
                RequestCaptureControls(settings, preferredMinutes, duration != null) { enabled ->
                    activeMinutes = if (enabled) preferredMinutes else null
                }
            }
        } else null,
    )
}

@Composable
private fun RequestCaptureControls(
    settings: SettingsRepository,
    minutes: Int,
    capturing: Boolean,
    onCapture: (Boolean) -> Unit,
) {
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val title = strings.getString(
        if (capturing) L10n.string.request_capture_next_duration else L10n.string.request_capture_duration,
    )
    AppPreferenceGroup {
        AppSwitchPreference(
            title = strings.getString(L10n.string.request_capture),
            summary = strings.getString(L10n.string.request_capture_hint),
            checked = capturing,
            onCheckedChange = onCapture,
        )
        AppArrowPreference(title, strings.getQuantityString(L10n.plurals.request_capture_minutes, minutes, minutes)) {
            input = minutes.toString()
            error = null
        }
    }
    AppBottomSheet(
        show = input != null,
        title = title,
        onDismiss = { if (!saving) input = null },
    ) {
        input?.let { value ->
            AppColumn {
                val rangeLabel = strings.getString(L10n.string.request_capture_minute_range, MAX_REQUEST_CAPTURE_MINUTES)
                AppTextInput(
                    value = value,
                    label = rangeLabel,
                    error = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                ) {
                    if (!saving) {
                        input = it
                        error = null
                    }
                }
                error?.let { AppHint(it) }
                AppAction(strings.getString(L10n.string.save), primary = true) {
                    val newMinutes = input?.trim()?.toIntOrNull()
                    if (newMinutes == null || newMinutes !in 1..MAX_REQUEST_CAPTURE_MINUTES) {
                        error = strings.getString(L10n.string.request_capture_invalid_minutes, MAX_REQUEST_CAPTURE_MINUTES)
                    } else if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                settings.setRequestCaptureMinutes(newMinutes)
                                input = null
                            } catch (failure: IOException) {
                                error = failure.userMessage(strings) ?: strings.getString(L10n.string.action_failed)
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
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
    appOnly: Boolean = false,
    fixedUid: Int? = null,
    previews: Map<Long, RequestPreview> = emptyMap(),
    captureControls: (@Composable () -> Unit)? = null,
) {
    val strings = rememberAppStrings()
    val installed by apps.apps.collectAsStateWithLifecycle()
    var appUid by rememberSaveable { mutableIntStateOf(-1) }
    val choices = remember(installed) { installed.distinctBy { it.uid } }
    var status by rememberSaveable { mutableIntStateOf(0) }
    val appByUid = remember(installed) { installed.associateBy { it.uid } }
    val choiceLabels = remember(choices, strings.configuration.locales[0]) { listOf(strings.getString(L10n.string.all)) + choices.map { it.label } }
    val locale = strings.configuration.locales[0]
    val timeFormat = remember(locale, appOnly) {
        if (appOnly) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM, locale)
        else java.text.DateFormat.getTimeInstance(java.text.DateFormat.DEFAULT, locale)
    }
    val rows =
        remember(repository, status, appUid, appOnly, fixedUid) {
                val blocked = listOf(-1, 1, 0)[status]
                if (appOnly) repository.appPages(fixedUid, blocked)
                else repository.searchPages("", blocked, appUid)
            }
            .collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<QueryEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.page)) {
        if (captureControls != null) {
            Spacer(Modifier.height(AppSpacing.gap))
            captureControls()
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = AppSpacing.gap),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        ) {
            AppChoicePreference(
                strings.getString(L10n.string.status), listOf(strings.getString(L10n.string.all), strings.getString(L10n.string.blocked), strings.getString(L10n.string.allowed)), status,
                compact = true, modifier = Modifier.weight(1f),
            ) { status = it }
            if (!appOnly) AppChoicePreference(
                strings.getString(L10n.string.apps),
                choiceLabels,
                (choices.indexOfFirst { it.uid == appUid } + 1).coerceAtLeast(0),
                compact = true, modifier = Modifier.weight(1f),
            ) { index ->
                appUid = if (index == 0) -1 else choices[index - 1].uid
            }
        }
        val refresh = rows.loadState.refresh
        if (rows.itemCount == 0) {
            AppCard {
                when (refresh) {
                    LoadState.Loading -> AppHint(strings.getString(L10n.string.requests_loading))
                    is LoadState.Error -> AppHint(refresh.error.userMessage(strings) ?: strings.getString(L10n.string.action_failed))
                    is LoadState.NotLoading ->
                        AppHint(strings.getString(if (appOnly) L10n.string.app_requests_empty else L10n.string.logs_empty))
                }
            }
        }
        error?.let { AppText(it) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
            contentPadding = PaddingValues(bottom = AppSpacing.gap),
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    if (appOnly) {
                        AppHint(timeFormat.format(java.util.Date(row.timestamp)))
                        val preview = previews[row.id]
                        if (preview == null) {
                            AppHint(strings.getString(L10n.string.request_preview_unavailable))
                        } else {
                            RequestPreviewDetails(preview)
                        }
                    }
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
}

@Composable
private fun RequestPreviewDetails(preview: RequestPreview) {
    val strings = rememberAppStrings()
    SelectionContainer {
        AppColumn {
            AppText("${preview.method} ${preview.path}")
            AppHint(strings.getString(L10n.string.request_query_parameters))
            AppText(
                preview.query?.takeIf { it.isNotEmpty() }?.replace('&', '\n')
                    ?: strings.getString(L10n.string.request_no_query_parameters)
            )
            if (preview.urlTruncated) AppHint(strings.getString(L10n.string.request_url_truncated))
            AppHint(strings.getString(L10n.string.request_body))
            AppText(preview.body ?: strings.getString(L10n.string.request_body_unavailable))
            if (preview.body != null && preview.bodyTruncated) {
                AppHint(strings.getString(L10n.string.request_body_truncated))
            }
        }
    }
}
