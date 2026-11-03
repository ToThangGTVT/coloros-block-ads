package com.utc.color.blockads.feature.home

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.utc.color.blockads.core.designsystem.*

@Composable
fun HomeScreen(engineVersion: String) {
    var demoSwitch by rememberSaveable { mutableStateOf(false) }
    var demoPreference by rememberSaveable { mutableStateOf(true) }
    AppColumn {
        AppCard {
            AppText("BlockAds", prominent = true)
            AppText("Phase 0 · Nền tảng giao diện")
            AppText("Chưa bật bảo vệ. Tunnel VPN sẽ được triển khai ở Phase 1.")
        }
        AppSectionTitle("Demo COUI")
        AppCard {
            AppText("Công tắc mẫu", prominent = true)
            AppText("Thử tương tác COUI; công tắc này không khởi động VPN.")
            AppSwitch(checked = demoSwitch, onCheckedChange = { demoSwitch = it })
        }
        AppPreferenceGroup {
            AppSwitchPreference(
                title = "Preference mẫu",
                summary = "Thành phần từ coui-preference 1.1.0",
                checked = demoPreference,
                onCheckedChange = { demoPreference = it },
            )
        }
        AppCard {
            AppText("Native engine", prominent = true)
            AppText("Rust JNI · $engineVersion")
        }
    }
}
