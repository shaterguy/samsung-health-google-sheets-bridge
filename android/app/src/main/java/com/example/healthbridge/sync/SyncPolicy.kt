package com.example.healthbridge.sync

object SyncPolicy {
    val intervalHours = listOf(1, 3, 6, 12, 24)
    val batteryPercentages = listOf(20, 30, 40, 50, 60, 70)
    const val defaultIntervalHours = 3
    const val defaultBatteryPercentage = 70
    const val retryDelayMillis = 30L * 60 * 1000

    fun normalizeInterval(value: Int): Int =
        value.takeIf(intervalHours::contains) ?: defaultIntervalHours

    fun normalizeBattery(value: Int): Int =
        value.takeIf(batteryPercentages::contains) ?: defaultBatteryPercentage

    fun nextRegularRun(now: Long, interval: Int): Long =
        now + normalizeInterval(interval) * 60L * 60 * 1000

    fun restoreRun(now: Long, saved: Long, interval: Int, reset: Boolean): Long =
        if (reset || saved <= 0L) nextRegularRun(now, interval) else saved

    fun isDue(now: Long, saved: Long): Boolean = saved <= now
}
