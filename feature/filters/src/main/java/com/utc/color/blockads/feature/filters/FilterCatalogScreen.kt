package com.utc.color.blockads.feature.filters

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.utc.color.blockads.core.common.FilterList
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.designsystem.PreferenceRow
import com.utc.color.blockads.core.designsystem.RowAdapter
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.core.filter.CatalogEntry
import com.utc.color.blockads.core.filter.CatalogSource
import com.utc.color.blockads.core.filter.FilterRepository
import com.utc.color.blockads.feature.filters.databinding.ScreenCatalogBinding
import com.utc.color.blockads.core.common.R as L10n
import com.utc.color.blockads.core.designsystem.R as Design

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

class FilterCatalogScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenCatalogBinding,
    state: Bundle,
    private val filters: FilterRepository,
    private val source: CatalogSource,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, filters: FilterRepository, source: CatalogSource,
    ) : this(activity, ScreenCatalogBinding.inflate(parent.inflater, parent, false), state, filters, source)

    private val groups = if (source.browser) browserGroups else dnsGroups
    private val language = resources.configuration.locales[0].language
    private val expanded = state.getIntArray("expanded")?.toMutableSet() ?: mutableSetOf()
    private var entries: Map<Int, List<CatalogEntry>>? = null
    private var added = emptyMap<String, FilterList>()
    private val pending = mutableSetOf<String>()
    private val adapter = RowAdapter(
        key = { row: CatalogRow -> row.key },
        create = { parent ->
            val row = parent.inflater.inflate(Design.layout.preference_row, parent, false) as PreferenceRow
            row.setBackgroundResource(Design.drawable.bg_card)
            CatalogHolder(row)
        },
        bind = ::bindRow,
    )

    init {
        views.catalogList.layoutManager = LinearLayoutManager(context)
        views.catalogList.adapter = adapter
        views.retry.setOnClickListener { load() }
        observe(filters.lists) {
            added = it.filter { list -> list.browser == source.browser }.associateBy(FilterList::url)
            render()
        }
        load()
    }

    private fun load() {
        views.retry.isVisible = false
        views.error.showMessage(context.getString(L10n.string.loading))
        action(failure = {
            views.error.showMessage(it.userMessage(resources) ?: context.getString(L10n.string.catalog_load_failed))
            views.retry.isVisible = true
        }) {
            entries = filters.catalog(source).sortedBy { language !in it.languages }.groupBy { it.group }
            views.error.showMessage(null)
            render()
        }
    }

    private fun render() {
        val catalog = entries ?: return
        adapter.submitList(buildList {
            for ((id, label, description) in groups) {
                val group = catalog[id].orEmpty()
                if (group.isEmpty()) continue
                val enabled = group.count { added[it.url]?.enabled == true }
                val summary = context.getString(description)
                add(CatalogRow.Group(id, context.getString(label),
                    if (enabled == 0) summary else context.getString(L10n.string.catalog_group_active, summary, enabled),
                    id in expanded,
                ))
                if (id in expanded) group.forEach { entry ->
                    val details = listOfNotNull(
                        context.getString(L10n.string.recommended).takeIf {
                            entry.recommended && (entry.languages.isEmpty() || language in entry.languages)
                        },
                        entry.languages.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    ).joinToString(" · ")
                    add(CatalogRow.Entry(entry, details, added[entry.url]?.enabled == true, entry.url in pending))
                }
            }
        })
    }

    private fun bindRow(holder: CatalogHolder, row: CatalogRow) {
        when (row) {
            is CatalogRow.Group -> {
                holder.row.isSelected = row.expanded
                holder.row.bind(row.title, row.summary, trailingIcon = PreferenceIcon.Arrow) {
                    if (!expanded.add(row.id)) expanded.remove(row.id)
                    render()
                }
            }
            is CatalogRow.Entry -> {
                holder.row.isSelected = false
                holder.row.bind(row.entry.name, row.summary, checked = row.checked, enabled = !row.pending) {
                    toggle(row.entry, !row.checked)
                }
            }
        }
    }

    private fun toggle(entry: CatalogEntry, enabled: Boolean) {
        if (!pending.add(entry.url)) return
        render()
        action(
            success = { pending.remove(entry.url); views.error.showMessage(null); render() },
            failure = {
                pending.remove(entry.url)
                views.error.showMessage(it.userMessage(resources) ?: context.getString(L10n.string.operation_failed))
                render()
            },
        ) {
            val list = added[entry.url]
            when {
                !enabled -> list?.let { filters.remove(it.id) }
                list != null -> filters.setEnabled(list.id, true)
                else -> filters.addUrl(entry.url, entry.name, browser = source.browser)
            }
        }
    }

    override fun saveState() = super.saveState().apply { putIntArray("expanded", expanded.toIntArray()) }
}

private class CatalogHolder(val row: PreferenceRow) : RecyclerView.ViewHolder(row)

private sealed interface CatalogRow {
    val key: Any
    data class Group(val id: Int, val title: String, val summary: String, val expanded: Boolean) : CatalogRow {
        override val key get() = id
    }
    data class Entry(val entry: CatalogEntry, val summary: String, val checked: Boolean, val pending: Boolean) : CatalogRow {
        override val key get() = entry.url
    }
}
