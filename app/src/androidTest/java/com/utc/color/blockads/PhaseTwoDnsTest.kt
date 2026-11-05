package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.enginebridge.EngineBridge
import java.io.*
import java.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseTwoDnsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixtureHost
        get() =
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("fixtureHost") ?: "10.0.2.2"

    private var nextId = 12000

    private fun packet(domain: String, type: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeShort(++nextId)
        out.writeShort(0x0100)
        out.writeShort(1)
        repeat(3) { out.writeShort(0) }
        domain.split('.').forEach {
            out.writeByte(it.length)
            out.writeBytes(it)
        }
        out.writeByte(0)
        out.writeShort(type)
        out.writeShort(1)
        return bytes.toByteArray()
    }

    private fun query(
        domain: String,
        type: Int = 1,
        tcp: Boolean = false,
        server: String = "8.8.8.8",
    ): ByteArray {
        val request = packet(domain, type)
        return if (tcp)
            Socket().use { socket ->
                socket.soTimeout = 10000
                socket.connect(InetSocketAddress(server, 53), 10000)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeShort(request.size)
                out.write(request)
                val input = DataInputStream(socket.getInputStream())
                ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            }
        else
            DatagramSocket().use { socket ->
                socket.soTimeout = 10000
                socket.send(
                    DatagramPacket(request, request.size, InetAddress.getByName(server), 53)
                )
                val reply = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(reply)
                reply.data.copyOf(reply.length).also {
                    assertEquals(nextId, ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255))
                }
            }
    }

    @Test
    fun publicEncryptedDnsUpstreams() = runBlocking {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("externalNetworkChecks") == "true"
        )
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        val bridge = EngineBridge()
        try {
            model.tunnel.start()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Connected } }
            val failures = mutableListOf<String>()
            for ((protocol, ip, name) in
                listOf(
                    Triple("https", "1.1.1.1", "cloudflare-dns.com"),
                    Triple("tls", "1.1.1.1", "one.one.one.one"),
                    Triple("quic", "94.140.14.14", "dns.adguard-dns.com"),
                )) {
                val options =
                    JSONObject()
                        .put("protocol", protocol)
                        .put("ip", ip)
                        .put("serverName", name)
                        .put("path", "/dns-query")
                assertTrue(bridge.configureDns(options.toString()))
                val response = query("example.com")
                if (
                    (response[3].toInt() and 15) != 0 ||
                        ((response[6].toInt() and 255) shl 8 or (response[7].toInt() and 255)) == 0
                )
                    failures += protocol
            }
            assertTrue("Upstreams failed: $failures", failures.isEmpty())
        } finally {
            model.filters.refreshDns()
            model.tunnel.stop()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Off } }
        }
    }

    private fun address(response: ByteArray) =
        InetAddress.getByAddress(response.takeLast(4).toByteArray()).hostAddress

    private fun count(domain: String): Int {
        val connection =
            URL("http://$fixtureHost:18080/dns-count/$domain").openConnection() as HttpURLConnection
        connection.setRequestProperty("Connection", "close")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            connection.inputStream.bufferedReader().use { it.readText().trim().toInt() }
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun dnsBlockingHotReloadTtlAndRoomLogging() = runBlocking {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        val original = model.settings.customRules.first()
        val bridge = EngineBridge()
        val fixture =
            JSONObject()
                .put("protocol", "udp")
                .put("ip", fixtureHost)
                .put("port", 18082)
                .put("logging", true)
        try {
            model.filters.saveCustom(
                "||blocked.blockads.test^\n||allowed.blockads.test^\n@@||allowed.blockads.test^"
            )
            model.tunnel.start()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Connected } }
            assertTrue(bridge.configureDns(fixture.toString()))
            assertEquals("0.0.0.0", address(query("blocked.blockads.test")))
            assertTrue(query("blocked.blockads.test", 28).takeLast(16).all { it == 0.toByte() })
            assertEquals("0.0.0.0", address(query("blocked.blockads.test", tcp = true)))
            assertEquals(
                "0.0.0.0",
                address(query("blocked.blockads.test", server = "fdfe:dcba:9876::2")),
            )
            assertEquals("203.0.113.7", address(query("allowed.blockads.test")))
            val name = "cache.blockads.test"
            val before = count(name)
            assertEquals("203.0.113.7", address(query(name)))
            assertEquals("203.0.113.7", address(query(name)))
            assertEquals(before + 1, count(name))
            delay(2300)
            assertEquals("203.0.113.7", address(query(name)))
            assertEquals(before + 2, count(name))
            withTimeout(5000) {
                while (model.queries.latest("blocked.blockads.test")?.blocked != true) delay(100)
            }
            assertNotNull(model.queries.latest("blocked.blockads.test")?.matchedRule)
            compose.onNodeWithText(appString(L10n.string.log)).performClick()
            val queryList = hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            val blockedQuery =
                hasText("blocked.blockads.test") and hasText(appString(L10n.string.blocked), substring = true)
            compose.waitUntil(5000) {
                compose.onAllNodes(queryList and hasAnyDescendant(hasClickAction())).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(queryList).performScrollToNode(blockedQuery)
            compose.onAllNodes(blockedQuery).onFirst().performClick()
            compose.onNodeWithText(appString(L10n.string.block_action)).assertExists()
            delay(600)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(
                            "screencap -p /sdcard/Download/blockads-phase2-log.png"
                        )
                )
                .use { it.readBytes() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText(appString(L10n.string.home)).performClick()
            model.filters.saveCustom(original + "\n@@||blocked.blockads.test^")
            assertEquals("203.0.113.7", address(query("blocked.blockads.test")))
            assertFalse(runCatching { model.filters.saveCustom("||bad..domain^") }.isSuccess)
            assertEquals("203.0.113.7", address(query("blocked.blockads.test")))
            fixture.put("logging", false).put("blockMode", "nxdomain")
            assertTrue(bridge.configureDns(fixture.toString()))
            model.filters.saveCustom("||blocked.blockads.test^")
            assertEquals(3, query("blocked.blockads.test")[3].toInt() and 15)
            query("nolog.blockads.test")
            delay(800)
            assertNull(model.queries.latest("nolog.blockads.test"))
        } finally {
            model.filters.saveCustom(original)
            model.filters.refreshDns()
            model.tunnel.stop()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Off } }
        }
    }
}
