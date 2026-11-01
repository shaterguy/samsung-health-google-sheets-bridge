package com.example.healthbridge

import android.app.Application
import com.example.healthbridge.data.SecureSettings
import com.example.healthbridge.sync.SyncWorker

class HealthBridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the persisted deadline without moving it forward every process start.
        if (SecureSettings(this).syncEnabled) SyncWorker.schedule(this)
    }
}
