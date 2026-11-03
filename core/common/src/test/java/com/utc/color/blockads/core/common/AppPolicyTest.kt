package com.utc.color.blockads.core.common

import org.junit.Assert.*
import org.junit.Test

class AppPolicyTest {
    private val apps =
        listOf(
            InstalledApp("app.first", 10001, "First", false),
            InstalledApp("app.shared", 10001, "Shared", true),
            InstalledApp("app.other", 10002, "Other", false),
        )

    @Test
    fun firewallOverridesBothBypassesAcrossSharedUidAndRestoresThemWhenAllowed() {
        val modes =
            mapOf(
                "app.first" to AppFilterMode.BypassFiltering,
                "app.shared" to AppFilterMode.BypassTunnel,
                "app.other" to AppFilterMode.BypassTunnel,
            )
        val blocked = resolveAppPolicy(apps, modes, setOf("app.first", "uninstalled.app"))
        assertEquals(setOf(10001), blocked.blockedUids)
        assertTrue(blocked.bypassUids.isEmpty())
        assertEquals(setOf("app.other"), blocked.excludedPackages)

        val allowed = resolveAppPolicy(apps, modes, emptySet())
        assertTrue(allowed.blockedUids.isEmpty())
        assertEquals(setOf(10001), allowed.bypassUids)
        assertEquals(apps.map { it.packageName }.toSet(), allowed.excludedPackages)
    }

    @Test
    fun defaultKeepsEveryAppInsideFilteringTunnelWithNetworkAllowed() {
        val policy = resolveAppPolicy(apps, emptyMap(), emptySet())
        assertEquals(ResolvedAppPolicy(emptySet(), emptySet(), emptySet()), policy)
    }
}
