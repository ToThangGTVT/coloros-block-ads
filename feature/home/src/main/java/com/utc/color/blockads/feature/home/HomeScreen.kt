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
    engineVersion: String,
    tunnel: TunnelState,
    filter: FilterStatus,
    queries: QueryLogRepository,
    onPrivateDns: () -> Unit,
    onProtection: (Boolean) -> Unit,
) {
    val stats by queries.today.collectAsStateWithLifecycle(initialValue = DailyStats(0))
    val week by queries.week.collectAsStateWithLifecycle(initialValue = emptyList())
    val top by queries.topDomains.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabled =
        tunnel.status in
            listOf(TunnelStatus.Connected, TunnelStatus.Connecting, TunnelStatus.Reconnecting)
    AppColumn {
        AppCard {
            AppText("BlockAds", prominent = true)
            AppText(
                when (tunnel.status) {
                    TunnelStatus.Off -> "VPN đang tắt"
                    TunnelStatus.Connecting -> "Đang kết nối…"
                    TunnelStatus.Connected ->
                        if (filter.loaded > 0) "Đang bảo vệ" else "VPN đang bật · Chưa có rule"
                    TunnelStatus.Reconnecting -> "Đang chờ kết nối mạng…"
                    TunnelStatus.Revoked -> "Quyền VPN đã bị thu hồi"
                    TunnelStatus.Error -> "Không thể kết nối VPN"
                },
                prominent = true,
            )
            AppProtectionSwitch(
                checked = enabled,
                onChange = onProtection,
                enabled = tunnel.status != TunnelStatus.Connecting,
            )
            tunnel.detail?.let { AppText(it) }
            AppText(
                "Lọc DNS, SNI/HTTP và theo ứng dụng. Lọc HTTPS là tùy chọn riêng tại màn Chứng chỉ."
            )
        }
        if (tunnel.privateDns)
            AppCard {
                AppText("Private DNS đang bật", prominent = true)
                AppText(
                    "DNS mã hóa của hệ thống có thể đi ngoài bộ lọc DNS. Mở cài đặt mạng, chọn DNS riêng tư rồi tắt nếu muốn lọc DNS của app này."
                )
                AppAction("Mở cài đặt DNS riêng tư", onPrivateDns)
            }
        AppCard {
            AppText("Hôm nay", prominent = true)
            AppStatistics(stats.total, stats.blocked)
            AppText("7 ngày gần đây")
            AppTrafficChart(week)
            AppText("Tính truy vấn DNS và kết nối TCP/QUIC được xét chính sách.")
        }
        AppCard {
            AppText("Top domain bị chặn trong log", prominent = true)
            if (top.isEmpty()) AppText("Chưa có domain bị chặn hôm nay.")
            top.forEach { item ->
                androidx.compose.runtime.key(item.domain) {
                    AppText("${item.domain} · ${item.count}")
                }
            }
        }
        AppCard {
            AppText("${filter.loaded} rule đang hoạt động", prominent = true)
            AppText(
                if (filter.loading) "Đang nạp bộ lọc…" else "Cache DNS tôn trọng TTL của upstream."
            )
            filter.error?.let { AppText(it) }
        }
        AppCard {
            AppText("Quyền riêng tư", prominent = true)
            AppText(
                "Android 17+: cần quyền mạng cục bộ để chuyển tiếp kết nối LAN. Nếu từ chối, kết nối Internet vẫn dùng được."
            )
            AppText(
                "Lưu lượng đi trực tiếp qua mạng của thiết bị. Không có máy chủ VPN trung gian và không ghi nội dung gói tin."
            )
        }
        AppCard {
            AppText("Native engine", prominent = true)
            AppText("Rust JNI · $engineVersion")
        }
    }
}
