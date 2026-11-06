package com.utc.color.blockads.feature.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.common.FilterStatus
import com.utc.color.blockads.core.common.HttpsSettings
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.data.TopDomain
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.button
import com.utc.color.blockads.core.designsystem.card
import com.utc.color.blockads.core.designsystem.dp
import com.utc.color.blockads.core.designsystem.hint
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.section
import com.utc.color.blockads.feature.home.databinding.DomainProgressBinding
import com.utc.color.blockads.feature.home.databinding.ScreenHomeBinding
import java.text.NumberFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.utc.color.blockads.core.common.R as Strings

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenHomeBinding,
    state: Bundle,
    tunnel: StateFlow<TunnelState>,
    filter: StateFlow<FilterStatus>,
    queries: QueryLogRepository,
    settings: SettingsRepository,
    private val certificates: CertificateRepository,
    private val onProtection: (Boolean) -> Unit,
    private val onCertificate: () -> Unit,
    onSettings: () -> Unit,
    private val onPrivateDns: () -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle,
        tunnel: StateFlow<TunnelState>, filter: StateFlow<FilterStatus>, queries: QueryLogRepository,
        settings: SettingsRepository, certificates: CertificateRepository, onProtection: (Boolean) -> Unit,
        onCertificate: () -> Unit, onSettings: () -> Unit, onPrivateDns: () -> Unit,
    ) : this(
        activity, ScreenHomeBinding.inflate(parent.inflater, parent, false), state, tunnel, filter,
        queries, settings, certificates, onProtection, onCertificate, onSettings, onPrivateDns,
    )

    private val behavior = BottomSheetBehavior.from(views.homePanel)
    private val number = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
    private val percent = NumberFormat.getPercentInstance(resources.configuration.locales[0]).apply {
        maximumFractionDigits = 1
    }
    private val topLimit = MutableStateFlow(state.getInt("topLimit", PAGE).coerceAtLeast(PAGE))
    private var selected = state.getInt("tab")
    private var expanded = state.getBoolean("expanded")
    private var status = tunnel.value
    private var filters = filter.value
    private var https = HttpsSettings()
    private var busy = false
    private var error: String? = null

    init {
        views.homeSettings.setOnClickListener { onSettings() }
        views.blockedMetric.label.setText(Strings.string.home_blocked_today)
        views.totalMetric.label.setText(Strings.string.total_requests)
        views.homeTabs.bind(
            listOf(context.getString(Strings.string.home_actions), context.getString(Strings.string.home_statistics)),
            selected,
        ) {
            selected = it
            views.homePanelContent.scrollTo(0, 0)
            renderTab()
            if (selected == 1) behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        renderTab()
        behavior.isFitToContents = false
        behavior.halfExpandedRatio = 0.8f
        behavior.state = if (expanded) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.STATE_COLLAPSED -> {
                        expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                        labelHandle()
                    }
                }
            }
        })
        views.panelHandle.setOnClickListener {
            behavior.state = if (expanded) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
        }
        labelHandle()
        fun resize() {
            val height = views.root.height
            if (height == 0) return
            val minPeek = context.dp(220).coerceAtMost(height)
            behavior.peekHeight = (height - views.hero.height).coerceIn(minPeek, height)
            behavior.expandedOffset = (views.hero.paddingTop + context.dp(48)).coerceAtMost(height - behavior.peekHeight)
            // The sheet keeps the parent's height, so its last expandedOffset pixels sit below the screen.
            views.homePanelContent.updatePadding(bottom = behavior.expandedOffset)
        }
        views.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
        views.hero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
        ViewCompat.setOnApplyWindowInsetsListener(views.hero) { hero, insets ->
            hero.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        renderProtection()
        observe(tunnel) { status = it; renderProtection() }
        observe(filter) { filters = it; renderProtection() }
        observe(settings.https) { https = it; renderProtection() }
        observe(certificates.state) { renderProtection() }
        observe(queries.today) {
            views.blockedMetric.value.text = number.format(it.blocked)
            views.totalMetric.value.text = number.format(it.total)
        }
        observe(queries.week) {
            views.loadingStatistics.isVisible = it.isEmpty()
            views.trafficChart.isVisible = it.isNotEmpty()
            views.trafficChart.render(it)
        }
        views.showMoreDomains.setOnClickListener { topLimit.value += PAGE }
        // One extra row tells whether another page exists.
        observe(topLimit.flatMapLatest { limit -> queries.topDomains(limit + 1).map { limit to it } }) { (limit, rows) ->
            renderTopDomains(rows.take(limit), hasMore = rows.size > limit)
        }
    }

    private fun renderTopDomains(top: List<TopDomain>, hasMore: Boolean) {
        val list = views.domainBreakdown
        views.showMoreDomains.isVisible = hasMore
        if (top.isEmpty()) {
            list.removeAllViews()
            list.hint(context.getString(Strings.string.none_yet))
            return
        }
        if (list.isNotEmpty() && list.getChildAt(0).tag !is DomainProgressBinding) list.removeAllViews()
        while (list.childCount > top.size) list.removeViewAt(list.childCount - 1)
        while (list.childCount < top.size) {
            DomainProgressBinding.inflate(list.inflater, list, true).also { it.root.tag = it }
        }
        val max = top.first().count.coerceAtLeast(1)
        top.forEachIndexed { index, item ->
            val row = list.getChildAt(index).tag as DomainProgressBinding
            val count = number.format(item.count)
            val share = percent.format(item.count.toDouble() / item.totalBlocked.coerceAtLeast(item.count))
            row.domain.text = item.domain
            row.count.text = context.getString(Strings.string.domain_blocked_share, count, share)
            row.progress.progress = (1000.0 * item.count / max).toInt()
            row.root.contentDescription =
                context.getString(Strings.string.domain_blocked_description, item.domain, count, share)
        }
    }

    private fun labelHandle() {
        views.panelHandle.contentDescription = context.getString(
            if (expanded) Strings.string.collapse_dashboard else Strings.string.expand_dashboard,
        )
    }

    private fun renderTab() {
        views.actions.isVisible = selected == 0
        views.statistics.isVisible = selected == 1
    }

    private fun renderProtection() {
        val checked = status.status in running
        val canToggle = status.status != TunnelStatus.Connecting
        views.shieldHalo.isVisible = status.status == TunnelStatus.Off
        views.shieldLabel.setText(
            when (status.status) {
                TunnelStatus.Off -> Strings.string.home_protection_off
                TunnelStatus.Connecting -> Strings.string.connecting
                TunnelStatus.Connected -> if (filters.loaded > 0) Strings.string.protecting else Strings.string.vpn_no_rules
                TunnelStatus.Reconnecting -> Strings.string.network_waiting
                TunnelStatus.Revoked -> Strings.string.vpn_revoked
                TunnelStatus.Error -> Strings.string.vpn_connection_failed
            },
        )
        views.shield.isSelected = checked
        views.shield.isEnabled = canToggle
        views.shield.setOnClickListener { onProtection(!checked) }
        views.rules.text = if (filters.loading) context.getString(Strings.string.filters_preparing) else
            resources.getQuantityString(Strings.plurals.rules_in_use, filters.loaded, number.format(filters.loaded))
        views.dnsProtection.root.bind(
            context.getString(Strings.string.home_dns_protection), context.getString(Strings.string.home_dns_description),
            checked = checked, enabled = canToggle,
        ) { onProtection(!checked) }
        val httpsOn = https.enabled && certificates.state.value.installed && checked && canToggle
        views.httpsProtection.root.bind(
            context.getString(Strings.string.home_https_protection), context.getString(Strings.string.home_https_description),
            checked = httpsOn, enabled = !busy && canToggle,
        ) { setHttps(!httpsOn) }

        views.notices.removeAllViews()
        listOfNotNull(error, status.detail, filters.error).forEach { views.notices.card().hint(it) }
        if (status.privateDns) views.notices.card().apply {
            section(context.getString(Strings.string.private_dns_on))
            hint(context.getString(Strings.string.private_dns_hint))
            button(context.getString(Strings.string.open_settings), onPrivateDns)
        }
    }

    private fun setHttps(enabled: Boolean) {
        busy = true
        error = null
        renderProtection()
        action(
            success = { busy = false; renderProtection() },
            failure = {
                busy = false
                error = it.userMessage(resources) ?: context.getString(Strings.string.action_failed)
                renderProtection()
            },
        ) {
            certificates.refresh()
            val ca = certificates.state.value
            if (enabled && (!ca.created || !ca.installed || https.apps.isEmpty())) {
                onCertificate()
            } else {
                certificates.setEnabled(enabled)
                if (enabled && status.status !in running) onProtection(true)
            }
        }
    }

    override fun saveState() = super.saveState().apply {
        putInt("tab", selected)
        putBoolean("expanded", expanded)
        putInt("topLimit", topLimit.value)
    }

    private companion object {
        const val PAGE = 5
        val running = setOf(TunnelStatus.Connected, TunnelStatus.Connecting, TunnelStatus.Reconnecting)
    }
}
