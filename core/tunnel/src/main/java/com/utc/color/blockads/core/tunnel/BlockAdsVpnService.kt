package com.utc.color.blockads.core.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.enginebridge.EngineBridge
import com.utc.color.blockads.core.enginebridge.SocketProtector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BlockAdsVpnService : VpnService(), SocketProtector {
    private val engine = EngineBridge()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val gate = Mutex()
    private lateinit var monitor: UnderlyingNetworkMonitor
    private var monitoring = false
    private var health: Job? = null
    @Volatile private var underlying: Network? = null
    @Volatile private var acceptingSockets = false
    @Volatile private var lastStartId = 0
    private var snapshot: UnderlyingNetwork? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(ChannelId, "Tunnel VPN", NotificationManager.IMPORTANCE_LOW)
            )
        monitor = UnderlyingNetworkMonitor(this) { commands.trySend { refreshNetwork() } }
        scope.launch {
            for (command in commands) gate.withLock {
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    shutdown(
                        TunnelState(
                            TunnelStatus.Error,
                            "Kết nối VPN bị gián đoạn. Hãy bật lại VPN.",
                        )
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == Stop) {
            commands.trySend {
                shutdown(TunnelState())
                if (lastStartId == startId) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
            return START_NOT_STICKY
        }
        try {
            val type =
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                else 0
            startForeground(NotificationId, notification("Đang kết nối VPN…"), type)
        } catch (_: RuntimeException) {
            TunnelRuntime.state.value =
                TunnelState(TunnelStatus.Error, "Không thể chạy dịch vụ VPN nền.")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        commands.trySend { startTunnel(startId) }
        return START_STICKY
    }

    private suspend fun startTunnel(startId: Int) {
        if (engine.isRunning()) return
        TunnelRuntime.state.value = TunnelState(TunnelStatus.Connecting)
        try {
            check(prepare(this) == null) { "VPN permission required" }
            if (!monitoring) {
                monitor.start()
                monitoring = true
            }
            val current = monitor.current()
            underlying = current.network
            snapshot = current
            val builder =
                Builder()
                    .setSession("BlockAds")
                    .setMtu(1500)
                    .setBlocking(false)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("172.19.0.2")
                    .addDnsServer("fdfe:dcba:9876::2")
                    .setMetered(false)
            // Per-app exclusions are added here when the Phase 3 app policy repository exists.
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val previous = connectivity.activeNetwork
            val tun = builder.establish() ?: error("VPN could not be established")
            acceptingSockets = true
            val started = tun.use { engine.start(it.detachFd(), this, current.dns) }
            check(started) { "Native startup failed" }
            setUnderlyingNetworks(current.network?.let { arrayOf(it) } ?: emptyArray())
            // Native readiness precedes asynchronous Android VPN routing publication.
            withTimeout(5000) {
                while (true) {
                    val active = connectivity.activeNetwork
                    val caps = active?.let { connectivity.getNetworkCapabilities(it) }
                    if (
                        active != previous &&
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                            caps.ownerUid == Process.myUid()
                    )
                        break
                    delay(25)
                }
            }
            publishNetworkState(current)
            health?.cancel()
            health = scope.launch {
                while (isActive) {
                    delay(2000)
                    if (!engine.isRunning()) {
                        commands.trySend {
                            shutdown(
                                TunnelState(
                                    TunnelStatus.Error,
                                    "Tunnel đã dừng ngoài dự kiến. Hãy bật lại VPN.",
                                )
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) {
            shutdown(
                TunnelState(
                    TunnelStatus.Error,
                    "Không thể mở VPN. Kiểm tra quyền hoặc VPN luôn bật của ứng dụng khác.",
                )
            )
            if (lastStartId == startId) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private fun refreshNetwork() {
        if (!engine.isRunning()) return
        val current = monitor.current()
        if (current == snapshot) return
        TunnelRuntime.state.value = TunnelState(TunnelStatus.Reconnecting)
        underlying = current.network
        snapshot = current
        setUnderlyingNetworks(current.network?.let { arrayOf(it) } ?: emptyArray())
        check(engine.updateNetwork(current.dns)) { "Native network update failed" }
        publishNetworkState(current)
    }

    private fun publishNetworkState(current: UnderlyingNetwork) {
        val connected = current.network != null
        TunnelRuntime.state.value =
            TunnelState(if (connected) TunnelStatus.Connected else TunnelStatus.Reconnecting)
        getSystemService(NotificationManager::class.java)
            .notify(
                NotificationId,
                notification(
                    if (connected) "VPN đang bật · Chưa lọc quảng cáo" else "Đang chờ kết nối mạng…"
                ),
            )
    }

    override fun protect(fd: Int): Boolean {
        if (!acceptingSockets || underlying == null) {
            TunnelRuntime.lastSocketFailure = "no-upstream-or-stopping"
            return false
        }
        // Protected sockets follow Android's physical default route. Explicitly binding a
        // background cellular Network during Wi-Fi handover can be rejected by netd (EPERM).
        // Do not treat that restricted background network as an app-selectable upstream.
        val allowed = super.protect(fd)
        if (!allowed) TunnelRuntime.lastSocketFailure = "protect-rejected"
        return allowed
    }

    override fun onRevoke() {
        acceptingSockets = false
        commands.trySend {
            shutdown(
                TunnelState(
                    TunnelStatus.Revoked,
                    "Quyền VPN đã bị thu hồi hoặc một VPN khác đã thay thế.",
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdown(state: TunnelState) {
        acceptingSockets = false
        health?.cancel()
        health = null
        if (monitoring) {
            runCatching { monitor.stop() }
            monitoring = false
        }
        engine.stop() // Join native worker and close its owned TUN descriptor before releasing
        // service.
        underlying = null
        snapshot = null
        TunnelRuntime.state.value = state
    }

    override fun onDestroy() {
        commands.close()
        scope.cancel()
        runBlocking(Dispatchers.IO) {
            gate.withLock {
                shutdown(
                    TunnelRuntime.state.value.let {
                        if (it.status in listOf(TunnelStatus.Error, TunnelStatus.Revoked)) it
                        else TunnelState()
                    }
                )
            }
        }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, BlockAdsVpnService::class.java).setAction(Stop),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("BlockAds")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (launch != null)
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@BlockAdsVpnService,
                            0,
                            launch,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
            }
            .addAction(Notification.Action.Builder(null, "Tắt VPN", stop).build())
            .build()
    }

    companion object {
        const val Start = "com.utc.color.blockads.START"
        const val Stop = "com.utc.color.blockads.STOP"
        private const val ChannelId = "tunnel"
        private const val NotificationId = 1
    }
}
