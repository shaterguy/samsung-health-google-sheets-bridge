package com.example.healthbridge.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.healthbridge.data.SecureSettings

class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SecureSettings(context)
        if (!settings.syncEnabled) return
        if (intent.action != SyncScheduler.ACTION_SYNC) {
            // BOOT_COMPLETED and package replacement only restore; never start a dataSync
            // foreground service directly from a boot broadcast on Android 15+.
            SyncWorker.schedule(context, reset = intent.action == Intent.ACTION_TIME_CHANGED)
            return
        }
        if (!settings.configured()) {
            SyncScheduler.nextInterval(context)
            return
        }
        SyncScheduler.armRecovery(context)
        if (intent.getBooleanExtra(SyncScheduler.EXTRA_EXACT, false) && SyncScheduler.canScheduleExact(context)) {
            try {
                context.startForegroundService(Intent(context, ScheduledSyncService::class.java))
                return
            } catch (_: IllegalStateException) {
                // An OEM restriction can deny the FGS exemption: durable work remains available.
            } catch (_: SecurityException) {
                // Revoked special access must not crash the receiver.
            }
        }
        // Inexact alarms do not grant a background FGS launch exemption.
        SyncWorker.enqueue(context)
    }
}
