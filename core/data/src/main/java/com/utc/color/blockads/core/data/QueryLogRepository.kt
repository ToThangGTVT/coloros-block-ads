package com.utc.color.blockads.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray

/** Application-lifetime bounded writer; JNI callbacks only enqueue metadata batches. */
class QueryLogRepository(private val database: QueryDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batches = Channel<String>(32)

    fun searchPages(search: String, status: Int) =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
                database.queries().pagingSource(search, status)
            }
            .flow

    val pages =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
                database.queries().pagingSource()
            }
            .flow

    init {
        scope.launch { runCatching { prune() } }
        scope.launch {
            for (json in batches) runCatching {
                val events = JSONArray(json)
                val rows =
                    (0 until events.length()).map { index ->
                        events.getJSONObject(index).let { event ->
                            QueryEntity(
                                timestamp = event.getLong("timestamp"),
                                domain = event.getString("domain"),
                                uid = null,
                                blocked = event.getBoolean("blocked"),
                                matchedRule =
                                    event.optString("matchedRule").takeIf {
                                        it != "null" && it.isNotBlank()
                                    },
                            )
                        }
                    }
                database.queries().insertBatch(rows)
                prune()
            }
        }
    }

    fun onDnsEvents(events: String) {
        batches.trySend(events)
    }

    suspend fun latest(domain: String) = database.queries().latest(domain)

    suspend fun prune() =
        database.queries().deleteBefore(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
}
