package com.example.healthbridge.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.example.healthbridge.data.SecureSettings

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutomaticSync.run(applicationContext, foreground = false)
        return Result.success()
    }

    companion object {
        private const val uniqueName = "health-connect-periodic-sync-v2"
        private const val immediateName = "health-connect-alarm-sync-v2"
        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context, reset: Boolean = false) {
            if (!SecureSettings(context).syncEnabled) return cancel(context)
            val work = WorkManager.getInstance(context)
            // Replace the old Wi-Fi-only work once, then retain the new fallback's cadence.
            work.cancelUniqueWork("health-connect-periodic-sync")
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15L, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            work.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.KEEP, request)
            SyncScheduler.restore(context, reset)
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(immediateName, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork("health-connect-periodic-sync")
            work.cancelUniqueWork(uniqueName)
            work.cancelUniqueWork(immediateName)
            SyncScheduler.cancel(context)
            context.stopService(Intent(context, ScheduledSyncService::class.java))
        }

        fun isNetworkConnected(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        fun currentBatteryPercent(context: Context): Int? {
            val value = context.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (value in 0..100) return value
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) level * 100 / scale else null
        }
    }
}
