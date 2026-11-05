package com.utc.color.blockads.core.designsystem

import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.text.NumberFormat

/** Individual bars compare each domain against the largest count. */
@Composable
fun AppDomainBreakdown(domains: List<Pair<String, Long>>, otherCount: Long = 0) {
    val strings = rememberAppStrings()
    val namedEntries = domains.filter { it.second > 0 }
    val entries = namedEntries + if (otherCount > 0) listOf(strings.getString(L10n.string.other) to otherCount) else emptyList()
    if (entries.isEmpty()) {
        AppCard { AppHint(strings.getString(L10n.string.none_yet)) }
        return
    }
    val palette = COUITheme.colorScheme
    val colors = remember(palette.primary, palette.onSurface, palette.surface, namedEntries.size, entries.size) {
        val mutedPrimary = lerp(palette.primary, palette.onSurface, 0.3f)
        List(entries.size) { index ->
            if (index == namedEntries.size) lerp(palette.onSurface, palette.surface, 0.6f)
            else lerp(mutedPrimary, palette.surface, (0.08f + index * 0.13f).coerceAtMost(0.6f))
        }
    }
    val number = remember(strings.configuration.locales[0]) { NumberFormat.getIntegerInstance(strings.configuration.locales[0]) }
    val maximum = entries.maxOf { it.second }.toDouble()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        entries.forEachIndexed { index, (domain, count) ->
            key(domain) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = domain,
                            modifier = Modifier.weight(1f),
                            style = COUITheme.textStyles.body1,
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AppHint(number.format(count))
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(palette.onSurface.copy(alpha = 0.05f))) {
                        Box(
                            Modifier.fillMaxWidth((count / maximum).toFloat().coerceIn(0.001f, 1f))
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(colors[index])
                        )
                    }
                }
            }
        }
    }
}
