package com.utc.color.blockads.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
    val ipv6 by settings.ipv6.collectAsStateWithLifecycle(initialValue = true)
    val retention by settings.logRetentionDays.collectAsStateWithLifecycle(initialValue = 7)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var feedback by remember { mutableStateOf<String?>(null) }
    var customUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var bootstrap by rememberSaveable { mutableStateOf<String?>(null) }
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
    AppColumn {
        feedback?.let { AppCard { AppText(it) } }
        AppSectionTitle("Bảo vệ")
        AppPreferenceGroup {
            AppSwitchPreference(
                "Tự bật sau khởi động",
                "Cần quyền VPN đã cấp trước đó",
                autoStart,
            ) {
                action { settings.setAutoStart(it) }
            }
            AppSwitchPreference(
                "IPv6",
                "Tắt sẽ chặn lưu lượng IPv6 trong tunnel để tránh đi ngoài VPN",
                ipv6,
            ) {
                action { settings.setIpv6(it) }
            }
            AppSwitchPreference(
                "Chặn QUIC",
                "Chặn UDP/443 để app có thể chuyển sang TCP/TLS",
                blockQuic,
            ) {
                action { settings.setBlockQuic(it) }
            }
            AppAction("Ứng dụng", onApps)
            AppAction("VPN luôn bật · Settings hệ thống") {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                    )
                }
                    .onFailure { feedback = "Không mở được cài đặt VPN" }
            }
            AppAction("Chứng chỉ HTTPS · Phase 5", onCertificate)
        }
        AppSectionTitle("DNS")
        AppPreferenceGroup {
            AppChoicePreference(
                "DNS upstream",
                DnsUpstream.entries.map { it.title },
                dns.upstream.ordinal,
            ) { selected ->
                action { settings.setDns(dns.copy(upstream = DnsUpstream.entries[selected])) }
            }
            if (dns.upstream == DnsUpstream.Custom) {
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
                AppAction("Lưu DNS tùy chỉnh") {
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
                "Chỉ lưu metadata trên thiết bị; có thể xóa ở mục Dữ liệu",
                dns.logging,
            ) { enabled ->
                action { settings.setDns(dns.copy(logging = enabled)) }
            }
        }

        AppSectionTitle("Giao diện")
        AppPreferenceGroup {
            AppChoicePreference(
                title = "Chủ đề",
                entries = themeLabels,
                selected = theme.ordinal,
                onSelected = { onTheme(ThemeMode.entries[it]) },
            )
        }
        if (error != null) AppCard { AppText(error) }
        AppSectionTitle("Dữ liệu")
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
            AppAction("Xóa nhật ký kết nối") { action("Đã xóa nhật ký") { queries.clearLogs() } }
            AppAction("Xuất cấu hình") { export.launch("blockads-config.json") }
            AppAction("Nhập cấu hình") { import.launch(arrayOf("application/json", "text/plain")) }
            AppText(
                "File chỉ chứa cài đặt và custom rules; không có query log, chứng chỉ hoặc khóa CA."
            )
        }
        AppSectionTitle("Giới thiệu")
        AppCard {
            AppText("BlockAds · 0.4.0", prominent = true)
            AppText("COUI 1.1.0 · ColorOS 16")
            AppText(
                "Lọc DNS, SNI/HTTP và theo ứng dụng. Thống kê tổng hợp vẫn hoạt động khi tắt log. HTTPS mã hóa chưa được giải mã."
            )
        }
    }
}
