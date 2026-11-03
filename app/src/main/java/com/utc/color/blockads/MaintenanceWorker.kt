package com.utc.color.blockads

import android.content.Context
import androidx.work.*
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.filter.FilterRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
    private val filters: FilterRepository,
    private val queries: QueryLogRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        queries.prune()
        var successful = true
        filters.lists
            .first()
            .filter { it.enabled }
            .forEach { if (!filters.download(it.id)) successful = false }
        return if (successful) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "log-retention",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<LogCleanupWorker>(24, TimeUnit.HOURS).build(),
                )
            val request =
                PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "filter-maintenance",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}

class LogCleanupWorker(
    context: Context,
    params: WorkerParameters,
    private val queries: QueryLogRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            queries.prune()
            Result.success()
        } catch (_: java.io.IOException) {
            Result.retry()
        }
}

class AppWorkerFactory
@Inject
constructor(private val filters: FilterRepository, private val queries: QueryLogRepository) :
    WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            MaintenanceWorker::class.java.name ->
                MaintenanceWorker(appContext, workerParameters, filters, queries)
            LogCleanupWorker::class.java.name ->
                LogCleanupWorker(appContext, workerParameters, queries)
            else -> null
        }
}
