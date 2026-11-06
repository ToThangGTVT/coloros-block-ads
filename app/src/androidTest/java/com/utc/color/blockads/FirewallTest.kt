package com.utc.color.blockads

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import com.utc.color.blockads.core.common.R as L10n
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FirewallTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun dns(): ByteArray =
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeShort(4321)
                out.writeShort(0x0100)
                out.writeShort(1)
                repeat(3) { out.writeShort(0) }
                "firewall.blockads.test".split('.').forEach {
                    out.writeByte(it.length)
                    out.writeBytes(it)
                }
                out.writeByte(0)
                out.writeShort(1)
                out.writeShort(1)
            }
            val query = bytes.toByteArray()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName("172.19.0.2"), 53))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            response.data.copyOf(response.length)
        }

    private fun http(): String =
        Socket().use { socket ->
            socket.soTimeout = 2000
            socket.connect(InetSocketAddress("10.0.2.2", 18080), 2000)
            socket
                .getOutputStream()
                .write(
                    "GET /small HTTP/1.1\r\nHost: allowed.test\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
            socket.getInputStream().bufferedReader().readText()
        }

    @Test
    fun firewallPersistsOverridesTunnelBypassAndRestoresNetwork() = runBlocking {
        lateinit var model: MainViewModel
        activity.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        model.trafficPolicy.awaitReady()
        val own =
            model.apps.apps
                .first { apps -> apps.any { it.packageName == targetContext.packageName } }
                .first { it.packageName == targetContext.packageName }
        val original = model.settings.exportConfiguration()
        suspend fun applied(blocked: Boolean) {
            withTimeout(5000) {
                model.trafficPolicy.applied.first {
                    it != null && (own.uid in it.blockedUids) == blocked
                }
            }
        }
        suspend fun routed(vpn: Boolean) {
            val manager = targetContext.getSystemService(ConnectivityManager::class.java)
            withTimeout(15000) {
                while (
                    model.tunnel.state.value.status != TunnelStatus.Connected ||
                        (manager
                            .getNetworkCapabilities(manager.activeNetwork)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) != vpn
                ) delay(50)
            }
        }
        try {
            model.apps.setNetworkAllowed(own, true)
            model.apps.setMode(own, AppFilterMode.Filter)
            model.filters.saveCustom("||firewall.blockads.test^")
            model.tunnel.start()
            routed(true)
            assertTrue(dns().takeLast(4).all { it == 0.toByte() })
            assertTrue(http().contains("blockads-phase1"))

            openTab("firewall")
            enterText(own.packageName)
            val row = visible(res("app_row").hasDescendant(By.text(own.packageName)))
            row.findObject(By.text(appString(L10n.string.allow_network))).click()
            applied(true)
            assertTrue(runCatching { dns() }.exceptionOrNull() is SocketTimeoutException)
            assertFalse(runCatching { http() }.getOrDefault("").contains("blockads-phase1"))
            val exported = model.settings.exportConfiguration()
            model.apps.setNetworkAllowed(own, true)
            model.settings.importConfiguration(exported)
            assertTrue(own.packageName in model.settings.blockedApps.first())
            val invalid =
                JSONObject(exported)
                    .put("blocked_apps", "[\"bad/package\"]")
                    .put("retention_days", 30)
            assertTrue(
                runCatching { model.settings.importConfiguration(invalid.toString()) }.isFailure
            )
            assertEquals(
                JSONObject(exported).getString("blocked_apps"),
                JSONObject(model.settings.exportConfiguration()).getString("blocked_apps"),
            )
            assertEquals(
                JSONObject(exported).getInt("retention_days"),
                JSONObject(model.settings.exportConfiguration()).getInt("retention_days"),
            )

            model.apps.setNetworkAllowed(own, true)
            applied(false)
            model.apps.setMode(own, AppFilterMode.BypassTunnel)
            routed(false)
            assertTrue(http().contains("blockads-phase1"))
            model.apps.setNetworkAllowed(own, false)
            applied(true)
            routed(true)
            assertFalse(runCatching { http() }.getOrDefault("").contains("blockads-phase1"))
            model.apps.setNetworkAllowed(own, true)
            applied(false)
            routed(false)
            assertEquals(
                AppFilterMode.BypassTunnel,
                model.settings.appPolicies.first()[own.packageName],
            )
            assertTrue(http().contains("blockads-phase1"))
        } finally {
            model.tunnel.stop()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Off } }
            model.filters.importConfiguration(original)
        }
    }
}
