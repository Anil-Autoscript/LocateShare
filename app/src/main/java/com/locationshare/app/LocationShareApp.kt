package com.locationshare.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import com.locationshare.app.worker.LocationCheckWorker
import java.util.concurrent.TimeUnit

class LocationShareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicCheck()
    }

    private fun schedulePeriodicCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 15 minutes is the Android OS minimum interval for PeriodicWorkRequest.
        // This only checks a boolean flag on GitHub — it does NOT poll GPS on
        // a schedule. GPS is touched only if/when the flag is true.
        val request = PeriodicWorkRequestBuilder<LocationCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "location_flag_check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
