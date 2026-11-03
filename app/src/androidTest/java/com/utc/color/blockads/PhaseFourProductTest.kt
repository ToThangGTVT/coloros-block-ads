package com.utc.color.blockads

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.utc.color.blockads.core.common.*
import java.io.*
import java.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhaseFourProductTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun configurationIsValidatedAtomicallyAndStatsWorkWithLoggingDisabled() = runBlocking {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[MainViewModel::class.java]
        }
        model.filters.awaitReady()
        val original = model.settings.exportConfiguration()
        try {
            val invalid = JSONObject(original).put("custom_rules", "||bad..domain^").toString()
            assertFalse(runCatching { model.filters.importConfiguration(invalid) }.isSuccess)
            assertEquals(
                JSONObject(original).optString("custom_rules"),
                JSONObject(model.settings.exportConfiguration()).optString("custom_rules"),
            )
            val badDns =
                JSONObject(original)
                    .put("dns_upstream", "Custom")
                    .put("custom_dns_url", "http://insecure.test/dns-query")
                    .put("custom_dns_ip", "1.1.1.1")
            assertFalse(
                runCatching { model.filters.importConfiguration(badDns.toString()) }.isSuccess
            )
            model.settings.setTheme(ThemeMode.Dark)
            model.settings.setLogRetentionDays(1)
            val exported = model.settings.exportConfiguration()
            assertFalse(exported.contains("matchedRule"))
            model.settings.setTheme(ThemeMode.Light)
            model.filters.importConfiguration(exported)
            assertEquals(ThemeMode.Dark, model.settings.theme.first())
            assertEquals(1, model.settings.logRetentionDays.first())
            assertEquals(7, model.queries.week.first().size)
            val dns = model.settings.dns.first()
            model.settings.setDns(dns.copy(logging = false))
            model.filters.refreshDns()
            model.filters.saveCustom("||stats.blockads.test^")
            val before = model.queries.today.first().blocked
            model.tunnel.start()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Connected } }
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeShort(1234)
                out.writeShort(0x0100)
                out.writeShort(1)
                repeat(3) { out.writeShort(0) }
                "stats.blockads.test".split('.').forEach {
                    out.writeByte(it.length)
                    out.writeBytes(it)
                }
                out.writeByte(0)
                out.writeShort(1)
                out.writeShort(1)
            }
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                val packet = bytes.toByteArray()
                socket.send(
                    DatagramPacket(packet, packet.size, InetAddress.getByName("8.8.8.8"), 53)
                )
                val response = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(response)
                assertTrue(
                    response.data.copyOfRange(response.length - 4, response.length).all {
                        it == 0.toByte()
                    }
                )
            }
            withTimeout(5000) { model.queries.today.first { it.blocked > before } }
            assertNull(model.queries.latest("stats.blockads.test"))
            val work =
                withContext(Dispatchers.IO) {
                    androidx.work.WorkManager.getInstance(compose.activity)
                        .getWorkInfosForUniqueWork("filter-maintenance")
                        .get()
                }
            assertTrue(work.isNotEmpty())
        } finally {
            model.tunnel.stop()
            withTimeout(15000) { model.tunnel.state.first { it.status == TunnelStatus.Off } }
            model.filters.importConfiguration(original)
        }
    }
}
