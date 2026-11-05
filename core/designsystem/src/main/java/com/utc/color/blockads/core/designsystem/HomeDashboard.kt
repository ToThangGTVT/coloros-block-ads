package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.collapse
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utc.color.blockads.core.common.R as L10n
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.theme.COUITheme

private enum class HomePanelPosition { Collapsed, Expanded }

/** Home's branded hero and COUI action/statistics panel. All values are live data. */
@Composable
fun AppHomeDashboard(
    title: String,
    checked: Boolean,
    canToggle: Boolean,
    rules: String,
    total: Long,
    blocked: Long,
    onProtection: (Boolean) -> Unit,
    httpsEnabled: Boolean,
    httpsToggleEnabled: Boolean,
    onHttpsProtection: (Boolean) -> Unit,
    onSettings: () -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    statistics: @Composable ColumnScope.() -> Unit,
) {
    val strings = rememberAppStrings()
    val palette = COUITheme.colorScheme
    val number = remember(strings.configuration.locales[0]) {
        java.text.NumberFormat.getIntegerInstance(strings.configuration.locales[0])
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val forest = AppBrandColors.Forest
    val lime = AppBrandColors.Lime
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var wasExpanded by rememberSaveable { mutableStateOf(false) }
    val panel = remember {
        AnchoredDraggableState(if (wasExpanded) HomePanelPosition.Expanded else HomePanelPosition.Collapsed)
    }
    LaunchedEffect(panel.settledValue) { wasExpanded = panel.settledValue == HomePanelPosition.Expanded }
    var heroHeight by remember { mutableIntStateOf(0) }
    val bodyScroll = rememberScrollState()
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds()
            .background(Brush.verticalGradient(listOf(Color(0xFF123E2D), Color(0xFF082F26)))),
    ) {
        val expandedTop = with(density) { 64.dp.toPx() }.coerceAtMost(constraints.maxHeight * 0.2f)
        val collapsedTop = (if (heroHeight > 0) heroHeight.toFloat() else with(density) { 440.dp.toPx() })
            .coerceAtMost((constraints.maxHeight - with(density) { 200.dp.toPx() }).coerceAtLeast(expandedTop))
            .coerceAtLeast(expandedTop)
        SideEffect {
            panel.updateAnchors(DraggableAnchors {
                HomePanelPosition.Expanded at expandedTop
                HomePanelPosition.Collapsed at collapsedTop
            })
        }
        val panelTop = panel.offset.takeUnless { it.isNaN() } ?: collapsedTop
        val nestedScroll = remember(panel, expandedTop, collapsedTop) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0f)
                        Offset(0f, panel.dispatchRawDelta(available.y)) else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0f)
                        Offset(0f, panel.dispatchRawDelta(available.y)) else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y < 0f && panel.requireOffset() > expandedTop) {
                        panel.animateTo(HomePanelPosition.Expanded)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val offset = panel.requireOffset()
                    if ((offset > expandedTop && offset < collapsedTop) || (available.y > 0f && bodyScroll.value == 0)) {
                        val target = if (available.y > 0f || offset > (expandedTop + collapsedTop) / 2f)
                            HomePanelPosition.Collapsed else HomePanelPosition.Expanded
                        panel.animateTo(target)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().onSizeChanged { heroHeight = it.height }.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_home_shield), contentDescription = null, tint = lime, modifier = Modifier.size(24.dp))
                Text(
                    strings.getString(L10n.string.app_display_name),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = COUITheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                IconButton(onClick = onSettings) {
                    Icon(COUIIcons.Settings, strings.getString(L10n.string.settings), tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Box(
                Modifier.padding(top = 28.dp, bottom = 26.dp).size(212.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(212.dp).background(lime.copy(alpha = if (checked) 0.05f else 0.02f), CircleShape))
                Box(Modifier.size(174.dp).background(lime.copy(alpha = if (checked) 0.08f else 0.03f), CircleShape))
                Card(
                    modifier = Modifier.size(128.dp).semantics {
                        role = Role.Switch
                        toggleableState = ToggleableState(checked)
                        if (!canToggle) disabled()
                    },
                    cornerRadius = 64.dp,
                    colors = CardDefaults.defaultColors(
                        color = if (checked) lime else Color(0xFFB5C9C0),
                        contentColor = forest,
                        pressedColor = Color(0xFFB9E456),
                    ),
                    onClick = if (canToggle) ({ onProtection(!checked) }) else null,
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Icon(painterResource(R.drawable.ic_home_shield), null, tint = forest, modifier = Modifier.size(32.dp))
                        Text(
                            title,
                            style = COUITheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                            color = forest,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Text(
                strings.getString(L10n.string.home_device_protection),
                style = COUITheme.textStyles.footnote1.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                rules,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
                style = COUITheme.textStyles.footnote1,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    strings.getString(L10n.string.home_blocked_today) to blocked,
                    strings.getString(L10n.string.total_requests) to total,
                ).forEach { (label, value) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        cornerRadius = 12.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                        colors = CardDefaults.defaultColors(color = Color.White.copy(alpha = 0.11f)),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(number.format(value), style = COUITheme.textStyles.title2.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = Color.White)
                            Text(label, style = COUITheme.textStyles.footnote1, color = Color.White.copy(alpha = 0.74f), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth()
                .offset { IntOffset(0, panelTop.roundToInt()) }
                .height(with(density) { (constraints.maxHeight - panelTop).coerceAtLeast(0f).toDp() })
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .testTag("home-panel")
                .nestedScroll(nestedScroll)
                .anchoredDraggable(panel, Orientation.Vertical)
                .semantics {
                    if (panel.settledValue == HomePanelPosition.Collapsed) {
                        expand { scope.launch { panel.animateTo(HomePanelPosition.Expanded) }; true }
                    } else {
                        collapse { scope.launch { panel.animateTo(HomePanelPosition.Collapsed) }; true }
                    }
                },
            cornerRadius = 0.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = palette.surfaceContainer),
        ) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).background(palette.onSurface.copy(alpha = 0.18f), CircleShape))
            }
            AppContentTabs(
                labels = listOf(strings.getString(L10n.string.home_actions), strings.getString(L10n.string.home_statistics)),
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(Modifier.fillMaxWidth().weight(1f).testTag("home-panel-content").verticalScroll(bodyScroll).padding(top = 8.dp, bottom = 8.dp)) {
                notices()
                if (selected == 0) {
                    AppSwitchPreference(
                        title = strings.getString(L10n.string.home_dns_protection),
                        summary = strings.getString(L10n.string.home_dns_description),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        checked = checked,
                        enabled = canToggle,
                        onCheckedChange = onProtection,
                    )
                    AppSwitchPreference(
                        title = strings.getString(L10n.string.home_https_protection),
                        summary = strings.getString(L10n.string.home_https_description),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        checked = httpsEnabled,
                        enabled = httpsToggleEnabled,
                        onCheckedChange = onHttpsProtection,
                    )
                } else {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = statistics)
                }
            }
        }
    }
}
