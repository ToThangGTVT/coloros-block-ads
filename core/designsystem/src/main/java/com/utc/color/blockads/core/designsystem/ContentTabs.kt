package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun AppContentTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier.weight(1f)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = COUITheme.textStyles.headline1,
                    color = if (active) palette.primary else palette.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = AppSpacing.gap),
                )
                Box(
                    Modifier.fillMaxWidth(0.5f).height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) palette.primary else androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
    }
}
