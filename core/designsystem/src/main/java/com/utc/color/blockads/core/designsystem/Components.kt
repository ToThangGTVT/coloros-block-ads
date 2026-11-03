package com.utc.color.blockads.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
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

@Composable
fun AppSectionTitle(text: String) {
    SmallTitle(text = text)
}

@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
}

@Composable
fun AppSwitchPreference(
    title: String,
    summary: String,
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
    )
}

@Composable
fun AppAction(label: String, onClick: () -> Unit) {
    TextButton(text = label, onClick = onClick)
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
fun AppQueryRow(domain: String, blocked: Boolean, time: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = AppSpacing.card, onClick = onClick) {
        Text(domain, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
        Text(
            (if (blocked) "Đã chặn" else "Cho phép") + " · " + time,
            style = COUITheme.textStyles.footnote1,
            color = if (blocked) COUITheme.colorScheme.error else COUITheme.colorScheme.primary,
        )
    }
}
