package com.utc.color.blockads

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
    private val settings: SettingsRepository,
    engine: EngineBridge,
    val tunnel: TunnelController,
) : ViewModel() {
    val theme =
        settings.theme.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemeMode.System,
        )
    private val _version = MutableStateFlow("Đang tải…")
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
                _error.value = "Không lưu được chủ đề. Vui lòng thử lại."
            }
        }
    }
}
