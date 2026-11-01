package com.example.healthbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test fun batteryChoicesCoverTwentyToSeventy() {
        assertEquals(listOf(20, 30, 40, 50, 60, 70), SyncPolicy.batteryPercentages)
        SyncPolicy.batteryPercentages.forEach { assertEquals(it, SyncPolicy.normalizeBattery(it)) }
        assertEquals(70, SyncPolicy.normalizeBattery(80))
        assertEquals(70, SyncPolicy.normalizeBattery(90))
        assertEquals(70, SyncPolicy.normalizeBattery(65))
    }

    @Test fun invalidIntervalFallsBackWithoutChangingValidIntervals() {
        assertEquals(3, SyncPolicy.normalizeInterval(2))
        assertEquals(12, SyncPolicy.normalizeInterval(12))
    }

    @Test fun processRestartDoesNotPostponePendingOrOverdueWork() {
        assertEquals(50_000L, SyncPolicy.restoreRun(10_000L, 50_000L, 3, false))
        assertEquals(5_000L, SyncPolicy.restoreRun(10_000L, 5_000L, 3, false))
        assertTrue(SyncPolicy.isDue(10_000L, 5_000L))
        assertTrue(SyncPolicy.isDue(10_000L, 10_000L))
        assertFalse(SyncPolicy.isDue(10_000L, 50_000L))
    }

    @Test fun firstScheduleAndExplicitResetUseSelectedInterval() {
        val now = 10_000L
        val expected = now + 3L * 60 * 60 * 1000
        assertEquals(expected, SyncPolicy.restoreRun(now, 0L, 3, false))
        assertEquals(expected, SyncPolicy.restoreRun(now, 5_000L, 3, true))
        assertEquals(now + 24L * 60 * 60 * 1000, SyncPolicy.nextRegularRun(now, 24))
        assertEquals(30L * 60 * 1000, SyncPolicy.retryDelayMillis)
    }
}
