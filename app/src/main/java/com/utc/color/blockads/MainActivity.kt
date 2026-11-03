package com.utc.color.blockads

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.utc.color.blockads.core.designsystem.AppTheme
import com.utc.color.blockads.core.designsystem.appUsesDarkColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { viewModel.certificates.refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val version by viewModel.version.collectAsStateWithLifecycle()
            val error by viewModel.error.collectAsStateWithLifecycle()
            val filterStatus by viewModel.filters.status.collectAsStateWithLifecycle()
            val tunnel by viewModel.tunnel.state.collectAsStateWithLifecycle()
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val permission =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        viewModel.tunnel.start()
                        if (
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                        )
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            val localNetworkPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    val consent = viewModel.tunnel.prepare()
                    if (consent == null) viewModel.tunnel.start() else permission.launch(consent)
                }
            AppTheme(theme) {
                val dark = appUsesDarkColors()
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                BlockAdsApp(
                    theme,
                    viewModel::setTheme,
                    version,
                    error,
                    tunnel,
                    filterStatus,
                    viewModel.settings,
                    viewModel.filters,
                    viewModel.queries,
                    viewModel.apps,
                    viewModel.certificates,
                ) { enabled ->
                    if (!enabled) viewModel.tunnel.stop()
                    else {
                        if (
                            Build.VERSION.SDK_INT >= 37 &&
                                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                                    PackageManager.PERMISSION_GRANTED
                        ) {
                            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else {
                            val consent = viewModel.tunnel.prepare()
                            if (consent == null) viewModel.tunnel.start()
                            else permission.launch(consent)
                        }
                    }
                }
            }
        }
    }
}
