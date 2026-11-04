package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.RichTooltip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TooltipAnchorPosition
import io.github.suqi8.coui.kmp.basic.TooltipBox
import io.github.suqi8.coui.kmp.basic.TooltipDefaults
import io.github.suqi8.coui.kmp.basic.rememberTooltipState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/** Tap or long-press for usage guidance; outside tap or Back dismisses it. */
@Composable
fun AppHelpTooltip(text: String, label: String = "Hướng dẫn") {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val colors = TooltipDefaults.richTooltipColors()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = state,
        focusable = true,
        tooltip = {
            RichTooltip(
                maxWidth = 280.dp,
                colors = colors,
                caretShape = TooltipDefaults.caretShape(),
            ) {
                Text(text = text, style = COUITheme.textStyles.body2, color = colors.contentColor)
            }
        },
    ) {
        IconButton(
            onClick = {
                if (state.isVisible) state.dismiss() else scope.launch { state.show() }
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = COUIIcons.Info,
                contentDescription = label,
                tint = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
