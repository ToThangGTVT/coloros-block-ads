# COUI API evidence

Read before UI implementation:

- https://suqi8.github.io/coui/
- https://suqi8.github.io/coui/guide/getting-started
- https://suqi8.github.io/coui/components/
- https://github.com/suqi8/coui/blob/main/AGENT.md
- https://github.com/suqi8/coui/blob/main/CLAUDE.md
- `example/shared/src/commonMain/kotlin/ui/Theme.kt`
- `example/shared/src/commonMain/kotlin/SettingsPage.kt`
- `example/shared/src/commonMain/kotlin/AppContent.kt`
- `example/shared/src/commonMain/kotlin/utils/PageUtils.kt`

Implementation signatures verified against **published 1.1.0 sources JARs** from
Maven Central, not inferred from Material/Miuix APIs or only from upstream main:

| Area | Verified API |
| --- | --- |
| Theme | `theme.COUITheme`, `ThemeController`, `ColorSchemeMode.System/Light/Dark/MonetSystem` |
| Components | `basic.Card`, `Switch`, `Text`, `TextButton`, `SmallTitle` |
| Preferences | `preference.SwitchPreference`, `ListPreference`, `ListPreferenceEntry(text)` |
| Chrome | `basic.Scaffold`, `TopAppBar`, `COUIScrollBehavior`, `NavigationBar`, `NavigationBarItem` |
| Blur | `blur.rememberLayerBackdrop`, `layerBackdrop`, `textureBlur`, `BlurDefaults.BlurRadius`, `BlendColorEntry` |
| Navigation | COUI artifact supplies `androidx.navigation3.ui.NavDisplay`; runtime is Jetpack `navigation3-runtime:1.1.4` |

All five requested KMP coordinates resolve Android variants without replacing
coordinates with `-android` artifacts. Versions are pinned to 1.1.0 in the catalog.
Do not also add Jetpack `navigation3-ui`: COUI supplies those package/class names.

`coui-blur` requires minSdk 33. The user explicitly chose raising the whole app
to 33. No manifest override or hidden fallback is used.

Features consume wrappers from `core/designsystem`. Colors and type come from
COUITheme, spacing from verified COUI component Defaults because this version
does not provide a theme spacing object. Blur uses COUI's radius token and the
surface blend pattern from its example. Both bars use COUI's built-in system
insets; horizontal display-cutout padding is applied once around the page.

The app uses a lime seed (`#CCF66B`) with forest green (`#0B3D2E`).
Light and dark COUI palettes explicitly override the accent roles; a darker green
is used for text and controls on light surfaces to preserve contrast. Monet uses
the same lime seed rather than wallpaper colors. Brand tokens live in `AppTheme.kt`.
