package com.utc.color.blockads

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BlockAdsApplication : Application(), androidx.work.Configuration.Provider {
    @javax.inject.Inject lateinit var workers: AppWorkerFactory
    override val workManagerConfiguration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(workers).build()

    override fun onCreate() {
        super.onCreate()
        MaintenanceWorker.schedule(this)
    }
}
