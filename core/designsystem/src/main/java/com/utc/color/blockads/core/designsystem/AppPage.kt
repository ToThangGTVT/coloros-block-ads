package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Filter
import io.github.suqi8.coui.kmp.icon.extended.HorizontalSplit
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.theme.COUITheme

enum class AppTab(val label: String) {
    Home("Trang chủ"),
    Log("Nhật ký"),
    Filters("Bộ lọc"),
    Settings("Cài đặt"),
}

@Composable
fun <T : Any> AppNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    entryProvider: (T) -> NavEntry<T>,
) {
    NavDisplay(backStack = backStack, onBack = onBack, entryProvider = entryProvider)
}

@Composable
fun AppPage(
    title: String,
    selectedTab: AppTab,
    onTab: (AppTab) -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val surface = COUITheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val scrollBehavior = COUIScrollBehavior()
    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = title,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { if (onBack != null) AppAction("Quay lại", onBack) },
                )
            }
        },
        bottomBar = {
            BlurredBar(backdrop) {
                NavigationBar(color = Color.Transparent) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { onTab(tab) },
                            icon =
                                when (tab) {
                                    AppTab.Home -> COUIIcons.HorizontalSplit
                                    AppTab.Log -> COUIIcons.Search
                                    AppTab.Filters -> COUIIcons.Filter
                                    AppTab.Settings -> COUIIcons.Settings
                                },
                            label = tab.label,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .layerBackdrop(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding =
                PaddingValues(
                    start = AppSpacing.page,
                    end = AppSpacing.page,
                    top = padding.calculateTopPadding() + AppSpacing.gap,
                    bottom = padding.calculateBottomPadding() + AppSpacing.page,
                ),
        ) {
            item(key = "phase0-content") { content() }
        }
    }
}

@Composable
private fun BlurredBar(backdrop: LayerBackdrop, content: @Composable () -> Unit) {
    Box(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = BlurDefaults.BlurRadius,
            colors =
                BlurDefaults.blurColors(
                    blendColors =
                        listOf(
                            BlendColorEntry(
                                color = COUITheme.colorScheme.surface.copy(alpha = 0.8f)
                            )
                        )
                ),
        )
    ) {
        content()
    }
}
