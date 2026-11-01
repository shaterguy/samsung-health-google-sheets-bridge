package com.example.healthbridge.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.healthbridge.sync.SyncPolicy

class SecureSettings(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "health_bridge_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var spreadsheetId: String
        get() = (prefs.getString("spreadsheet_id", "") ?: "").trim()
        set(value) = prefs.edit().putString("spreadsheet_id", value.trim()).apply()

    var googleAuthorized: Boolean
        get() = prefs.getBoolean("google_authorized", false)
        set(value) = prefs.edit().putBoolean("google_authorized", value).apply()

    var googleAccountEmail: String?
        get() = prefs.getString("google_account_email", null)
        set(value) = prefs.edit().putString("google_account_email", value).apply()

    var syncIntervalHours: Int
        get() = SyncPolicy.normalizeInterval(
            prefs.getInt("sync_interval_hours", SyncPolicy.defaultIntervalHours)
        )
        set(value) = prefs.edit()
            .putInt("sync_interval_hours", SyncPolicy.normalizeInterval(value))
            .apply()

    var minimumBatteryPercent: Int
        get() = SyncPolicy.normalizeBattery(
            prefs.getInt("minimum_battery_percent", SyncPolicy.defaultBatteryPercentage)
        )
        set(value) = prefs.edit()
            .putInt("minimum_battery_percent", SyncPolicy.normalizeBattery(value))
            .apply()

    var syncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) = prefs.edit().putBoolean("sync_enabled", value).apply()

    var initialSyncComplete: Boolean
        get() = prefs.getBoolean("initial_sync_complete", false)
        set(value) = prefs.edit().putBoolean("initial_sync_complete", value).apply()

    var lastSyncInstant: String?
        get() = prefs.getString("last_sync_instant", null)
        set(value) = prefs.edit().putString("last_sync_instant", value).apply()

    var lastSyncMessage: String?
        get() = prefs.getString("last_sync_message", null)
        set(value) = prefs.edit().putString("last_sync_message", value).apply()

    var nextSyncAtMillis: Long
        get() = prefs.getLong("next_sync_at_millis", 0L)
        set(value) { prefs.edit().putLong("next_sync_at_millis", value).commit() }

    var lastSuccessfulSyncInstant: String?
        get() = prefs.getString("last_successful_sync_instant", null)
        set(value) = prefs.edit().putString("last_successful_sync_instant", value).apply()

    fun configured(): Boolean =
        googleAuthorized && spreadsheetId.matches(Regex("[A-Za-z0-9_-]{20,}"))

    fun clearSyncCursor() {
        initialSyncComplete = false
        lastSyncInstant = null
        lastSuccessfulSyncInstant = null
        lastSyncMessage = "새 스프레드시트의 첫 동기화 대기"
    }
}
