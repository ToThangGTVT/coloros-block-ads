package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.ListPreference
import io.github.suqi8.coui.kmp.preference.ListPreferenceEntry
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

// COUI exposes component defaults rather than a theme spacing object.
object AppSpacing {
    val page
        get() = TopAppBarDefaults.TitlePadding

    val gap
        get() = BasicComponentDefaults.InsideMargin.calculateTopPadding()

    val card
        get() = BasicComponentDefaults.InsideMargin
}

@Composable
fun AppColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        content = content,
    )
}

@Composable
fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = AppSpacing.card, content = content)
}

@Composable
fun AppPreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
fun AppText(text: String, prominent: Boolean = false) {
    Text(
        text = text,
        style = if (prominent) COUITheme.textStyles.title2 else COUITheme.textStyles.body1,
        color = COUITheme.colorScheme.onSurface,
    )
}

/**
 * Short secondary line (footnote, summary color) for hints that should not compete with content.
 */
@Composable
fun AppHint(text: String) {
    Text(
        text = text,
        style = COUITheme.textStyles.footnote1,
        color = COUITheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
fun AppSectionTitle(text: String) {
    SmallTitle(text = text)
}

@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
}

/** Headline status with details under it and the switch at the end of the row. */
@Composable
fun AppProtectionCard(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                AppText(title, prominent = true)
                details()
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
fun AppStatistics(total: Long, blocked: Long) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(
                "Tổng request" to java.text.NumberFormat.getIntegerInstance().format(total),
                "Đã chặn" to java.text.NumberFormat.getIntegerInstance().format(blocked),
                "Tỷ lệ chặn" to
                    (if (total == 0L) "0%"
                    else
                        String.format(
                            java.util.Locale.getDefault(),
                            "%.1f%%",
                            blocked * 100.0 / total,
                        )),
            )
            .forEach { (label, value) ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    AppText(value, prominent = true)
                    AppHint(label)
                }
            }
    }
}

@Composable
fun AppSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun AppChoicePreference(
    title: String,
    entries: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val choices = remember(entries) { entries.map { ListPreferenceEntry(text = it) } }
    ListPreference(
        entries = choices,
        selectedIndex = selected,
        onSelectedIndexChange = onSelected,
        title = title,
        cancelButtonText = "Hủy",
        renderInRootScaffold = false,
    )
}

/** Navigation or one-shot action row inside an [AppPreferenceGroup], aligned with other rows. */
@Composable
fun AppArrowPreference(title: String, summary: String? = null, onClick: () -> Unit) {
    ArrowPreference(title = title, summary = summary, onClick = onClick)
}

/** Padded content inside an [AppPreferenceGroup], e.g. text fields under a choice. */
@Composable
fun AppGroupContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(AppSpacing.card),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        content = content,
    )
}

/** Buttons at the bottom of a card share its width equally so they line up. */
@Composable
fun AppButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.gap),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        content = content,
    )
}

@Composable
fun RowScope.AppButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors =
            if (primary) ButtonDefaults.textButtonColorsPrimary()
            else ButtonDefaults.textButtonColors(),
    )
}

/** Single full-width card button. */
@Composable
fun AppAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    AppButtonRow { AppButton(label, primary, onClick) }
}

@Composable
fun AppTextInput(
    value: String,
    label: String,
    multiline: Boolean = false,
    error: Boolean = false,
    onChange: (String) -> Unit,
) {
    io.github.suqi8.coui.kmp.basic.TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        isError = error,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 8 else 1,
        singleLine = !multiline,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun AppBottomSheet(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        renderInRootScaffold = false,
    ) {
        // COUI 1.1.0 handles IME insets but its sheet content has no navigation-bar padding.
        Box(
            Modifier.padding(
                bottom =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        AppSpacing.gap
            )
        ) {
            content()
        }
    }
}

@Composable
fun AppQueryRow(
    domain: String,
    blocked: Boolean,
    time: String,
    appName: String = "Chưa xác định",
    icon: android.graphics.Bitmap? = null,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = AppSpacing.card, onClick = onClick) {
        AppIdentity(appName, icon)
        Text(domain, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
        Text(
            (if (blocked) "Đã chặn" else "Cho phép") + " · " + time,
            style = COUITheme.textStyles.footnote1,
            color = if (blocked) COUITheme.colorScheme.error else COUITheme.colorScheme.primary,
        )
    }
}

@Composable
fun AppIdentity(label: String, bitmap: android.graphics.Bitmap?) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.gap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (bitmap != null)
            io.github.suqi8.coui.kmp.basic.Icon(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier =
                    Modifier.size(io.github.suqi8.coui.kmp.basic.NavigationBarDefaults.IconSize),
            )
        AppText(label)
    }
}

/** Non-clickable header row of an [AppPreferenceGroup], aligned with the preference rows below. */
@Composable
fun AppItemHeader(title: String, summary: String? = null, bitmap: android.graphics.Bitmap? = null) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction =
            bitmap?.let {
                {
                    io.github.suqi8.coui.kmp.basic.Icon(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier =
                            Modifier.padding(end = AppSpacing.gap)
                                .size(io.github.suqi8.coui.kmp.basic.NavigationBarDefaults.IconSize),
                    )
                }
            },
    )
}

/** Read-only row with a trailing value, aligned with preference rows. */
@Composable
fun AppValueRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                // Same style as the title so both share a baseline.
                style = COUITheme.textStyles.headline1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
}
