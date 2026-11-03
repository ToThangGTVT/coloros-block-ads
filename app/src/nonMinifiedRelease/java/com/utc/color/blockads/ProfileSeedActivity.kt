package com.utc.color.blockads

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.data.QueryDatabase
import com.utc.color.blockads.core.data.QueryEntity
import com.utc.color.blockads.core.designsystem.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

/** Only present in the profile-generation variant, never debug or release APKs. */
@AndroidEntryPoint
class ProfileSeedActivity : ComponentActivity() {
    @Inject lateinit var database: QueryDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    database.queries().clearLogs()
                    val now = System.currentTimeMillis()
                    database
                        .queries()
                        .insertBatch(
                            (0 until 5000).map {
                                QueryEntity(
                                    timestamp = now - it,
                                    domain = "ads$it.profile.test",
                                    uid = android.os.Process.myUid(),
                                    blocked = it % 2 == 0,
                                    matchedRule = "||profile.test^",
                                )
                            }
                        )
                }
                ready = true
            }
            AppTheme(ThemeMode.System) {
                Box(
                    Modifier.fillMaxSize().safeDrawingPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(if (ready) "PROFILE_READY" else "PROFILE_PREPARING")
                }
            }
        }
    }
}
