package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.compose.BarChart
import com.github.mikephil.charting.compose.rememberTypeface
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.utc.color.blockads.core.common.TrafficDay
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** MPAndroidChart is isolated here; every visual token comes from COUI. */
@Composable
fun AppTrafficChart(days: List<TrafficDay>) {
    if (days.isEmpty()) {
        AppText("Đang tải thống kê…")
        return
    }
    val palette = COUITheme.colorScheme
    val style = COUITheme.textStyles.footnote1
    val font = rememberTypeface(style.fontFamily ?: FontFamily.Default)
    val textSize = style.fontSize.value * LocalDensity.current.fontScale
    val radius = CardDefaults.CornerRadius.value
    val labels =
        remember(days) {
            days.map {
                DateTimeFormatter.ofPattern("dd/MM")
                    .format(Instant.ofEpochMilli(it.day).atZone(ZoneId.systemDefault()))
            }
        }
    val series =
        remember(days, palette.primary, palette.error, radius) {
            val entries = days.mapIndexed { index, day ->
                BarEntry<Nothing>(
                    index.toFloat(),
                    stackValues =
                        listOf(
                            (day.total - day.blocked).coerceAtLeast(0).toFloat(),
                            day.blocked.toFloat(),
                        ),
                    data = null,
                )
            }
            BarData(
                BarDataSet(entries, "").apply {
                    this.colors = listOf(palette.primary.toArgb(), palette.error.toArgb())
                    stackLabels = listOf("Cho phép", "Đã chặn")
                    isDrawValuesEnabled = false
                    barCornerRadius = radius
                    isHighlightEnabled = false
                }
            )
        }
    BarChart(
        data = series,
        modifier = Modifier.fillMaxWidth().height(AppSpacing.page * 12),
        contentDescription =
            "Thống kê 7 ngày: " +
                days
                    .mapIndexed { index, day ->
                        "${labels[index]} tổng ${day.total}, chặn ${day.blocked}"
                    }
                    .joinToString("; "),
        setup = {
            description.isEnabled = false
            axisRight.isEnabled = false
            isScaleEnabled = false
            isTouchEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.isDrawGridLinesEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.granularity = 1f
        },
        update = {
            xAxis.textColor = palette.onSurface.toArgb()
            xAxis.typeface = font
            xAxis.textSize = textSize
            xAxis.valueFormatter = IAxisValueFormatter { value, _ ->
                labels.getOrNull(value.roundToInt()) ?: ""
            }
            xAxis.labelCount = days.size.coerceAtLeast(2)
            axisLeft.textColor = palette.onSurface.toArgb()
            axisLeft.typeface = font
            axisLeft.textSize = textSize
            axisLeft.gridColor = palette.onSurface.copy(alpha = 0.12f).toArgb()
            axisLeft.axisLineColor = palette.onSurface.copy(alpha = 0.12f).toArgb()
            xAxis.axisLineColor = palette.onSurface.copy(alpha = 0.12f).toArgb()
            legend.textColor = palette.onSurface.toArgb()
            legend.typeface = font
            legend.textSize = textSize
            axisLeft.axisMaximum =
                (days.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1).toFloat() * 1.15f
        },
    )
}
