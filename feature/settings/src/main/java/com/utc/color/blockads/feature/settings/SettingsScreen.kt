package com.utc.color.blockads.feature.settings

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import kotlinx.coroutines.launch

private val themeLabels = listOf("Theo hệ thống", "Sáng", "Tối", "Monet")

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onApps: () -> Unit,
    onCertificate: () -> Unit,
    error: String?,
    settings: SettingsRepository,
) {
    val dns by settings.dns.collectAsStateWithLifecycle(initialValue = DnsSettings())
    val scope = rememberCoroutineScope()
    AppColumn {
        AppSectionTitle("DNS")
        AppPreferenceGroup {
            AppChoicePreference(
                "DNS upstream",
                DnsUpstream.entries.map { it.title },
                dns.upstream.ordinal,
            ) { selected ->
                scope.launch { settings.setDns(dns.copy(upstream = DnsUpstream.entries[selected])) }
            }
            AppChoicePreference(
                "Kiểu phản hồi chặn",
                listOf("0.0.0.0 / ::", "NXDOMAIN"),
                dns.blockResponse.ordinal,
            ) { selected ->
                scope.launch {
                    settings.setDns(dns.copy(blockResponse = BlockResponse.entries[selected]))
                }
            }
            AppSwitchPreference(
                "Nhật ký DNS",
                "Chỉ lưu domain, thời gian và rule trong 7 ngày",
                dns.logging,
            ) { enabled ->
                scope.launch { settings.setDns(dns.copy(logging = enabled)) }
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
        AppSectionTitle("Bảo vệ")
        AppPreferenceGroup {
            AppAction("Ứng dụng", onApps)
            AppAction("Chứng chỉ HTTPS · Phase 5", onCertificate)
        }
        AppSectionTitle("Giới thiệu")
        AppCard {
            AppText("BlockAds · 0.2.0", prominent = true)
            AppText("COUI 1.1.0 · ColorOS 16")
            AppText(
                "Phase 2: lọc DNS, cache TTL và nhật ký trên thiết bị. Chưa lọc SNI/HTTP hoặc giải mã HTTPS."
            )
        }
    }
}
