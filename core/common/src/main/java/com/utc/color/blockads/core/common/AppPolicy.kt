package com.utc.color.blockads.core.common

import com.utc.color.blockads.core.common.R as L10n
enum class AppFilterMode(val titleRes: Int) {
    Filter(L10n.string.mode_filter),
    BypassFiltering(L10n.string.mode_bypass_filtering),
    BypassTunnel(L10n.string.mode_bypass_tunnel),
}

data class InstalledApp(
    val packageName: String,
    val uid: Int,
    val label: String,
    val system: Boolean,
)

data class TrafficDay(val day: Long, val total: Long, val blocked: Long)

data class ResolvedAppPolicy(
    val blockedUids: Set<Int>,
    val bypassUids: Set<Int>,
    val excludedPackages: Set<String>,
)

/** Firewall wins over every bypass, including packages sharing an Android UID. */
fun resolveAppPolicy(
    apps: List<InstalledApp>,
    policies: Map<String, AppFilterMode>,
    blockedPackages: Set<String>,
): ResolvedAppPolicy {
    val groups = apps.groupBy { it.uid }
    val blocked = groups.filterValues { all -> all.any { it.packageName in blockedPackages } }.keys
    val bypass =
        groups
            .filter { (uid, all) ->
                uid !in blocked &&
                    all.any { policies[it.packageName] == AppFilterMode.BypassFiltering }
            }
            .keys
    val excluded =
        groups
            .filter { (uid, all) ->
                uid !in blocked &&
                    all.any { policies[it.packageName] == AppFilterMode.BypassTunnel }
            }
            .values
            .flatten()
            .map { it.packageName }
            .toSet()
    return ResolvedAppPolicy(blocked, bypass, excluded)
}
