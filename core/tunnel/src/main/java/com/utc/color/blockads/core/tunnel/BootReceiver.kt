package com.utc.color.blockads.core.tunnel

import android.content.*
import com.utc.color.blockads.core.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (settings.autoStart.first() && android.net.VpnService.prepare(context) == null)
                    context.startForegroundService(
                        Intent(context, BlockAdsVpnService::class.java)
                            .setAction(BlockAdsVpnService.Start)
                    )
            } catch (_: RuntimeException) {
                /* Consent or OEM policy may require opening the app. */
            } finally {
                pending.finish()
            }
        }
    }
}
