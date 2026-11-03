package com.utc.color.blockads.core.common

enum class AppFilterMode(val title: String) {
    Filter("Lọc"),
    BypassFiltering("Bỏ qua lọc"),
    BypassTunnel("Bỏ qua tunnel"),
}

data class InstalledApp(
    val packageName: String,
    val uid: Int,
    val label: String,
    val system: Boolean,
)

data class TrafficDay(val day: Long, val total: Long, val blocked: Long)
