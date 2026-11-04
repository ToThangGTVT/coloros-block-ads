package com.utc.color.blockads.feature.settings

import android.content.Intent
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val themeLabels = listOf("Theo hệ thống", "Sáng", "Tối", "Monet")

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    engineVersion: String,
    onApps: () -> Unit,
    onCertificate: () -> Unit,
    error: String?,
    settings: SettingsRepository,
    filters: FilterRepository,
    queries: QueryLogRepository,
) {
    val dns by settings.dns.collectAsStateWithLifecycle(initialValue = DnsSettings())
    val blockQuic by settings.blockQuic.collectAsStateWithLifecycle(initialValue = true)
    val autoStart by settings.autoStart.collectAsStateWithLifecycle(initialValue = false)
    val liveUpdates by settings.liveUpdates.collectAsStateWithLifecycle(initialValue = true)
    val ipv6 by settings.ipv6.collectAsStateWithLifecycle(initialValue = true)
    val retention by settings.logRetentionDays.collectAsStateWithLifecycle(initialValue = 7)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationManager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var notificationsAllowed by remember { mutableStateOf(notificationManager.areNotificationsEnabled()) }
    var promotionAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications())
    }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var backgroundAllowed by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundAllowed = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                notificationsAllowed = notificationManager.areNotificationsEnabled()
                promotionAllowed = Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = notificationManager.areNotificationsEnabled()
        promotionAllowed = Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications()
    }
    var customUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var bootstrap by rememberSaveable { mutableStateOf<String?>(null) }
    val batteryPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            backgroundAllowed = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    fun action(success: String? = null, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { feedback = success }
                .onFailure { feedback = it.message ?: "Thao tác thất bại" }
        }
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                action("Đã xuất cấu hình") {
                    withContext(Dispatchers.IO) {
                        val json = settings.exportConfiguration()
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                            it.write(json)
                        } ?: error("Không mở được file")
                    }
                }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                action("Đã nhập cấu hình") {
                    withContext(Dispatchers.IO) {
                        val bytes =
                            context.contentResolver.openInputStream(uri)?.use {
                                it.readNBytes(2 * 1024 * 1024 + 1)
                            } ?: error("Không mở được file")
                        require(bytes.size <= 2 * 1024 * 1024) { "File vượt quá 2 MB" }
                        filters.importConfiguration(bytes.toString(Charsets.UTF_8))
                    }
                }
        }
    val updateContent = rememberGitHubUpdateContent()
    AppLazyList {
        item(key = "settings-0", contentType = "settings-section") {
            feedback?.let { AppCard { AppText(it) } }
        }
        item(key = "settings-1", contentType = "settings-section") {
            AppSectionTitle("Bảo vệ")
        }
        item(key = "settings-2", contentType = "settings-section") {
            AppPreferenceGroup {
                AppSwitchPreference(
                    "Ưu tiên chạy ngầm",
                    if (backgroundAllowed) "Đã miễn tối ưu hóa pin cho BlockAds"
                    else "Miễn tối ưu hóa pin để VPN tiếp tục bảo vệ khi tắt màn hình",
                    backgroundAllowed,
                ) { enabled ->
                    val intent =
                        if (enabled) {
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        } else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { batteryPermission.launch(intent) }
                        .onFailure {
                            runCatching {
                                batteryPermission.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                                .onFailure { feedback = "Không mở được cài đặt pin" }
                        }
                }
                AppSwitchPreference(
                    "Tự bật sau khởi động",
                    "Cần đã cấp quyền VPN",
                    autoStart,
                ) {
                    action { settings.setAutoStart(it) }
                }
                AppSwitchPreference(
                    "IPv6",
                    "Tắt để chặn IPv6 trong VPN",
                    ipv6,
                ) {
                    action { settings.setIpv6(it) }
                }
                AppSwitchPreference(
                    "Chặn QUIC",
                    "App chuyển sang TCP, dễ lọc hơn",
                    blockQuic,
                ) {
                    action { settings.setBlockQuic(it) }
                }
                AppArrowPreference(
                    "Ứng dụng",
                    "Lọc quảng cáo và HTTPS theo app",
                    onApps,
                )
                AppArrowPreference("Lọc HTTPS", "Chặn theo URL và ẩn phần tử quảng cáo", onCertificate)
                AppArrowPreference("VPN luôn bật", "Cài đặt hệ thống") {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                        )
                    }
                        .onFailure { feedback = "Không mở được cài đặt VPN" }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 36) item(key = "settings-notifications", contentType = "settings-section") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AppSectionTitle("Thông báo") }
                AppHelpTooltip(
                    text = "Hiển thị trạng thái VPN dạng viên thuốc (Live Updates) trên Android 16 trở lên. " +
                        "Vị trí và khả năng hiển thị phụ thuộc Android và hãng máy. " +
                        "Tắt tùy chọn này chỉ ẩn viên thuốc; thông báo dịch vụ VPN vẫn được giữ để chạy nền.",
                    label = "Hướng dẫn thông báo dạng viên thuốc",
                )
            }
            AppPreferenceGroup {
                AppSwitchPreference(
                    "Thông báo dạng viên thuốc",
                    when {
                        liveUpdates && !notificationsAllowed -> "Android chưa cho phép thông báo"
                        liveUpdates && !promotionAllowed -> "Live Updates đang tắt trong Android"
                        else -> null
                    },
                    liveUpdates,
                ) { enabled ->
                    action { settings.setLiveUpdates(enabled) }
                    if (enabled && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (liveUpdates && (!notificationsAllowed || !promotionAllowed)) {
                    AppArrowPreference("Cài đặt thông báo Android") {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }.onFailure { feedback = "Không mở được cài đặt thông báo" }
                    }
                }
            }
        }
        item(key = "settings-3", contentType = "settings-section") {
            AppSectionTitle("DNS")
        }
        item(key = "settings-4", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    "DNS upstream",
                    DnsUpstream.entries.map { it.title },
                    dns.upstream.ordinal,
                ) { selected ->
                    action { settings.setDns(dns.copy(upstream = DnsUpstream.entries[selected])) }
                }
                if (dns.upstream == DnsUpstream.Custom)
                    AppGroupContent {
                        AppTextInput(
                            customUrl ?: dns.customUrl,
                            "URL https:// hoặc tls://",
                            onChange = { customUrl = it },
                        )
                        AppTextInput(
                            bootstrap ?: dns.customBootstrap,
                            "IP bootstrap",
                            onChange = { bootstrap = it },
                        )
                        AppAction("Lưu", primary = true) {
                            action("Đã lưu DNS tùy chỉnh") {
                                settings.setDns(
                                    dns.copy(
                                        customUrl = customUrl ?: dns.customUrl,
                                        customBootstrap = bootstrap ?: dns.customBootstrap,
                                    )
                                )
                            }
                        }
                    }
                AppChoicePreference(
                    "Kiểu phản hồi chặn",
                    listOf("0.0.0.0 / ::", "NXDOMAIN"),
                    dns.blockResponse.ordinal,
                ) { selected ->
                    action {
                        settings.setDns(dns.copy(blockResponse = BlockResponse.entries[selected]))
                    }
                }
                AppSwitchPreference(
                    "Nhật ký kết nối",
                    "Chỉ lưu trên thiết bị",
                    dns.logging,
                ) { enabled ->
                    action { settings.setDns(dns.copy(logging = enabled)) }
                }
            }
        }
        item(key = "settings-5", contentType = "settings-section") {
            AppSectionTitle("Giao diện")
        }
        item(key = "settings-6", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    title = "Chủ đề",
                    entries = themeLabels,
                    selected = theme.ordinal,
                    onSelected = { onTheme(ThemeMode.entries[it]) },
                )
            }
        }
        item(key = "settings-7", contentType = "settings-section") {
            if (error != null) AppCard { AppText(error) }
        }
        item(key = "settings-8", contentType = "settings-section") {
            AppSectionTitle("Dữ liệu")
        }
        item(key = "settings-9", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    "Giữ nhật ký",
                    listOf("1 ngày", "7 ngày", "30 ngày"),
                    listOf(1, 7, 30).indexOf(retention).coerceAtLeast(0),
                ) { index ->
                    action {
                        settings.setLogRetentionDays(listOf(1, 7, 30)[index])
                        queries.prune()
                    }
                }
                AppArrowPreference("Xuất cấu hình", "File JSON") {
                    export.launch("blockads-config.json")
                }
                AppArrowPreference("Nhập cấu hình", "Từ file JSON") {
                    import.launch(arrayOf("application/json", "text/plain"))
                }
                AppArrowPreference("Xóa nhật ký kết nối") {
                    action("Đã xóa nhật ký") { queries.clearLogs() }
                }
            }
        }
        item(key = "settings-10", contentType = "settings-section") {
            updateContent()
        }
        item(key = "settings-11", contentType = "settings-section") {
            AppSectionTitle("Giới thiệu")
        }
        item(key = "settings-12", contentType = "settings-section") {
            AppCard {
                AppText(
                    "BlockAds ${remember(context) { com.utc.color.blockads.core.data.GitHubUpdateRepository(context).currentVersion }}",
                    prominent = true,
                )
                AppHint("Rust JNI · $engineVersion")
                AppHint("COUI 1.1.0 · ColorOS 16")
            }
        }
    }
}
