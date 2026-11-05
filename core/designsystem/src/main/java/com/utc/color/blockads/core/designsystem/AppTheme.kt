package com.utc.color.blockads.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.utc.color.blockads.core.common.ThemeMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme

object AppBrandColors {
    val Lime = Color(0xFFCCF66B)
    val Forest = Color(0xFF0B3D2E)
    val Green = Color(0xFF3D7418)
}

@Composable fun appUsesDarkColors(): Boolean = COUITheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun AppTheme(mode: ColorSchemeMode, content: @Composable () -> Unit) {
    val controller =
        remember(mode) {
            ThemeController(
                mode,
                keyColor = AppBrandColors.Lime,
                lightColors = lightColorScheme(
                    primary = AppBrandColors.Green,
                    onPrimary = Color.White,
                    primaryVariant = AppBrandColors.Forest,
                    onPrimaryVariant = AppBrandColors.Lime,
                    primaryContainer = AppBrandColors.Lime,
                    onPrimaryContainer = AppBrandColors.Forest,
                    disabledPrimary = AppBrandColors.Green.copy(alpha = 0.3f),
                    disabledPrimaryButton = AppBrandColors.Lime.copy(alpha = 0.3f),
                    disabledOnPrimaryButton = AppBrandColors.Forest.copy(alpha = 0.54f),
                    tertiaryContainer = AppBrandColors.Lime.copy(alpha = 0.24f),
                    onTertiaryContainer = AppBrandColors.Forest,
                    tertiaryContainerVariant = AppBrandColors.Lime.copy(alpha = 0.16f),
                    sliderKeyPointForeground = AppBrandColors.Green,
                ),
                darkColors = darkColorScheme(
                    primary = AppBrandColors.Lime,
                    onPrimary = AppBrandColors.Forest,
                    primaryVariant = AppBrandColors.Lime,
                    onPrimaryVariant = AppBrandColors.Forest,
                    primaryContainer = AppBrandColors.Lime,
                    onPrimaryContainer = AppBrandColors.Forest,
                    disabledPrimary = AppBrandColors.Lime.copy(alpha = 0.3f),
                    disabledOnPrimary = AppBrandColors.Forest,
                    disabledPrimaryButton = AppBrandColors.Lime.copy(alpha = 0.3f),
                    disabledOnPrimaryButton = AppBrandColors.Forest.copy(alpha = 0.54f),
                    tertiaryContainer = AppBrandColors.Lime.copy(alpha = 0.16f),
                    onTertiaryContainer = AppBrandColors.Lime,
                    tertiaryContainerVariant = AppBrandColors.Lime.copy(alpha = 0.12f),
                    sliderKeyPointForeground = AppBrandColors.Lime,
                ),
            )
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
