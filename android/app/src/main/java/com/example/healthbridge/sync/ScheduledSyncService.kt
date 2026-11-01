package com.example.healthbridge.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.example.healthbridge.MainActivity
import com.example.healthbridge.R
import com.example.healthbridge.data.SecureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Visible only for an actual scheduled transfer; never a perpetual keep-alive service. */
class ScheduledSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("scheduled-sync", "예약 동기화", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 2042, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            2041,
            Notification.Builder(this, "scheduled-sync")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("건강정보 동기화 중")
                .setContentText("Health Connect → Google Sheets")
                .setContentIntent(open)
                .setOngoing(true)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        if (!SecureSettings(this).syncEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "healthbridge:scheduled-sync")
            .apply { acquire(10L * 60 * 1000) }
        scope.launch {
            try {
                val completed = withTimeoutOrNull(9L * 60 * 1000) {
                    AutomaticSync.run(applicationContext, foreground = true)
                    true
                } ?: false
                if (!completed) {
                    SecureSettings(applicationContext).lastSyncMessage = "처리 시간이 길어져 중단했습니다 · 저장한 행을 유지하고 다시 시도합니다"
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 dataSync budget exhaustion must stop the service immediately.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
