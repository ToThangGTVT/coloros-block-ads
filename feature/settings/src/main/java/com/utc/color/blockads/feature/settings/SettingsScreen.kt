package com.utc.color.blockads.feature.settings

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.designsystem.*

private val themeLabels = listOf("Theo hệ thống", "Sáng", "Tối", "Monet")

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onApps: () -> Unit,
    onCertificate: () -> Unit,
    error: String?,
) {
    AppColumn {
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
            AppText("BlockAds · 0.0.1", prominent = true)
            AppText("COUI 1.1.0 · ColorOS 16")
            AppText("Bản khung Phase 0. Chưa thu thập truy vấn hoặc chuyển tiếp lưu lượng.")
        }
    }
}
