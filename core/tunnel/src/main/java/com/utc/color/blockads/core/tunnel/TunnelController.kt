package com.utc.color.blockads.core.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object TunnelRuntime {
    @Volatile var lastSocketFailure: String = "none"
    val state = MutableStateFlow(TunnelState())
}

class TunnelController(context: Context) {
    private val context = context.applicationContext
    val state = TunnelRuntime.state.asStateFlow()

    val diagnostic: String
        get() = TunnelRuntime.lastSocketFailure

    fun prepare(): Intent? = VpnService.prepare(context)

    fun start() {
        if (
            state.value.status in
                listOf(TunnelStatus.Connecting, TunnelStatus.Connected, TunnelStatus.Reconnecting)
        )
            return
        TunnelRuntime.state.value = TunnelState(TunnelStatus.Connecting)
        TunnelRuntime.lastSocketFailure = "none"
        try {
            context.startForegroundService(
                Intent(context, BlockAdsVpnService::class.java).setAction(BlockAdsVpnService.Start)
            )
        } catch (_: RuntimeException) {
            TunnelRuntime.state.value =
                TunnelState(
                    TunnelStatus.Error,
                    "Không thể khởi động dịch vụ VPN từ trạng thái hiện tại.",
                )
        }
    }

    fun stop() {
        context.startService(
            Intent(context, BlockAdsVpnService::class.java).setAction(BlockAdsVpnService.Stop)
        )
    }
}
