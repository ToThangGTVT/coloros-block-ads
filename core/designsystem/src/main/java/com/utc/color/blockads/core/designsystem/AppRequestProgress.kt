package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.text.NumberFormat

/** A single bar: primary is allowed traffic, red is the blocked share of all requests. */
@Composable
fun AppRequestProgress(total: Long, blocked: Long) {
    val palette = COUITheme.colorScheme
    val totalCount = total.coerceAtLeast(0)
    val blockedCount = blocked.coerceIn(0, totalCount)
    val fraction = if (totalCount == 0L) 0f else (blockedCount.toDouble() / totalCount).toFloat()
    val number = remember { NumberFormat.getIntegerInstance() }
    val percent = remember { NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 } }
    val totalLabel = "${number.format(totalCount)} yêu cầu"
    val blockedLabel = "${number.format(blockedCount)} đã chặn · ${percent.format(fraction)}"

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap)) {
            Box(Modifier.weight(1f)) { AppHint(totalLabel) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = blockedLabel,
                    style = COUITheme.textStyles.footnote1,
                    color = palette.error,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(
                    if (totalCount > 0) palette.primary else palette.onSurface.copy(alpha = 0.08f)
                )
                .semantics {
                    contentDescription = "$totalLabel, $blockedLabel"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                }
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(palette.error)
                )
            }
        }
    }
}
