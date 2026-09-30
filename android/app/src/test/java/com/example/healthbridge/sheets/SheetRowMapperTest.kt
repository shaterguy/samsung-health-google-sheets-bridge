package com.example.healthbridge.sheets

import com.example.healthbridge.data.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SheetRowMapperTest {
    private fun record(payload: Map<String, Any?> = mapOf("count" to 42)) = UploadRecord(
        recordType = "steps",
        sourceRecordId = "source-id",
        dataOrigin = "health_connect.aggregate",
        startTime = "2026-07-11T00:00:00Z",
        endTime = "2026-07-11T01:00:00Z",
        localDate = "2026-07-11",
        clientModifiedAt = "2026-07-11T01:02:03.123456Z",
        payload = payload,
    )

    @Test
    fun stepVersionKeyChangesOnlyWhenAggregateChanges() {
        val original = SheetRowMapper.versionKey(record())
        assertEquals(
            original,
            SheetRowMapper.versionKey(
                record().copy(clientModifiedAt = "2026-07-12T02:03:04.123456Z")
            ),
        )
        assertTrue(original != SheetRowMapper.versionKey(record(mapOf("count" to 43))))
    }

    @Test
    fun sheetRowsKeepSourceInstantsCanonicalAndSyncTimesInKoreaTime() {
        val rows = SheetRowMapper.rows(
            record(),
            Instant.parse("2026-07-12T00:00:00Z"),
            Instant.parse("2026-07-12T00:01:00Z"),
        )
        assertEquals(1, rows.size)
        assertEquals(14, rows.single().size)
        assertTrue(rows.single().first().toString().endsWith(":1"))
        assertEquals("2026-07-11 00:00:00.000000", rows.single()[7])
        assertEquals("2026-07-11 01:00:00.000000", rows.single()[8])
        assertEquals("2026-07-11 01:02:03.123456", rows.single()[9])
        assertEquals("2026-07-12 09:00:00.000000", rows.single()[10])
        assertEquals("2026-07-12 09:01:00.000000", rows.single()[13])
        assertEquals(
            "2026-07-12 09:00:00.123456",
            SheetRowMapper.formatSyncTimestamp(Instant.parse("2026-07-12T00:00:00.123456Z")),
        )
    }

    @Test
    fun oversizedPayloadIsSplitBelowGoogleCellLimit() {
        val rows = SheetRowMapper.rows(
            record(mapOf("text" to "x".repeat(90_100))),
            Instant.parse("2026-07-12T00:00:00Z"),
            Instant.parse("2026-07-12T00:01:00Z"),
        )
        assertTrue(rows.size >= 3)
        assertTrue(rows.all { it[12].toString().length <= SheetRowMapper.maxPayloadChars })
        assertTrue(rows.all { it[3] == rows.size })
    }
}
