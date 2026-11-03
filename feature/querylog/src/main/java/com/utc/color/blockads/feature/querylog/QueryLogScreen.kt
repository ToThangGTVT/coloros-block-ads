package com.utc.color.blockads.feature.querylog

import androidx.compose.runtime.Composable
import com.utc.color.blockads.core.designsystem.AppCard
import com.utc.color.blockads.core.designsystem.AppText

@Composable
fun QueryLogScreen() {
    AppCard {
        AppText("Nhật ký truy vấn", prominent = true)
        AppText(
            "Nhật ký sẽ có dữ liệu khi bộ lọc DNS hoạt động ở Phase 2. Danh sách Room/Paging được hoàn thiện ở Phase 4."
        )
    }
}
