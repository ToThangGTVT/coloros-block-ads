package com.utc.color.blockads.feature.home

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
    val stats by queries.today.collectAsStateWithLifecycle()
    val week by queries.week.collectAsStateWithLifecycle()
    val top by queries.topDomains.collectAsStateWithLifecycle()
    val enabled =
        tunnel.status in
            listOf(TunnelStatus.Connected, TunnelStatus.Connecting, TunnelStatus.Reconnecting)
    val number = remember { java.text.NumberFormat.getIntegerInstance() }
    AppColumn {
        AppProtectionCard(
            title =
                when (tunnel.status) {
                    TunnelStatus.Off -> "VPN đang tắt"
                    TunnelStatus.Connecting -> "Đang kết nối…"
                    TunnelStatus.Connected ->
                        if (filter.loaded > 0) "Đang bảo vệ" else "VPN đang bật · Chưa có rule"
                    TunnelStatus.Reconnecting -> "Đang chờ kết nối mạng…"
                    TunnelStatus.Revoked -> "Quyền VPN đã bị thu hồi"
                    TunnelStatus.Error -> "Không thể kết nối VPN"
                },
            checked = enabled,
            enabled = tunnel.status != TunnelStatus.Connecting,
            onChange = onProtection,
        ) {
            AppHint(
                if (filter.loading) "Đang nạp bộ lọc…"
                else "${number.format(filter.loaded)} rule đang dùng"
            )
            tunnel.detail?.let { AppHint(it) }
            filter.error?.let { AppHint(it) }
        }
        if (tunnel.privateDns)
            AppCard {
                AppText("Private DNS đang bật", prominent = true)
                AppHint("Có thể vượt qua bộ lọc DNS. Nên tắt để chặn đầy đủ.")
                AppAction("Mở cài đặt", primary = true, onClick = onPrivateDns)
            }
        AppSectionTitle("Hôm nay")
        AppCard { AppStatistics(stats.total, stats.blocked) }
        AppSectionTitle("7 ngày gần đây")
        AppCard { AppTrafficChart(week) }
        AppSectionTitle("Bị chặn nhiều nhất")
        if (top.isEmpty()) AppCard { AppHint("Chưa có") }
        else AppDomainBreakdown(
            domains = top.map { it.domain to it.count },
            otherCount = (top.first().totalBlocked - top.sumOf { it.count }).coerceAtLeast(0),
        )
    }
}
