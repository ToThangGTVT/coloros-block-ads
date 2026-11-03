package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.compose.LineChart
import com.github.mikephil.charting.compose.rememberTypeface
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.utc.color.blockads.core.common.TrafficDay
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
    fun format(pattern: String) =
        days.map {
            DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)
                .format(Instant.ofEpochMilli(it.day).atZone(ZoneId.systemDefault()))
        }
    // Axis shows weekdays (Mon, Tue, …); the accessibility text keeps the full date.
    val labels = remember(days) { format("EEE") }
    val dates = remember(days) { format("dd/MM") }
    val series =
        remember(days, palette.primary, palette.error) {
            // Total first so the blocked area, always within it, is drawn on top.
            fun area(label: String, color: androidx.compose.ui.graphics.Color, value: (TrafficDay) -> Long) =
                LineDataSet(
                        days.mapIndexed { index, day ->
                            Entry(index.toFloat(), value(day).toFloat())
                        },
                        label,
                    )
                    .apply {
                        colors = listOf(color.toArgb())
                        // Horizontal bezier stays smooth without dipping below zero.
                        mode = LineDataSet.Mode.HORIZONTAL_BEZIER
                        lineWidth = 2f
                        isDrawCirclesEnabled = false
                        isDrawValuesEnabled = false
                        isHighlightEnabled = false
                        isDrawFilledEnabled = true
                        fillDrawable =
                            android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(
                                    color.copy(alpha = 0.4f).toArgb(),
                                    color.copy(alpha = 0f).toArgb(),
                                ),
                            )
                    }
            LineData(
                area("Tất cả", palette.primary) { it.total },
                area("Đã chặn", palette.error) { it.blocked },
            )
        }
    LineChart(
        data = series,
        modifier = Modifier.fillMaxWidth().height(AppSpacing.page * 12),
        contentDescription =
            "Thống kê 7 ngày: " +
                days
                    .mapIndexed { index, day ->
                        "${dates[index]} tổng ${day.total}, chặn ${day.blocked}"
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
            xAxis.spaceMin = 0.4f
            xAxis.spaceMax = 0.4f
            axisLeft.axisMinimum = 0f
            axisLeft.isDrawAxisLineEnabled = false
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
