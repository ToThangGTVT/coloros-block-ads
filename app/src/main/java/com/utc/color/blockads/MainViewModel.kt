package com.utc.color.blockads

import com.utc.color.blockads.core.common.R as L10n
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import com.utc.color.blockads.core.tunnel.TunnelController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class MainViewModel
@Inject
constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    val settings: SettingsRepository,
    engine: EngineBridge,
    val tunnel: TunnelController,
    val filters: com.utc.color.blockads.core.filter.FilterRepository,
    val queries: com.utc.color.blockads.core.data.QueryLogRepository,
    val certificates: com.utc.color.blockads.core.certificate.CertificateRepository,
    val apps: com.utc.color.blockads.core.data.AppsRepository,
    val trafficPolicy: com.utc.color.blockads.core.tunnel.TrafficPolicyRepository,
) : ViewModel() {
    val theme =
        settings.theme.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemeMode.System,
        )
    private val _version = MutableStateFlow(context.getString(L10n.string.loading))
    val version = _version.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch { _version.value = withContext(Dispatchers.IO) { engine.version() } }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            try {
                settings.setTheme(mode)
                _error.value = null
            } catch (_: IOException) {
                _error.value = context.getString(L10n.string.theme_save_failed)
            }
        }
    }
}
