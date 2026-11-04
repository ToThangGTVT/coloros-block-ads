package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AppRequestPoint(val timestamp: Long, val total: Long, val blocked: Long)

/** Lightweight per-app chart; both series share a zero-based scale. */
@Composable
fun AppRequestChart(points: List<AppRequestPoint>, period: String) {
    if (points.size < 2) return
    val palette = COUITheme.colorScheme
    val dark = palette.surface.luminance() < 0.5f
    val blue = if (dark) Color(0xFF93B2BF) else Color(0xFF5D8190)
    val red = if (dark) Color(0xFFD29C92) else Color(0xFFAD7067)
    val grid = palette.onSurface.copy(alpha = 0.08f)
    val maximum = points.maxOf { it.total }.coerceAtLeast(1).toFloat()
    val labels = remember(points.first().timestamp, points.last().timestamp) {
        val format = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())
        listOf(points.first(), points.last()).map { format.format(Instant.ofEpochMilli(it.timestamp)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.page)) {
            ChartLegend("Request", blue)
            ChartLegend("Đã chặn", red)
        }
        Canvas(
            Modifier.fillMaxWidth().height(88.dp).semantics {
                contentDescription = "$period, theo giờ: " + points.joinToString("; ") {
                    val time = DateTimeFormatter.ofPattern("dd/MM HH:mm")
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it.timestamp))
                    "$time: ${it.total} request, ${it.blocked} đã chặn"
                }
            }
        ) {
            val inset = 3.dp.toPx()
            val chartHeight = (size.height - 2 * inset).coerceAtLeast(1f)
            val chartWidth = (size.width - 2 * inset).coerceAtLeast(1f)
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = inset + chartHeight * fraction
                drawLine(grid, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
            }
            fun series(color: Color, value: (AppRequestPoint) -> Long) {
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = inset + chartWidth * index / points.lastIndex
                    val y = inset + chartHeight * (1f - value(point).toFloat() / maximum)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            series(blue) { it.total }
            series(red) { it.blocked }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppHint(labels.first())
            AppHint(labels.last())
        }
        AppHint("$period · theo giờ")
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        AppHint(label)
    }
}
