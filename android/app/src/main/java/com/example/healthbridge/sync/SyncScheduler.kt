package com.example.healthbridge.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.example.healthbridge.data.SecureSettings

/** Persistent deadlines survive process death; opening the app never postpones a due run. */
object SyncScheduler {
    const val ACTION_SYNC = "com.example.healthbridge.RUN_SCHEDULED_SYNC"
    const val EXTRA_EXACT = "exact_alarm"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms()

    fun isBatteryOptimizationExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun nextRunMillis(context: Context): Long = SecureSettings(context).nextSyncAtMillis

    @Synchronized
    fun restore(context: Context, reset: Boolean = false) {
        val settings = SecureSettings(context)
        if (!settings.syncEnabled) return cancel(context)
        val now = System.currentTimeMillis()
        val due = SyncPolicy.restoreRun(now, settings.nextSyncAtMillis, settings.syncIntervalHours, reset)
        settings.nextSyncAtMillis = due
        arm(context, due)
    }

    @Synchronized
    fun after(context: Context, delayMillis: Long) {
        val settings = SecureSettings(context)
        // A running sync must not recreate alarms after the user disables automatic sync.
        if (!settings.syncEnabled) return cancel(context)
        val due = System.currentTimeMillis() + delayMillis
        settings.nextSyncAtMillis = due
        arm(context, due)
    }

    @Synchronized
    fun nextInterval(context: Context) {
        val settings = SecureSettings(context)
        after(context, SyncPolicy.normalizeInterval(settings.syncIntervalHours) * 60L * 60 * 1000)
    }

    @Synchronized
    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, false))
        SecureSettings(context).nextSyncAtMillis = 0L
    }

    @Synchronized
    fun armRecovery(context: Context) {
        if (SecureSettings(context).syncEnabled) {
            // Do not move the due marker: queued work must still see that a transfer is due.
            arm(context, System.currentTimeMillis() + SyncPolicy.retryDelayMillis)
        }
    }

    private fun arm(context: Context, due: Long) {
        val manager = alarmManager(context)
        val at = maxOf(due, System.currentTimeMillis() + 5_000L)
        if (canScheduleExact(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, true))
                return
            } catch (_: SecurityException) {
                // Permission can be revoked between the capability check and registration.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, false))
    }

    private fun pendingIntent(context: Context, exact: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            2041,
            Intent(context, SyncAlarmReceiver::class.java)
                .setAction(ACTION_SYNC)
                .putExtra(EXTRA_EXACT, exact),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)
}
