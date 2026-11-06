package com.utc.color.blockads

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.utc.color.blockads.core.data.QueryDatabase
import com.utc.color.blockads.core.data.QueryEntity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only present in the profile-generation variant, never debug or release APKs. */
@AndroidEntryPoint
class ProfileSeedActivity : ComponentActivity() {
    @Inject lateinit var database: QueryDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_seed)
        val status = findViewById<TextView>(R.id.profile_status)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.queries().clearLogs()
                val now = System.currentTimeMillis()
                database.queries().insertBatch(
                    List(5000) {
                        QueryEntity(
                            timestamp = now - it,
                            domain = "ads$it.profile.test",
                            uid = android.os.Process.myUid(),
                            blocked = it % 2 == 0,
                            matchedRule = "||profile.test^",
                        )
                    },
                )
            }
            status.text = "PROFILE_READY"
        }
    }
}
