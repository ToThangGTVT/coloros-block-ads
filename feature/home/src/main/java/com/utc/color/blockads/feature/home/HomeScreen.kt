package com.utc.color.blockads.feature.home

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.common.FilterStatus
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.designsystem.*

@Composable
fun HomeScreen(
    engineVersion: String,
    tunnel: TunnelState,
    filter: FilterStatus,
    onProtection: (Boolean) -> Unit,
) {
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
                        if (filter.loaded > 0) "Đang bảo vệ DNS" else "VPN đang bật · Chưa có rule"
                    TunnelStatus.Reconnecting -> "Đang chờ kết nối mạng…"
                    TunnelStatus.Revoked -> "Quyền VPN đã bị thu hồi"
                    TunnelStatus.Error -> "Không thể kết nối VPN"
                },
                prominent = true,
            )
            AppSwitch(
                checked = enabled,
                onCheckedChange = onProtection,
                enabled = tunnel.status != TunnelStatus.Connecting,
            )
            tunnel.detail?.let { AppText(it) }
            AppText("Phase 2 · Lọc DNS. Chưa lọc SNI/HTTP hoặc giải mã HTTPS.")
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
