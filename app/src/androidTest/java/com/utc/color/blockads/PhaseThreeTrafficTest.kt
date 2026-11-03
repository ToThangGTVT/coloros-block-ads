package com.utc.color.blockads

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.enginebridge.EngineBridge
import java.net.*
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseThreeTrafficTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val fixtureHost
        get() =
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("fixtureHost") ?: "10.0.2.2"

    private fun http(host: String): String =
        Socket().use { socket ->
            socket.soTimeout = 6000
            socket.connect(InetSocketAddress(fixtureHost, 18080), 6000)
            socket.getOutputStream().write("GET /small HTTP/1.1\r\nHo".toByteArray())
            Thread.sleep(25)
            socket.getOutputStream().write("st: $host\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }

    private fun tlsCount(): Int {
        val c = URL("http://$fixtureHost:18080/tls-count").openConnection() as HttpURLConnection
        c.setRequestProperty("Connection", "close")
        c.connectTimeout = 5000
        c.readTimeout = 5000
        return try {
            c.inputStream.bufferedReader().use { it.readText().toInt() }
        } finally {
            c.disconnect()
        }
    }

    private fun tls() {
        val raw = Socket()
        raw.connect(InetSocketAddress(fixtureHost, 18083), 5000)
        (SSLSocketFactory.getDefault()
                .let { it as SSLSocketFactory }
                .createSocket(raw, "sni.blockads.test", 18083, true) as SSLSocket)
            .use { socket ->
                socket.soTimeout = 6000
                socket.sslParameters =
                    socket.sslParameters.apply {
                        serverNames = listOf(SNIHostName("sni.blockads.test"))
                    }
                runCatching { socket.startHandshake() }
            }
    }

    @Test
    fun sniHttpAttributionAndAppPoliciesUseActualAndroidUid() = runBlocking {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        model.trafficPolicy.awaitReady()
        val own =
            withTimeout(5000) {
                    model.apps.apps.first {
                        it.any { app -> app.packageName == compose.activity.packageName }
                    }
                }
                .first { it.packageName == compose.activity.packageName }
        val custom = model.settings.customRules.first()
        val dns = model.settings.dns.first()
        model.settings.setDns(dns.copy(logging = true))
        model.filters.refreshDns()
        val policies = model.settings.appPolicies.first()
        val originalMode = policies[own.packageName] ?: AppFilterMode.Filter
        suspend fun mode(value: AppFilterMode) {
            model.apps.setMode(own, value)
            withTimeout(5000) {
                model.trafficPolicy.applied.first {
                    (it?.policies?.get(own.packageName) ?: AppFilterMode.Filter) == value
                }
            }
        }
        try {
            android.util.Log.i("BlockAdsPhase3", "start filter")
            mode(AppFilterMode.Filter)
            model.filters.saveCustom(
                "||http.blockads.test^\$app=${own.packageName}\n||sni.blockads.test^\$app=${own.packageName}"
            )
            model.tunnel.start()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Connected } }
            delay(600) // Let initial physical-network capability callbacks settle on slow AVDs.
            assertTrue(http("http.blockads.test").startsWith("HTTP/1.1 204"))
            assertTrue(http("allowed.blockads.test").contains("blockads-phase1"))
            android.util.Log.i("BlockAdsPhase3", "http passed")
            val beforeTls = tlsCount()
            tls()
            assertEquals(beforeTls, tlsCount())
            android.util.Log.i("BlockAdsPhase3", "tls rejected before upstream")
            withTimeout(5000) {
                while (model.queries.latest("sni.blockads.test") == null) delay(100)
            }
            val row = model.queries.latest("sni.blockads.test")!!
            assertTrue(row.blocked)
            assertEquals(android.os.Process.myUid(), row.uid)
            assertEquals("tls", row.protocol)
            assertTrue(
                model.queries.latest("http.blockads.test")!!.matchedRule!!.contains("\$app=")
            )
            android.util.Log.i("BlockAdsPhase3", "attribution passed")
            mode(AppFilterMode.BypassFiltering)
            val nativeBefore = EngineBridge().counters()[2]
            assertTrue(http("http.blockads.test").contains("blockads-phase1"))
            assertTrue(EngineBridge().counters()[2] > nativeBefore)
            android.util.Log.i("BlockAdsPhase3", "bypass filtering passed")
            mode(AppFilterMode.BypassTunnel)
            // The VPN remains available to other apps, while this app leaves its Network.
            withTimeout(15000) {
                val manager =
                    compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
                while (
                    manager
                        .getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true ||
                        model.tunnel.state.value.status != TunnelStatus.Connected
                ) delay(100)
            }
            assertTrue(http("http.blockads.test").contains("blockads-phase1"))
            mode(AppFilterMode.Filter)
            withTimeout(15000) {
                val manager =
                    compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
                while (
                    manager
                        .getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true ||
                        model.tunnel.state.value.status != TunnelStatus.Connected
                ) delay(100)
            }
            assertTrue(http("http.blockads.test").startsWith("HTTP/1.1 204"))
            compose.onNodeWithText("Cài đặt").performClick()
            compose
                .onNodeWithText("Ứng dụng", useUnmergedTree = true)
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("Tìm ứng dụng").performTextInput("BlockAds")
            compose.onNodeWithText("Chính sách · BlockAds").assertExists()
        } finally {
            model.tunnel.stop()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Off } }
            mode(originalMode)
            model.filters.saveCustom(custom)
            model.settings.setDns(dns)
            model.filters.refreshDns()
        }
        Unit
    }
}
