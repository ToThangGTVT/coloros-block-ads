package com.utc.color.blockads.feature.querylog

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.utc.color.blockads.core.common.InstalledApp
import com.utc.color.blockads.core.data.AppQueryStats
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.designsystem.RowAdapter
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.choiceSheet
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.feature.querylog.databinding.RowAppStatisticsBinding
import com.utc.color.blockads.feature.querylog.databinding.ScreenAppStatisticsBinding
import java.text.NumberFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import com.utc.color.blockads.core.common.R as Strings

@OptIn(ExperimentalCoroutinesApi::class)
internal class AppStatisticsTab private constructor(
    activity: ComponentActivity,
    private val views: ScreenAppStatisticsBinding,
    state: Bundle,
    queries: QueryLogRepository,
    private val apps: AppsRepository,
    private val onAppRequests: (Int?, String) -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, queries: QueryLogRepository,
        apps: AppsRepository, onAppRequests: (Int?, String) -> Unit,
    ) : this(
        activity, ScreenAppStatisticsBinding.inflate(parent.inflater, parent, false), state, queries, apps, onAppRequests,
    )

    private val period = MutableStateFlow(state.getInt("period"))
    private var sort = state.getInt("sort")
    private var stats: List<AppQueryStats>? = null
    private var appsByUid = apps.apps.value.groupBy { it.uid }
    private val locale = resources.configuration.locales[0]
    private val number = NumberFormat.getIntegerInstance(locale)
    private val percent = NumberFormat.getPercentInstance(locale)
    private val adapter = RowAdapter(
        key = { row: RankedApp -> row.stats.uid },
        create = { parent -> StatsHolder(RowAppStatisticsBinding.inflate(parent.inflater, parent, false)) },
        bind = ::bindRow,
        recycle = { it.icon?.cancel() },
    )

    init {
        views.appStatisticsList.layoutManager = LinearLayoutManager(context)
        views.appStatisticsList.adapter = adapter
        renderControls()
        render()
        observe(period.flatMapLatest { queries.appStatistics(if (it == 0) 24 else 168) }) {
            stats = it
            render()
        }
        observe(apps.apps) {
            appsByUid = it.groupBy { app -> app.uid }
            render()
        }
    }

    private fun renderControls() {
        val range = context.getString(Strings.string.time_range)
        val periods = listOf(context.getString(Strings.string.last_day), context.getString(Strings.string.seven_days))
        views.period.root.bind(range, periods[period.value], trailingIcon = PreferenceIcon.Dropdown) {
            show(choiceSheet(context, range, periods, period.value) {
                if (period.value != it) {
                    stats = null
                    period.value = it
                    renderControls()
                    render()
                }
            })
        }
        val sortBy = context.getString(Strings.string.sort_by)
        val sorts = listOf(context.getString(Strings.string.most_requests), context.getString(Strings.string.most_blocks))
        views.sort.root.bind(sortBy, sorts[sort], trailingIcon = PreferenceIcon.Dropdown) {
            show(choiceSheet(context, sortBy, sorts, sort) {
                sort = it
                renderControls()
                render()
            })
        }
    }

    private fun render() {
        views.status.showMessage(when {
            stats == null -> context.getString(Strings.string.statistics_loading)
            stats.isNullOrEmpty() -> context.getString(Strings.string.logs_period_empty)
            else -> null
        })
        adapter.submitList(stats.orEmpty().sortedWith(
            compareByDescending<AppQueryStats> { if (sort == 0) it.total else it.blocked }
                .thenByDescending { it.total }.thenBy { it.uid ?: -1 },
        ).map { RankedApp(it, appsByUid[it.uid].orEmpty()) })
    }

    private fun bindRow(holder: StatsHolder, row: RankedApp) {
        val (stats, matches) = row
        val label = matches.joinToString { it.label }.ifBlank {
            stats.uid?.let { "UID $it" } ?: context.getString(Strings.string.unknown)
        }
        val packageName = matches.firstOrNull()?.packageName
        val binding = holder.views
        val open = { onAppRequests(stats.uid, label) }
        binding.identity.root.bind(label, action = open)
        binding.root.setOnClickListener { open() }
        holder.icon?.cancel()
        binding.identity.root.showIcon(packageName?.let(apps::cachedIcon))
        if (packageName != null) holder.icon = scope.launch { binding.identity.root.showIcon(apps.icon(packageName)) }
        val ratio = if (stats.total == 0L) 0.0 else (stats.blocked.toDouble() / stats.total).coerceIn(0.0, 1.0)
        binding.blockedProgress.progress = (ratio * 1000).toInt()
        binding.total.text = resources.getQuantityString(
            Strings.plurals.requests_count, if (stats.total == 1L) 1 else 2, number.format(stats.total),
        )
        binding.blocked.text = context.getString(Strings.string.blocked_count_percent, number.format(stats.blocked), percent.format(ratio))
    }

    override fun saveState() = super.saveState().apply {
        putInt("period", period.value)
        putInt("sort", sort)
    }
}

private class StatsHolder(val views: RowAppStatisticsBinding) : RecyclerView.ViewHolder(views.root) {
    var icon: Job? = null
}

private data class RankedApp(val stats: AppQueryStats, val matches: List<InstalledApp>)
