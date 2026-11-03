package com.utc.color.blockads.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Application-lifetime bounded writer; JNI callbacks only enqueue metadata batches. */
class QueryLogRepository(
    private val database: QueryDatabase,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun day(time: Long) =
        java.time.Instant.ofEpochMilli(time)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val days = flow {
        while (true) {
            emit(day(System.currentTimeMillis()))
            delay(60000)
        }
    }
        .distinctUntilChanged()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val today = days.flatMapLatest {
        database.queries().observeStats(it).map { value -> value ?: DailyStats(it) }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val week = days.flatMapLatest {
        val today =
            java.time.Instant.ofEpochMilli(it)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
        val dates =
            (6 downTo 0).map { offset ->
                today
                    .minusDays(offset.toLong())
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        database.queries().observeHistory(dates.first()).map { rows ->
            dates.map { day ->
                rows
                    .firstOrNull { row -> row.day == day }
                    ?.let { row ->
                        com.utc.color.blockads.core.common.TrafficDay(day, row.total, row.blocked)
                    } ?: com.utc.color.blockads.core.common.TrafficDay(day, 0, 0)
            }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val topDomains = days.flatMapLatest { database.queries().topDomains(it) }

    suspend fun clearLogs() = database.queries().clearLogs()

    private val batches = Channel<String>(32)

    fun searchPages(search: String, status: Int, uid: Int = -1) =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
                database.queries().pagingSource(search, status, uid)
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
                val batch = JSONObject(json)
                val events = batch.getJSONArray("events")
                val rows =
                    (0 until events.length()).map { index ->
                        events.getJSONObject(index).let { event ->
                            QueryEntity(
                                timestamp = event.getLong("timestamp"),
                                domain = event.getString("domain"),
                                uid =
                                    if (event.isNull("uid")) null
                                    else event.optInt("uid", -1).takeIf { it >= 0 },
                                blocked = event.getBoolean("blocked"),
                                matchedRule =
                                    event.optString("matchedRule").takeIf {
                                        it != "null" && it.isNotBlank()
                                    },
                                protocol = event.optString("protocol", "dns"),
                            )
                        }
                    }
                database.withTransaction {
                    database.queries().insertBatch(rows)
                    val totals = batch.getJSONArray("totals")
                    for (index in 0 until totals.length()) {
                        val value = totals.getJSONObject(index)
                        database
                            .queries()
                            .addStats(
                                day(value.getLong("timestamp")),
                                value.getLong("total"),
                                value.getLong("blocked"),
                            )
                    }
                }
            }
        }
    }

    fun onDnsEvents(events: String) {
        batches.trySend(events)
    }

    suspend fun latest(domain: String) = database.queries().latest(domain)

    suspend fun prune() =
        database
            .queries()
            .deleteBefore(
                System.currentTimeMillis() -
                    settings.logRetentionDays.first().toLong() * 24 * 60 * 60 * 1000
            )
}
