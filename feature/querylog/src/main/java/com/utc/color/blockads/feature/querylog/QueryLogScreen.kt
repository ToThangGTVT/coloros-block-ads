package com.utc.color.blockads.feature.querylog

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.filter.FilterRepository
import com.utc.color.blockads.feature.querylog.databinding.ScreenQueryLogBinding
import com.utc.color.blockads.core.common.R as Strings

class QueryLogScreen private constructor(
    private val activity: ComponentActivity,
    private val views: ScreenQueryLogBinding,
    state: Bundle,
    private val queries: QueryLogRepository,
    private val filters: FilterRepository,
    private val settings: SettingsRepository,
    private val apps: AppsRepository,
    private val onAppRequests: (Int?, String) -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, queries: QueryLogRepository,
        filters: FilterRepository, settings: SettingsRepository, apps: AppsRepository, onAppRequests: (Int?, String) -> Unit,
    ) : this(
        activity, ScreenQueryLogBinding.inflate(parent.inflater, parent, false), state, queries,
        filters, settings, apps, onAppRequests,
    )

    private var tab = state.getInt("tab")
    private val tabs = Array(2) { state.getBundle("logTab$it") ?: Bundle() }
    private var content: Screen? = null

    init {
        views.logTabs.bind(listOf(context.getString(Strings.string.queries), context.getString(Strings.string.app_statistics)), tab) {
            if (tab == it) return@bind
            saveTab()
            tab = it
            render()
        }
        render()
    }

    private fun saveTab() {
        content?.let { tabs[tab] = it.saveState() }
    }

    private fun render() {
        content?.close()
        val host = views.tabContent
        host.removeAllViews()
        content = if (tab == 0) QueryHistoryScreen(activity, host, tabs[tab], queries, filters, settings, apps)
        else AppStatisticsTab(activity, host, tabs[tab], queries, apps, onAppRequests)
        content?.let {
            host.addView(it.view)
            it.restoreViews(tabs[tab])
        }
    }

    override fun saveState() = Bundle().apply {
        saveTab()
        putInt("tab", tab)
        tabs.forEachIndexed { index, state -> putBundle("logTab$index", state) }
    }

    override fun close() {
        content?.close()
        super.close()
    }
}

fun AppRequestsScreen(
    activity: ComponentActivity, parent: ViewGroup, state: Bundle, queries: QueryLogRepository,
    filters: FilterRepository, settings: SettingsRepository, apps: AppsRepository, uid: Int?,
): Screen = QueryHistoryScreen(activity, parent, state, queries, filters, settings, apps, appOnly = true, fixedUid = uid)
