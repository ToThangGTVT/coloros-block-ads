package com.utc.color.blockads

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.enginebridge.EngineBridge
import com.utc.color.blockads.core.tunnel.TunnelController
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only: scripts/phase1-test-server.py on the host; VPN app-op granted by test runner. */
@RunWith(AndroidJUnit4::class)
class TunnelLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller = TunnelController(context)
    private val engine = EngineBridge()

    private suspend fun await(status: TunnelStatus) =
        withTimeout(15_000) {
            controller.state
                .first { it.status == status || it.status == TunnelStatus.Error }
                .also {
                    assertEquals(it.detail, status, it.status)
                }
        }

    private fun http(path: String): ByteArray {
        val connection = URL("http://10.0.2.2:18080/$path").openConnection() as HttpURLConnection
        connection.setRequestProperty("Connection", "close")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        return try {
            connection.inputStream.use { it.readBytes() }
        } catch (error: Exception) {
            throw AssertionError(
                "Tunnel HTTP failure: state=${controller.state.value}, counters=${engine.counters().toList()}, protect=${controller.diagnostic}",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun udp() {
        DatagramSocket().use { socket ->
            socket.soTimeout = 8000
            val data = "udp-through-tunnel".toByteArray()
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("10.0.2.2"), 18081))
            val response = DatagramPacket(ByteArray(256), 256)
            socket.receive(response)
            assertArrayEquals(data, response.data.copyOf(response.length))
        }
    }

    private fun dns(server: String = "172.19.0.2") {
        DatagramSocket().use { socket ->
            socket.soTimeout = 8000
            // A IN example.com, recursion desired; query bytes are fixture data, never logged.
            val query =
                byteArrayOf(
                    0x12,
                    0x34,
                    1,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    7,
                    101,
                    120,
                    97,
                    109,
                    112,
                    108,
                    101,
                    3,
                    99,
                    111,
                    109,
                    0,
                    0,
                    1,
                    0,
                    1,
                )
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            assertTrue(response.length >= 12)
            assertEquals(0x12, response.data[0].toInt() and 255)
            assertEquals(0x34, response.data[1].toInt() and 255)
            assertTrue((response.data[2].toInt() and 0x80) != 0)
            assertEquals(0, response.data[3].toInt() and 0x0f)
        }
    }

    private fun socketInfo(fd: String): String =
        try {
            android.os.ParcelFileDescriptor.fromFd(fd.toInt()).use {
                "${android.system.Os.getsockname(it.fileDescriptor)} -> ${runCatching { android.system.Os.getpeername(it.fileDescriptor) }.getOrNull()}"
            }
        } catch (error: Exception) {
            error.javaClass.simpleName
        }

    private fun fdLinks(): Map<String, String> =
        File("/proc/self/fd")
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                try {
                    file.name to android.system.Os.readlink(file.path)
                } catch (_: Exception) {
                    null
                }
            }
            .toMap()

    @Test
    fun fullTunnelTrafficAndTwentyStartStopCyclesDoNotLeakDescriptors() = runBlocking {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        assertNull("Grant ACTIVATE_VPN to this emulator before running", controller.prepare())
        assertEquals("Fixture unreachable before VPN", "blockads-phase1", String(http("small")))
        try {
            controller.start()
            await(TunnelStatus.Connected)
            assertEquals("blockads-phase1", String(http("small")))
            assertEquals(1024 * 1024, http("bytes").size)
            udp()
            dns()
            dns("fdfe:dcba:9876::2")
            val counts = engine.counters()
            assertTrue("Packets must enter the TUN", counts[0] > 0)
            assertTrue("Packets must return through the TUN", counts[1] > 0)
            assertTrue("TCP relay was bypassed", counts[2] > 0)
            assertTrue("UDP relay was bypassed", counts[3] > 0)
            assertEquals("Socket protection must succeed", 0L, counts[4])
            println("Before migration counters=${counts.toList()}")
            val resets = engine.counters()[7]
            shell("svc wifi disable")
            withTimeout(20_000) { while (engine.counters()[7] <= resets) delay(100) }
            await(TunnelStatus.Connected)
            assertEquals("blockads-phase1", String(http("small")))
            udp()
            val mobileResets = engine.counters()[7]
            shell("svc wifi enable")
            withTimeout(20_000) { while (engine.counters()[7] <= mobileResets) delay(100) }
            await(TunnelStatus.Connected)
            assertEquals("blockads-phase1", String(http("small")))
            udp()
            controller.stop()
            await(TunnelStatus.Off)
            delay(250)
            val beforeLinks = fdLinks()
            val baseline = beforeLinks.size
            repeat(20) {
                controller.start()
                await(TunnelStatus.Connected)
                assertEquals("blockads-phase1", String(http("small")))
                udp()
                controller.stop()
                await(TunnelStatus.Off)
                delay(150)
                assertFalse(engine.isRunning())
            }
            delay(500)
            val afterLinks = fdLinks()
            val after = afterLinks.size
            println(
                "Phase1: initial counters=${counts.toList()}, FD baseline=$baseline, after20=$after"
            )
            assertTrue(
                "FD count grew from $baseline to $after; new=${afterLinks.filterKeys { it !in beforeLinks }.mapValues { entry -> socketInfo(entry.key) }}",
                after <= baseline + 4,
            )
            controller.start()
            await(TunnelStatus.Connected)
            engine.stop() // Simulate an unexpected native exit without changing consent.
            await(TunnelStatus.Error)
            delay(250)
            controller.start()
            await(TunnelStatus.Connected)
            assertEquals("blockads-phase1", String(http("small")))
        } finally {
            controller.stop()
            shell("svc wifi enable")
        }
    }

    @Test
    fun publicHttpsThroughTunnel() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalNetworkChecks") == "true"
        )
        assertNull(controller.prepare())
        try {
            controller.start()
            await(TunnelStatus.Connected)
            val connection =
                URL("https://example.com/").openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Connection", "close")
            try {
                assertEquals(200, connection.responseCode)
                assertTrue(connection.inputStream.use { it.readBytes().isNotEmpty() })
            } finally {
                connection.disconnect()
            }
            assertTrue(engine.counters()[2] > 0)
        } finally {
            controller.stop()
            await(TunnelStatus.Off)
            delay(200)
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes() }
    }
}
