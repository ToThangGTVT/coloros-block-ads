package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.text.NumberFormat

/** One proportional bar and a matching legend for the displayed top domains. */
@Composable
fun AppDomainBreakdown(domains: List<Pair<String, Long>>, otherCount: Long = 0) {
    val namedEntries = domains.filter { it.second > 0 }
    val entries = namedEntries + if (otherCount > 0) listOf("Khác" to otherCount) else emptyList()
    if (entries.isEmpty()) {
        AppCard { AppHint("Chưa có") }
        return
    }
    val palette = COUITheme.colorScheme
    val colors = remember(palette.primary, palette.onSurface, palette.surface, namedEntries.size, entries.size) {
        val mutedBlue = lerp(palette.primary, palette.onSurface, 0.3f)
        List(entries.size) { index ->
            if (index == namedEntries.size) lerp(palette.onSurface, palette.surface, 0.6f)
            else lerp(mutedBlue, palette.surface, (0.08f + index * 0.13f).coerceAtMost(0.6f))
        }
    }
    val number = remember { NumberFormat.getIntegerInstance() }
    val total = entries.sumOf { it.second.toDouble() }
    AppPreferenceGroup {
        Row(
            modifier = Modifier.padding(AppSpacing.card)
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(50))
                .semantics {
                    contentDescription = "Tỷ trọng trong các tên miền bị chặn nhiều nhất: " +
                        entries.joinToString("; ") { (domain, count) ->
                            "$domain ${number.format(count)} lượt"
                        }
                },
        ) {
            entries.forEachIndexed { index, (_, count) ->
                Box(
                    Modifier.weight((count.toDouble() / total).toFloat())
                        .height(20.dp)
                        .background(colors[index])
                )
            }
        }
        entries.forEachIndexed { index, (domain, count) ->
            key(domain) {
                AppValueRow(domain, number.format(count), markerColor = colors[index])
            }
        }
    }
}
