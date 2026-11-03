package com.utc.color.blockads.feature.certificate

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.designsystem.AppCard
import com.utc.color.blockads.core.designsystem.AppText

@Composable
fun CertificateScreen() {
    AppCard {
        AppText("Chứng chỉ HTTPS", prominent = true)
        AppText("Tính năng tùy chọn ở Phase 5. Chưa tạo CA hoặc bật giải mã HTTPS.")
    }
}
