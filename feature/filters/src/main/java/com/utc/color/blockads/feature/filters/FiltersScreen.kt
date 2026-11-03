package com.utc.color.blockads.feature.filters

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.designsystem.AppCard
import com.utc.color.blockads.core.designsystem.AppText

@Composable
fun FiltersScreen() {
    AppCard {
        AppText("Danh sách bộ lọc", prominent = true)
        AppText("Quản lý filter list và custom rule được triển khai từ Phase 2.")
    }
}
