package com.utc.color.blockads.feature.filters

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.CatalogEntry
import com.utc.color.blockads.core.filter.CatalogSource
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.launch

/** Registry groupId → title and summary, in AdGuard Home's order. */
private val dnsGroups =
    listOf(
        Triple(1, L10n.string.group_general, L10n.string.group_general_summary),
        Triple(2, L10n.string.other, L10n.string.group_other_dns_summary),
        Triple(3, L10n.string.group_regional, L10n.string.group_regional_summary),
        Triple(4, L10n.string.group_security, L10n.string.group_dns_security_summary),
    )

/** AdGuard browser extension groupId → title and summary, in the extension's order. */
private val browserGroups =
    listOf(
        Triple(1, L10n.string.group_ads, L10n.string.group_ads_summary),
        Triple(2, L10n.string.group_privacy, L10n.string.group_privacy_summary),
        Triple(3, L10n.string.group_social, L10n.string.group_social_summary),
        Triple(4, L10n.string.group_annoyances, L10n.string.group_annoyances_summary),
        Triple(5, L10n.string.group_security, L10n.string.group_security_summary),
        Triple(6, L10n.string.other, L10n.string.group_other_summary),
        Triple(7, L10n.string.group_language, L10n.string.group_language_summary),
    )

@Composable
fun FilterCatalogScreen(filters: FilterRepository, source: CatalogSource) {
    val strings = rememberAppStrings()
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    val scope = rememberCoroutineScope()
    val groups = if (source.browser) browserGroups else dnsGroups
    val language = strings.configuration.locales[0].language
    LaunchedEffect(attempt) {
        error = null
        runCatching { filters.catalog(source) }
            .onSuccess { catalog = it }
            .onFailure { error = it.userMessage(strings) ?: strings.getString(L10n.string.catalog_load_failed) }
    }
    val added =
        remember(lists) { lists.filter { it.browser == source.browser }.associateBy { it.url } }
    // The device language's lists come first, as the extension suggests them.
    val grouped =
        remember(catalog, language) {
            catalog.orEmpty().sortedBy { language !in it.languages }.groupBy { it.group }
        }
    fun isOn(entry: CatalogEntry) = added[entry.url]?.enabled == true
    AppLazyList {
        item(key = "catalog-status", contentType = "status") {
            error?.let {
                AppCard {
                    AppText(it)
                    if (catalog == null) AppAction(strings.getString(L10n.string.retry)) { attempt++ }
                }
            }
            val entries = catalog
            if (entries == null) {
                if (error == null) AppCard { AppHint(strings.getString(L10n.string.loading)) }
            }
        }
        groups.forEach { (id, titleRes, summaryRes) ->
            val title = strings.getString(titleRes)
            val summary = strings.getString(summaryRes)
            val group = grouped[id].orEmpty()
            if (group.isEmpty()) return@forEach
            val on = group.count(::isOn)
            item(key = "group-$id", contentType = "group") {
                AppPreferenceGroup {
                    AppArrowPreference(
                        title,
                        if (on == 0) summary else strings.getString(L10n.string.catalog_group_active, summary, on),
                    ) {
                        expanded = if (id in expanded) expanded - id else expanded + id
                    }
                }
            }
            if (id in expanded)
                items(group, key = { it.url }, contentType = { "catalog-entry" }) { entry ->
                    AppPreferenceGroup {
                        AppSwitchPreference(
                            title = entry.name,
                            summary =
                                listOfNotNull(
                                        strings.getString(L10n.string.recommended).takeIf {
                                            entry.recommended &&
                                                (entry.languages.isEmpty() ||
                                                    language in entry.languages)
                                        },
                                        entry.languages
                                            .takeIf { it.isNotEmpty() }
                                            ?.joinToString(", "),
                                    )
                                    .joinToString(" · ")
                                    .ifEmpty { null },
                            checked = isOn(entry),
                            onCheckedChange = { checked ->
                                scope.launch {
                                    runCatching {
                                        val list = added[entry.url]
                                        when {
                                            !checked -> list?.let { filters.remove(it.id) }
                                            list != null -> filters.setEnabled(list.id, true)
                                            else ->
                                                filters.addUrl(
                                                    entry.url,
                                                    entry.name,
                                                    browser = source.browser,
                                                )
                                        }
                                    }
                                        .onSuccess { error = null }
                                        .onFailure { error = it.userMessage(strings) }
                                }
                            },
                        )
                    }
                }
        }
    }
}
