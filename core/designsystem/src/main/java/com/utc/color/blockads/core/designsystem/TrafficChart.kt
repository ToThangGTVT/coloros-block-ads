package com.utc.color.blockads.core.designsystem

import android.graphics.Canvas
import android.graphics.Paint
import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.github.mikephil.charting.charts.LineChart as AndroidLineChart
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.utc.color.blockads.core.common.TrafficDay
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** MPAndroidChart is isolated here; every visual token comes from COUI. */
@Composable
fun AppTrafficChart(days: List<TrafficDay>) {
    val strings = rememberAppStrings()
    if (days.isEmpty()) {
        AppText(strings.getString(L10n.string.statistics_loading))
        return
    }
    val palette = COUITheme.colorScheme
    val style = COUITheme.textStyles.footnote1
    val font = rememberTypeface(style.fontFamily ?: FontFamily.Default)
    val textSize = style.fontSize.value * LocalDensity.current.fontScale
    fun format(pattern: String) =
        days.map {
            DateTimeFormatter.ofPattern(pattern, strings.configuration.locales[0])
                .format(Instant.ofEpochMilli(it.day).atZone(ZoneId.systemDefault()))
        }
    // Axis shows weekdays (Mon, Tue, …); the accessibility text keeps the full date.
    val labels = remember(days, strings.configuration.locales[0]) { format("EEE") }
    val dates = remember(days, strings.configuration.locales[0]) { format("dd/MM") }
    val series =
        remember(days, palette.primary, palette.error, strings.configuration.locales[0]) {
            // Total first so the blocked area, always within it, is drawn on top.
            fun area(label: String, color: androidx.compose.ui.graphics.Color, fillAlpha: Float, value: (TrafficDay) -> Long) =
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
                        lineWidth = 4f
                        isDrawCirclesEnabled = false
                        isDrawValuesEnabled = false
                        isHighlightEnabled = false
                        isDrawFilledEnabled = true
                        fillDrawable =
                            android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(
                                    color.copy(alpha = fillAlpha).toArgb(),
                                    color.copy(alpha = 0f).toArgb(),
                                ),
                            )
                    }
            LineData(
                area(strings.getString(L10n.string.all), palette.primary, 0.28f) { it.total },
                area(strings.getString(L10n.string.blocked), palette.error, 0.14f) { it.blocked },
            )
        }
    LineChart(
        data = series,
        modifier = Modifier.fillMaxWidth().height(280.dp),
        contentDescription =
            strings.getString(L10n.string.traffic_week_description) +
                days
                    .mapIndexed { index, day ->
                        strings.getString(L10n.string.traffic_day_description, dates[index], day.total, day.blocked)
                    }
                    .joinToString("; "),
        setup = {
            renderer = TrafficLineRenderer(this)
            description.isEnabled = false
            axisRight.isEnabled = false
            isScaleEnabled = false
            isTouchEnabled = false
            // A zero-valued line is centered on the plot's bottom edge. Let its
            // lower half render into the reserved inset instead of clipping it.
            isClipDataToContentEnabled = false
            extraBottomOffset = 6f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.isDrawGridLinesEnabled = false
            xAxis.spaceMin = 0.4f
            xAxis.spaceMax = 0.4f
            axisLeft.axisMinimum = 0f
            axisLeft.isDrawAxisLineEnabled = false
            axisLeft.granularity = 1f
            axisLeft.labelCount = 4
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
            axisLeft.gridColor = palette.onSurface.copy(alpha = 0.06f).toArgb()
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

/** Rounded line starts and a single solid marker at the newest day of each series. */
private class TrafficLineRenderer(private val view: AndroidLineChart) :
    LineChartRenderer(view, view.animator, view.viewPortHandler) {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPosition = FloatArray(2)
    private val markerRadius = 5f * view.resources.displayMetrics.density

    init {
        paintRender.strokeCap = Paint.Cap.ROUND
        paintRender.strokeJoin = Paint.Join.ROUND
    }

    override fun drawExtras(c: Canvas) {
        super.drawExtras(c)
        view.lineData?.dataSets?.forEach { dataSet ->
            if (!dataSet.isVisible || dataSet.entryCount == 0) return@forEach
            val last = dataSet.getEntryForIndex(dataSet.entryCount - 1) ?: return@forEach
            markerPosition[0] = last.x
            markerPosition[1] = last.y * view.animator.phaseY
            view.getTransformer(dataSet.axisDependency).pointValuesToPixel(markerPosition)
            markerPaint.color = dataSet.getColor(dataSet.entryCount - 1)
            c.drawCircle(markerPosition[0], markerPosition[1], markerRadius, markerPaint)
        }
    }
}
