package com.utc.color.blockads.core.designsystem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.utc.color.blockads.core.common.TrafficDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.google.android.material.R as Material
import com.utc.color.blockads.core.common.R as Strings

class TrafficChart(context: Context, attrs: AttributeSet) : LineChart(context, attrs) {
    init {
        renderer = TrafficLineRenderer(this)
        description.isEnabled = false
        axisRight.isEnabled = false
        isScaleEnabled = false
        isTouchEnabled = false
        isClipDataToContentEnabled = false
        extraBottomOffset = 16f
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.isDrawGridLinesEnabled = false
        xAxis.spaceMin = 0.4f
        xAxis.spaceMax = 0.4f
        axisLeft.axisMinimum = 0f
        axisLeft.isDrawAxisLineEnabled = false
        axisLeft.granularity = 1f
        axisLeft.labelCount = 4
        val text = context.color(Material.attr.colorOnSurface)
        xAxis.textColor = text
        axisLeft.textColor = text
        legend.textColor = text
        axisLeft.gridColor = ColorUtils.setAlphaComponent(text, 16)
        xAxis.axisLineColor = ColorUtils.setAlphaComponent(text, 30)
    }

    fun render(days: List<TrafficDay>) {
        if (days.isEmpty()) {
            clear()
            return
        }

        val locale = resources.configuration.locales[0]
        fun format(pattern: String, day: TrafficDay): String =
            DateTimeFormatter.ofPattern(pattern, locale)
                .format(Instant.ofEpochMilli(day.day).atZone(ZoneId.systemDefault()))

        val labels = days.map { format("EEE", it) }
        xAxis.valueFormatter = IAxisValueFormatter { value, _ -> labels.getOrNull(value.roundToInt()).orEmpty() }
        xAxis.labelCount = days.size.coerceAtLeast(2)
        axisLeft.axisMaximum = days.maxOf { it.total }.coerceAtLeast(1).toFloat() * 1.15f
        contentDescription = context.getString(Strings.string.traffic_week_description) +
            days.joinToString(", ") {
                context.getString(Strings.string.traffic_day_description, format("dd/MM", it), it.total, it.blocked)
            }

        fun area(label: Int, color: Int, alpha: Int, value: (TrafficDay) -> Long) =
            LineDataSet(
                days.mapIndexed { index, day -> Entry(index.toFloat(), value(day).toFloat()) },
                context.getString(label),
            ).apply {
                colors = listOf(color)
                mode = LineDataSet.Mode.HORIZONTAL_BEZIER
                lineWidth = 4f
                isDrawCirclesEnabled = false
                isDrawValuesEnabled = false
                isHighlightEnabled = false
                isDrawFilledEnabled = true
                fillDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(ColorUtils.setAlphaComponent(color, alpha), ColorUtils.setAlphaComponent(color, 0)),
                )
            }

        data = LineData(
            area(Strings.string.all, context.color(androidx.appcompat.R.attr.colorPrimary), 71) { it.total },
            area(Strings.string.blocked, context.color(androidx.appcompat.R.attr.colorError), 36) { it.blocked },
        )
        invalidate()
    }
}

/** Rounded line starts and a single solid marker at the newest day of each series. */
private class TrafficLineRenderer(private val view: LineChart) :
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
            val last = dataSet.getEntryForIndex(dataSet.entryCount - 1)
            markerPosition[0] = last.x
            markerPosition[1] = last.y * view.animator.phaseY
            view.getTransformer(dataSet.axisDependency).pointValuesToPixel(markerPosition)
            markerPaint.color = dataSet.getColor(dataSet.entryCount - 1)
            c.drawCircle(markerPosition[0], markerPosition[1], markerRadius, markerPaint)
        }
    }
}
