package com.utc.color.blockads.core.common

enum class TunnelStatus {
    Off,
    Connecting,
    Connected,
    Reconnecting,
    Revoked,
    Error,
}

data class TunnelState(val status: TunnelStatus = TunnelStatus.Off, val detail: String? = null)
