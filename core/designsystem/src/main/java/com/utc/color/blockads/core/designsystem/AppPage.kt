package com.utc.color.blockads.core.designsystem

import com.utc.color.blockads.core.common.R as L10n
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Filter
import io.github.suqi8.coui.kmp.icon.extended.HorizontalSplit
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay

enum class AppTab(val labelRes: Int) {
    Home(L10n.string.home),
    Log(L10n.string.log),
    Filters(L10n.string.filters),
    Firewall(L10n.string.firewall),
    Settings(L10n.string.settings);

    val label: String
        @Composable get() = androidx.compose.ui.res.stringResource(labelRes)
}

private const val TabKey = "blockads.tab"

/** NavEntry metadata distinguishes top-level tabs from pushed sub-pages. */
fun appTabMetadata(tab: AppTab): Map<String, Any> = mapOf(TabKey to tab.ordinal)

private fun Scene<*>.tabIndex() = metadata[TabKey] as? Int

private fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.tabTransition(
    easing: Easing
): ContentTransform? {
    initialState.tabIndex() ?: return null
    targetState.tabIndex() ?: return null
    // Keep the chart and long lists stationary instead of translating two full-screen
    // backdrop layers on every frame of a tab change.
    return fadeIn(tween(durationMillis = 140, easing = easing)) togetherWith
        fadeOut(tween(durationMillis = 90))
}

@Composable
fun <T : Any> AppNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    entryProvider: (T) -> NavEntry<T>,
) {
    val push = defaultTransitionSpec<T>()
    val pop = defaultPopTransitionSpec<T>()
    val predictivePop = defaultPredictivePopTransitionSpec<T>()
    fun isTab(key: T) = entryProvider(key).metadata[TabKey] is Int
    // Push dimming/corner clip only belongs to sub-pages; a tab-to-tab slide has neither.
    val current = backStack.last()
    var previous by remember { mutableStateOf<T?>(null) }
    var pagerEffects by
        remember(current) {
            mutableStateOf(previous.let { it == null || isTab(it) } && isTab(current))
        }
    SideEffect { previous = current }
    LaunchedEffect(current) {
        // Once settled, prepare for a predictive back gesture to the entry below.
        delay(180)
        pagerEffects =
            isTab(current) && backStack.getOrNull(backStack.lastIndex - 1)?.let(::isTab) != false
    }
    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        transitionSpec = { tabTransition(FastOutSlowInEasing) ?: push() },
        popTransitionSpec = { tabTransition(FastOutSlowInEasing) ?: pop() },
        predictivePopTransitionSpec = { edge ->
            tabTransition(LinearEasing) ?: predictivePop(edge)
        },
        transitionEffects =
            if (pagerEffects) NavDisplayTransitionEffects.None
            else NavDisplayTransitionEffects.Default,
        entryProvider = entryProvider,
    )
}

private val LocalTabBarHeight = compositionLocalOf { 0.dp }

/**
 * Hosts the bottom tab bar outside the pages so it stays put while pages slide underneath.
 * Sub-pages pass [showBar] false and the bar slides away.
 */
@Composable
fun AppTabHost(
    selectedTab: AppTab,
    onTab: (AppTab) -> Unit,
    showBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val surface = COUITheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    // Root overlay host must contain both pages and tab bar so modal sheets intercept
    // touches above the bar as well as above the current page.
    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { _ ->
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                CompositionLocalProvider(LocalTabBarHeight provides barHeight) { content() }
            }
            AnimatedVisibility(
                visible = showBar,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                // Measured inside so the hidden bar keeps its height for the tab pages below.
                BlurredBar(
                    backdrop,
                    Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                ) {
                    NavigationBar(
                        modifier =
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                            ),
                        color = Color.Transparent,
                    ) {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { onTab(tab) },
                                icon =
                                    when (tab) {
                                        AppTab.Home -> COUIIcons.HorizontalSplit
                                        AppTab.Log -> COUIIcons.Search
                                        AppTab.Filters -> COUIIcons.Filter
                                        AppTab.Firewall -> COUIIcons.Lock
                                        AppTab.Settings -> COUIIcons.Settings
                                    },
                                label = tab.label,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    showTopBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val strings = rememberAppStrings()
    val surface = COUITheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val scrollBehavior = COUIScrollBehavior()
    // Sub-pages hide the tab bar, so only tab pages leave room for it.
    val tabBar = if (onBack == null) LocalTabBarHeight.current else 0.dp
    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        topBar = {
            if (showTopBar)
            BlurredBar(backdrop, blurEnabled = scrollable) {
                TopAppBar(
                    title = title,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onBack != null)
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = COUIIcons.Back,
                                    contentDescription = strings.getString(L10n.string.back),
                                    tint = COUITheme.colorScheme.onSurface,
                                )
                            }
                    },
                )
            }
        },
    ) { padding ->
        val bottom: Dp = maxOf(padding.calculateBottomPadding(), tabBar)
        if (!scrollable) {
            Box(
                Modifier.fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = bottom)
                    .consumeWindowInsets(padding)
                    .layerBackdrop(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                content()
            }
        } else {
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
                        bottom = bottom + AppSpacing.page,
                    ),
            ) {
                item(key = "phase0-content") { content() }
            }
        }
    }
}

@Composable
private fun BlurredBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Fixed content starts below the bar; sampling its backdrop would pull
    // the segmented control's green fill upward into the title area.
    if (!blurEnabled) {
        Box(modifier.background(COUITheme.colorScheme.surface)) { content() }
        return
    }
    Box(
        modifier.textureBlur(
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
