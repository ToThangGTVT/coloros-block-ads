package com.utc.color.blockads.feature.home

import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.common.FilterStatus
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.data.*
import com.utc.color.blockads.core.designsystem.*

@Composable
fun HomeScreen(
    tunnel: TunnelState,
    filter: FilterStatus,
    queries: QueryLogRepository,
    onPrivateDns: () -> Unit,
    onProtection: (Boolean) -> Unit,
) {
    val strings = rememberAppStrings()
    val stats by queries.today.collectAsStateWithLifecycle()
    val week by queries.week.collectAsStateWithLifecycle()
    val top by queries.topDomains.collectAsStateWithLifecycle()
    val enabled =
        tunnel.status in
            listOf(TunnelStatus.Connected, TunnelStatus.Connecting, TunnelStatus.Reconnecting)
    val number = remember(strings.configuration.locales[0]) { java.text.NumberFormat.getIntegerInstance(strings.configuration.locales[0]) }
    AppColumn {
        AppProtectionCard(
            title =
                when (tunnel.status) {
                    TunnelStatus.Off -> strings.getString(L10n.string.vpn_off)
                    TunnelStatus.Connecting -> strings.getString(L10n.string.connecting)
                    TunnelStatus.Connected ->
                        if (filter.loaded > 0) strings.getString(L10n.string.protecting) else strings.getString(L10n.string.vpn_no_rules)
                    TunnelStatus.Reconnecting -> strings.getString(L10n.string.network_waiting)
                    TunnelStatus.Revoked -> strings.getString(L10n.string.vpn_revoked)
                    TunnelStatus.Error -> strings.getString(L10n.string.vpn_connection_failed)
                },
            checked = enabled,
            enabled = tunnel.status != TunnelStatus.Connecting,
            onChange = onProtection,
        ) {
            AppHint(
                if (filter.loading) strings.getString(L10n.string.filters_preparing)
                else strings.getQuantityString(L10n.plurals.rules_in_use, filter.loaded, number.format(filter.loaded))
            )
            tunnel.detail?.let { AppHint(it) }
            filter.error?.let { AppHint(it) }
        }
        if (tunnel.privateDns)
            AppCard {
                AppText(strings.getString(L10n.string.private_dns_on), prominent = true)
                AppHint(strings.getString(L10n.string.private_dns_hint))
                AppAction(strings.getString(L10n.string.open_settings), primary = true, onClick = onPrivateDns)
            }
        AppSectionTitle(strings.getString(L10n.string.today))
        AppCard { AppStatistics(stats.total, stats.blocked) }
        AppSectionTitle(strings.getString(L10n.string.last_seven_days))
        AppCard { AppTrafficChart(week) }
        AppSectionTitle(strings.getString(L10n.string.most_blocked))
        if (top.isEmpty()) AppCard { AppHint(strings.getString(L10n.string.none_yet)) }
        else AppDomainBreakdown(
            domains = top.map { it.domain to it.count },
            otherCount = (top.first().totalBlocked - top.sumOf { it.count }).coerceAtLeast(0),
        )
    }
}
