package com.utc.color.blockads.core.designsystem

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberAppStrings(): Resources {
    // Observe configuration so localized labels update when the app locale changes.
    LocalConfiguration.current
    return LocalContext.current.resources
}
