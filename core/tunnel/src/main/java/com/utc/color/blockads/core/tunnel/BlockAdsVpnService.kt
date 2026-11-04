package com.utc.color.blockads.core.tunnel

import com.utc.color.blockads.core.common.R as L10n
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
import android.os.Bundle
import android.os.Process
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.common.TunnelStatus
import com.utc.color.blockads.core.enginebridge.EngineBridge
import com.utc.color.blockads.core.enginebridge.SocketProtector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

@dagger.hilt.android.AndroidEntryPoint
class BlockAdsVpnService : VpnService(), SocketProtector {
    @javax.inject.Inject lateinit var filters: com.utc.color.blockads.core.filter.FilterRepository
    @javax.inject.Inject lateinit var queries: com.utc.color.blockads.core.data.QueryLogRepository
    @javax.inject.Inject lateinit var settings: com.utc.color.blockads.core.data.SettingsRepository
    @javax.inject.Inject lateinit var trafficPolicy: TrafficPolicyRepository
    @javax.inject.Inject
    lateinit var certificates: com.utc.color.blockads.core.certificate.CertificateRepository
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
    private var excludedPackages: Set<String>? = null
    @Volatile private var liveUpdatesEnabled = false
    @Volatile private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        lastNotificationText = getString(L10n.string.vpn_connecting)
        engine.setDnsEventSink(
            com.utc.color.blockads.core.enginebridge.DnsEventSink(queries::onDnsEvents)
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(ChannelId, getString(L10n.string.vpn_channel), NotificationManager.IMPORTANCE_LOW)
            )
        scope.launch {
            settings.liveUpdates.collect { enabled ->
                liveUpdatesEnabled = enabled
                // Serialize with stop/restart so a setting change cannot resurrect a stopped VPN notification.
                gate.withLock {
                    if (engine.isRunning()) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NotificationId, notification(lastNotificationText))
                    }
                }
            }
        }
        scope.launch {
            filters.status.collect { status ->
                if (TunnelRuntime.state.value.status == TunnelStatus.Connected) {
                    getSystemService(NotificationManager::class.java)
                        .notify(
                            NotificationId,
                            notification(
                                if (status.loaded > 0) resources.getQuantityString(L10n.plurals.protecting_rules, status.loaded, status.loaded)
                                else getString(L10n.string.vpn_no_rules)
                            ),
                        )
                }
            }
        }
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            queries.today.sample(5000).collect {
                if (TunnelRuntime.state.value.status == TunnelStatus.Connected)
                    getSystemService(NotificationManager::class.java)
                        .notify(
                            NotificationId,
                            notification(
                                if (filters.status.value.loaded > 0) getString(L10n.string.vpn_protecting)
                                else getString(L10n.string.vpn_no_rules)
                            ),
                        )
            }
        }
        monitor = UnderlyingNetworkMonitor(this) { commands.trySend { refreshNetwork() } }
        scope.launch {
            trafficPolicy.applied
                .filterNotNull()
                .map { it.excludedPackages }
                .distinctUntilChanged()
                .collect { excluded ->
                    commands.trySend {
                        if (
                            excludedPackages != null &&
                                excludedPackages != excluded &&
                                engine.isRunning()
                        ) {
                            shutdown(TunnelState(TunnelStatus.Reconnecting))
                            startTunnel(lastStartId)
                        }
                    }
                }
        }
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
                            getString(L10n.string.vpn_interrupted),
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
        if (intent?.action == HideLiveUpdate) {
            scope.launch { settings.setLiveUpdates(false) }
            return START_STICKY
        }
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
            startForeground(NotificationId, notification(getString(L10n.string.vpn_connecting)), type)
        } catch (_: RuntimeException) {
            TunnelRuntime.state.value =
                TunnelState(TunnelStatus.Error, getString(L10n.string.vpn_background_failed))
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
            filters.awaitReady()
            trafficPolicy.awaitReady()
            certificates.awaitReady()
            certificates.refresh()
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
                    .setMtu(9000) // Must match MTU in nativecore/src/tun/mod.rs.
                    .setBlocking(false)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("172.19.0.2")
                    .addDnsServer("fdfe:dcba:9876::2")
                    .setMetered(false)
            val excluded = trafficPolicy.applied.filterNotNull().first().excludedPackages
            excluded.forEach { name -> runCatching { builder.addDisallowedApplication(name) } }
            excludedPackages = excluded
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
                    val excludedSelf = packageName in excluded
                    val ownVpn =
                        excludedSelf &&
                            connectivity.allNetworks.any { network ->
                                connectivity.getNetworkCapabilities(network)?.let {
                                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                                        it.ownerUid == Process.myUid()
                                } == true
                            }
                    if (
                        ownVpn ||
                            (active != previous &&
                                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                                caps.ownerUid == Process.myUid())
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
                                    getString(L10n.string.vpn_stopped_unexpectedly),
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
                    getString(L10n.string.vpn_open_failed),
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
            TunnelState(
                if (connected) TunnelStatus.Connected else TunnelStatus.Reconnecting,
                privateDns = current.privateDns,
            )
        getSystemService(NotificationManager::class.java)
            .notify(
                NotificationId,
                notification(
                    if (connected)
                        if (filters.status.value.loaded > 0)
                            resources.getQuantityString(L10n.plurals.protecting_rules, filters.status.value.loaded, filters.status.value.loaded)
                        else getString(L10n.string.vpn_no_rules)
                    else getString(L10n.string.network_waiting)
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

    override fun connectionOwner(
        protocol: Int,
        source: String,
        sourcePort: Int,
        destination: String,
        destinationPort: Int,
    ): Int = runCatching {
        getSystemService(ConnectivityManager::class.java)
            .getConnectionOwnerUid(
                protocol,
                java.net.InetSocketAddress(java.net.InetAddress.getByName(source), sourcePort),
                java.net.InetSocketAddress(
                    java.net.InetAddress.getByName(destination),
                    destinationPort,
                ),
            )
    }
        .getOrDefault(-1)

    override fun onRevoke() {
        acceptingSockets = false
        commands.trySend {
            shutdown(
                TunnelState(
                    TunnelStatus.Revoked,
                    getString(L10n.string.vpn_revoked_detail),
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, getString(L10n.string.vpn_channel), NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            gate.withLock {
                if (engine.isRunning()) {
                    val text = when (TunnelRuntime.state.value.status) {
                        TunnelStatus.Connected -> {
                            val count = filters.status.value.loaded
                            if (count > 0) resources.getQuantityString(L10n.plurals.protecting_rules, count, count)
                            else getString(L10n.string.vpn_no_rules)
                        }
                        TunnelStatus.Reconnecting -> getString(L10n.string.network_waiting)
                        else -> getString(L10n.string.vpn_connecting)
                    }
                    manager.notify(NotificationId, notification(text))
                }
            }
        }
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
        lastNotificationText = text
        val stats = queries.today.value
        val total = stats.total.coerceAtLeast(0)
        val blocked = stats.blocked.coerceIn(0, total)
        val allowed = total - blocked
        val blockedRatio = if (total > 0) blocked.toDouble() / total else 0.0
        val allowedRatio = if (total > 0) allowed.toDouble() / total else 0.0
        val numbers = java.text.NumberFormat.getIntegerInstance(resources.configuration.locales[0])
        val percent = java.text.NumberFormat.getPercentInstance(resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        val blockedPercent = percent.format(blockedRatio)
        val counters = SpannableStringBuilder(getString(L10n.string.today_prefix))
        fun appendCount(value: Long, ratio: Double, label: String, color: Int) {
            val start = counters.length
            counters.append(numbers.format(value)).append(" ").append(label)
                .append(" (").append(percent.format(ratio)).append(")")
            counters.setSpan(ForegroundColorSpan(color), start, counters.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendCount(allowed, allowedRatio, getString(L10n.string.allowed_lowercase), 0xFF34865D.toInt())
        counters.append(" / ")
        appendCount(blocked, blockedRatio, getString(L10n.string.blocked_lowercase), 0xFFB95E57.toInt())
        val compact = android.icu.text.CompactDecimalFormat.getInstance(
            resources.configuration.locales[0], android.icu.text.CompactDecimalFormat.CompactStyle.SHORT,
        ).apply { maximumFractionDigits = 0 }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val hide = PendingIntent.getService(
            this, 2, Intent(this, BlockAdsVpnService::class.java).setAction(HideLiveUpdate),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(
                if (Build.VERSION.SDK_INT >= 36 && liveUpdatesEnabled)
                    if (total > 0) getString(L10n.string.notification_block_rate, blockedPercent) else getString(L10n.string.notification_no_requests)
                else "BlockAds"
            )
            .setContentText(counters)
            .apply {
                if (Build.VERSION.SDK_INT >= 36 && liveUpdatesEnabled) {
                    // Green at the left, red at the right, using one common 10,000-unit scale.
                    // Do not pass zero-length segments to the framework.
                    val blockedUnits = (blockedRatio * 10_000).roundToInt().coerceIn(0, 10_000)
                    val allowedUnits = 10_000 - blockedUnits
                    val segments = buildList {
                        if (total == 0L) {
                            add(Notification.ProgressStyle.Segment(10_000).setColor(0xFF888888.toInt()))
                        } else {
                            if (allowedUnits > 0) add(
                                Notification.ProgressStyle.Segment(allowedUnits).setColor(0xFF34865D.toInt())
                            )
                            if (blockedUnits > 0) add(
                                Notification.ProgressStyle.Segment(blockedUnits).setColor(0xFFB95E57.toInt())
                            )
                        }
                    }
                    setStyle(Notification.ProgressStyle()
                        .setProgressSegments(segments)
                        .setStyledByProgress(false)
                        .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithResource(
                            this@BlockAdsVpnService, R.drawable.ic_progress_shield,
                        ))
                        .setProgress(if (total > 0) allowedUnits else 0))
                } else {
                    setStyle(Notification.BigTextStyle().bigText(
                        SpannableStringBuilder(text).append('\n').append(counters)
                    ))
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (Build.VERSION.SDK_INT >= 36) {
                    // The extras mechanism works on Android 16. The equivalent builder setter
                    // only became public in SDK 36.1, so do not call it on base Android 16.
                    addExtras(Bundle().apply {
                        putBoolean("android.requestPromotedOngoing", liveUpdatesEnabled)
                    })
                    if (liveUpdatesEnabled) {
                        setShortCriticalText(
                            when (TunnelRuntime.state.value.status) {
                                TunnelStatus.Connected -> "${compact.format(allowed)}/${compact.format(blocked)}"
                                TunnelStatus.Reconnecting -> getString(L10n.string.network_waiting_short)
                                else -> getString(L10n.string.connecting_short)
                            }
                        )
                        setDeleteIntent(hide)
                    }
                }
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
            .build()
    }

    companion object {
        const val Start = "com.utc.color.blockads.START"
        const val Stop = "com.utc.color.blockads.STOP"
        private const val HideLiveUpdate = "com.utc.color.blockads.HIDE_LIVE_UPDATE"
        private const val ChannelId = "tunnel"
        private const val NotificationId = 1
    }
}
