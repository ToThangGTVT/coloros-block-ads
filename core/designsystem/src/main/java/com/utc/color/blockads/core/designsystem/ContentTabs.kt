package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.TabRowDefaults
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.theme.COUITheme

/** Shared COUI segmented tabs for Home, query logs and filters. */
@Composable
fun AppContentTabs(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val palette = COUITheme.colorScheme
    TabRowWithContour(
        tabs = labels,
        selectedTabIndex = selected,
        onTabSelected = onSelect,
        modifier = modifier.fillMaxWidth(),
        minWidth = 0.dp,
        maxWidth = 1000.dp,
        cornerRadius = 24.dp,
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = palette.onSurface.copy(alpha = 0.04f),
            contentColor = palette.onSurfaceVariantSummary,
            selectedBackgroundColor = AppBrandColors.Forest,
            selectedContentColor = AppBrandColors.Lime,
        ),
    )
}
