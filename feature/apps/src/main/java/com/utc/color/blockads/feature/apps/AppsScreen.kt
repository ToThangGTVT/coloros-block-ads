package com.utc.color.blockads.feature.apps

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.utc.color.blockads.core.common.AppFilterMode
import com.utc.color.blockads.core.common.HttpsSettings
import com.utc.color.blockads.core.common.InstalledApp
import com.utc.color.blockads.core.common.resolveAppPolicy
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.RowAdapter
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.choiceSheet
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.feature.apps.databinding.RowAppBinding
import com.utc.color.blockads.feature.apps.databinding.ScreenAppsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.utc.color.blockads.core.common.R as Strings

class AppsScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenAppsBinding,
    state: Bundle,
    private val repository: AppsRepository,
    private val settings: SettingsRepository,
    private val firewallOnly: Boolean,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, repository: AppsRepository,
        settings: SettingsRepository, firewallOnly: Boolean,
    ) : this(activity, ScreenAppsBinding.inflate(parent.inflater, parent, false), state, repository, settings, firewallOnly)

    private var showSystem = state.getBoolean("system")
    private var apps = repository.apps.value
    private var policies = repository.policies.value
    private var blocked = repository.blockedApps.value
    private var https = HttpsSettings()
    private val modes = AppFilterMode.entries.map { context.getString(it.titleRes) }
    private val adapter = RowAdapter(
        key = { row: AppRow -> row.app.packageName },
        create = { parent -> AppHolder(RowAppBinding.inflate(parent.inflater, parent, false)) },
        bind = ::bindRow,
        recycle = { it.icon?.cancel() },
    )

    init {
        views.appList.layoutManager = LinearLayoutManager(context)
        views.appList.adapter = adapter
        views.help.isVisible = firewallOnly
        views.help.setOnClickListener { showHelp(it, Strings.string.firewall_help) }
        views.search.setText(state.getString("search").orEmpty())
        views.search.doAfterTextChanged { render() }
        renderSystem()
        render()
        observe(combine(repository.apps, repository.policies, repository.blockedApps, settings.https, ::AppSettings)) {
            apps = it.apps
            policies = it.policies
            blocked = it.blocked
            https = it.https
            render()
        }
    }

    private fun renderSystem() {
        views.showSystem.root.bind(
            context.getString(Strings.string.show_system_apps), context.getString(Strings.string.network_apps_only),
            checked = showSystem,
        ) {
            showSystem = !showSystem
            renderSystem()
            render()
        }
    }

    private fun render() {
        val search = views.search.text.toString()
        val blockedUids = resolveAppPolicy(apps, policies, blocked).blockedUids
        val uidCounts = apps.groupingBy { it.uid }.eachCount()
        adapter.submitList(apps.filter {
            (showSystem || !it.system) && (it.label.contains(search, true) || it.packageName.contains(search, true))
        }.map {
            AppRow(it, it.uid in blockedUids, uidCounts.getValue(it.uid) > 1,
                it.packageName in https.apps, policies[it.packageName] ?: AppFilterMode.Filter)
        })
    }

    private fun bindRow(holder: AppHolder, row: AppRow) {
        val app = row.app
        val item = holder.views
        item.identity.root.bind(app.label, app.packageName)
        holder.icon?.cancel()
        item.identity.root.showIcon(repository.cachedIcon(app.packageName))
        holder.icon = scope.launch { item.identity.root.showIcon(repository.icon(app.packageName)) }
        item.network.root.isVisible = firewallOnly
        item.networkHint.isVisible = !firewallOnly && row.blocked
        item.https.root.isVisible = !firewallOnly
        item.policy.root.isVisible = !firewallOnly
        if (firewallOnly) {
            item.network.root.bind(
                context.getString(Strings.string.allow_network),
                context.getString(when {
                    row.shared -> Strings.string.shared_uid_policy
                    row.blocked -> Strings.string.network_blocked
                    else -> Strings.string.network_allowed
                }),
                checked = !row.blocked,
            ) { save(Strings.string.firewall_save_failed) { repository.setNetworkAllowed(app, row.blocked) } }
        } else {
            item.https.root.bind(
                context.getString(Strings.string.https_filtering), context.getString(Strings.string.ca_required),
                checked = row.https,
            ) {
                save(Strings.string.action_failed) {
                    val saved = settings.https.first()
                    val packages = if (row.https) saved.apps - app.packageName else (saved.apps + app.packageName).distinct()
                    settings.setHttps(saved.copy(apps = packages))
                }
            }
            val title = context.getString(Strings.string.app_policy_title, app.label)
            item.policy.root.bind(title, modes[row.mode.ordinal], trailingIcon = PreferenceIcon.Dropdown) {
                show(choiceSheet(context, title, modes, row.mode.ordinal) { index ->
                    save(Strings.string.app_policy_save_failed) { repository.setMode(app, AppFilterMode.entries[index]) }
                })
            }
        }
    }

    private fun save(fallback: Int, block: suspend () -> Unit) {
        action({ views.error.showMessage(null) }, {
            views.error.showMessage(it.userMessage(resources) ?: context.getString(fallback))
        }, block)
    }

    override fun saveState() = super.saveState().apply {
        putBoolean("system", showSystem)
        putString("search", views.search.text.toString())
    }
}

private class AppHolder(val views: RowAppBinding) : RecyclerView.ViewHolder(views.root) {
    var icon: Job? = null
}

private data class AppRow(
    val app: InstalledApp, val blocked: Boolean, val shared: Boolean, val https: Boolean, val mode: AppFilterMode,
)

private data class AppSettings(
    val apps: List<InstalledApp>, val policies: Map<String, AppFilterMode>, val blocked: Set<String>, val https: HttpsSettings,
)
