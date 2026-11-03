package com.utc.color.blockads.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.utc.color.blockads.core.common.ThemeMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

@Composable fun appUsesDarkColors(): Boolean = COUITheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun AppTheme(mode: ColorSchemeMode, content: @Composable () -> Unit) {
    val controller =
        remember(mode) {
            ThemeController(mode, keyColor = Color(0xFF3482FF))
        }
    COUITheme(controller = controller, content = content)
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    AppTheme(
        mode =
            when (mode) {
                ThemeMode.System -> ColorSchemeMode.System
                ThemeMode.Light -> ColorSchemeMode.Light
                ThemeMode.Dark -> ColorSchemeMode.Dark
                ThemeMode.Monet -> ColorSchemeMode.MonetSystem
            },
        content = content,
    )
}
