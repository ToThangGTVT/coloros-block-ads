package com.utc.color.blockads.feature.filters

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FiltersScreen(
    filters: FilterRepository,
    settings: SettingsRepository,
    onCatalog: () -> Unit,
    onBrowserCatalog: () -> Unit,
) {
    val strings = rememberAppStrings()
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by filters.status.collectAsStateWithLifecycle()
    val saved by settings.customRules.collectAsStateWithLifecycle(initialValue = "")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()
    val browser = tab == 1
    val visibleLists = remember(lists, browser) { lists.filter { it.browser == browser } }
    var custom by rememberSaveable { mutableStateOf<String?>(null) }
    var dnsUrl by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("") }
    // Capture the target type when opening the sheet, independent of the selected tab.
    var addingBrowser by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember(strings.configuration.locales[0]) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, strings.configuration.locales[0]) }
    val numberFormat = remember(strings.configuration.locales[0]) { java.text.NumberFormat.getIntegerInstance(strings.configuration.locales[0]) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.userMessage(strings) ?: strings.getString(L10n.string.operation_failed)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = AppSpacing.page)) {
            AppContentTabs(listOf(strings.getString(L10n.string.dns_filters), strings.getString(L10n.string.browser_filters)), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            states.SaveableStateProvider(tab) {
                AppLazyList {
                    item(key = "overview", contentType = "card") {
                        AppCard {
                            AppColumn {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
                                    ) {
                                        AppText(
                                            if (browser) strings.getString(L10n.string.web_filtering)
                                            else strings.getString(L10n.string.domain_blocking),
                                            prominent = true,
                                        )
                                        AppHint(
                                            if (browser) strings.getString(L10n.string.browser_https_required)
                                            else strings.getString(L10n.string.all_apps_dns_hint)
                                        )
                                    }
                                    AppHelpTooltip(
                                        text =
                                            if (browser)
                                                strings.getString(L10n.string.browser_filters_help)
                                            else
                                                strings.getString(L10n.string.dns_filters_help),
                                        label =
                                            if (browser) strings.getString(L10n.string.browser_filters_help_label)
                                            else strings.getString(L10n.string.dns_filters_help_label),
                                    )
                                }
                                AppText(
                                    strings.getQuantityString(L10n.plurals.lists_enabled, visibleLists.size, visibleLists.count { it.enabled }, visibleLists.size)
                                )
                                if (status.loading) AppHint(strings.getString(L10n.string.filters_loading))
                            }
                        }
                    }
                    (error ?: status.error)?.let { message ->
                        item(key = "error", contentType = "card") { AppCard { AppText(message) } }
                    }
                    item(key = "sources", contentType = "card") {
                        AppPreferenceGroup {
                            AppArrowPreference(
                                strings.getString(L10n.string.choose_from_catalog),
                                if (browser) strings.getString(L10n.string.adguard_browser_filters)
                                else strings.getString(L10n.string.adguard_dns_lists),
                                if (browser) onBrowserCatalog else onCatalog,
                            )
                            AppArrowPreference(strings.getString(L10n.string.add_by_url), strings.getString(L10n.string.filter_url_hint)) {
                                sheetError = null
                                addingBrowser = browser
                            }
                        }
                    }
                    item(key = "lists-title", contentType = "title") {
                        AppSectionTitle(strings.getString(L10n.string.added_lists))
                    }
                    if (visibleLists.isEmpty()) {
                        item(key = "empty", contentType = "card") {
                            AppCard {
                                AppText(strings.getString(L10n.string.filters_empty))
                                AppHint(strings.getString(L10n.string.filters_empty_hint))
                            }
                        }
                    }
                    items(visibleLists, key = { it.id }, contentType = { "filter" }) { list ->
                        AppPreferenceGroup {
                            AppSwitchPreference(
                                title = list.name,
                                summary =
                                    list.error
                                        ?: (strings.getQuantityString(L10n.plurals.filter_rules_prefix, list.ruleCount, numberFormat.format(list.ruleCount)) +
                                            if (list.updatedAt == 0L) strings.getString(L10n.string.not_downloaded)
                                            else dateFormat.format(java.util.Date(list.updatedAt))),
                                checked = list.enabled,
                                onCheckedChange = { action { filters.setEnabled(list.id, it) } },
                            )
                        }
                    }
                    item(key = "shared-title", contentType = "title") {
                        AppSectionTitle(strings.getString(L10n.string.shared_management))
                    }
                    item(key = "shared-actions", contentType = "card") {
                        AppPreferenceGroup {
                            AppArrowPreference(
                                strings.getString(L10n.string.update_all),
                                strings.getQuantityString(L10n.plurals.loaded_rules_summary, status.loaded, numberFormat.format(status.loaded)),
                            ) {
                                if (!status.loading) action { filters.updateAll() }
                            }
                            AppArrowPreference(
                                strings.getString(L10n.string.custom_rules),
                                strings.getString(L10n.string.custom_rules_shared),
                            ) {
                                sheetError = null
                                editingCustom = true
                            }
                            if (status.ignored > 0)
                                AppGroupContent {
                                    AppHint(
                                        strings.getQuantityString(L10n.plurals.unsupported_rules_ignored, status.ignored, numberFormat.format(status.ignored))
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
    val addBrowser = addingBrowser
    AppBottomSheet(
        show = addBrowser != null,
        title = if (addBrowser == true) strings.getString(L10n.string.add_browser_filter) else strings.getString(L10n.string.add_dns_filter),
        onDismiss = { if (!busy) addingBrowser = null },
    ) {
        if (addBrowser != null)
            AppColumn {
                AppTextInput(
                    value = if (addBrowser) browserUrl else dnsUrl,
                    label = strings.getString(L10n.string.filter_url),
                    error = sheetError != null,
                    onChange = {
                        if (addBrowser) browserUrl = it else dnsUrl = it
                        sheetError = null
                    },
                )
                sheetError?.let { AppText(it) }
                AppAction(if (busy) strings.getString(L10n.string.adding) else strings.getString(L10n.string.add_filter), primary = true) {
                    if (!busy) {
                        val url = if (addBrowser) browserUrl else dnsUrl
                        busy = true
                        scope.launch {
                            try {
                                filters.addUrl(url, browser = addBrowser)
                                if (addBrowser) browserUrl = "" else dnsUrl = ""
                                addingBrowser = null
                                sheetError = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                sheetError = failure.userMessage(strings) ?: strings.getString(L10n.string.filter_add_failed)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
    }
    AppBottomSheet(
        show = editingCustom,
        title = strings.getString(L10n.string.custom_rules),
        onDismiss = { if (!busy) editingCustom = false },
    ) {
        AppColumn {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AppHint(strings.getString(L10n.string.custom_rules_shared)) }
                AppHelpTooltip(
                    text =
                        strings.getString(L10n.string.custom_rules_help),
                    label = strings.getString(L10n.string.custom_rules_help_label),
                )
            }
            AppTextInput(
                value = custom ?: saved,
                label = strings.getString(L10n.string.one_rule_per_line),
                multiline = true,
                error = sheetError != null,
                onChange = {
                    custom = it
                    sheetError = null
                },
            )
            sheetError?.let { AppText(it) }
            AppAction(if (busy) strings.getString(L10n.string.saving) else strings.getString(L10n.string.save_rules), primary = true) {
                if (!busy) {
                    val rules = custom ?: saved
                    busy = true
                    scope.launch {
                        try {
                            filters.saveCustom(rules)
                            custom = null
                            editingCustom = false
                            sheetError = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            sheetError = failure.userMessage(strings) ?: strings.getString(L10n.string.rules_save_failed)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
