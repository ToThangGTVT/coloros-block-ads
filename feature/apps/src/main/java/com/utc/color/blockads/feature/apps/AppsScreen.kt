package com.utc.color.blockads.feature.apps

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.designsystem.AppCard
import com.utc.color.blockads.core.designsystem.AppText

@Composable
fun AppsScreen() {
    AppCard {
        AppText("Ứng dụng", prominent = true)
        AppText("Lọc, bỏ qua lọc và bỏ qua tunnel theo ứng dụng sẽ có ở Phase 3.")
    }
}
