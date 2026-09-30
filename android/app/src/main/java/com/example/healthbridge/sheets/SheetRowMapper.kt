package com.example.healthbridge.sheets

import com.example.healthbridge.data.UploadRecord
import com.google.gson.Gson
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object SheetRowMapper {
    const val maxPayloadChars = 45_000

    private val gson = Gson()
    private val databaseTimestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
        .withZone(ZoneOffset.UTC)
    private val syncTimestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
        .withZone(ZoneId.of("Asia/Seoul"))

    fun versionKey(record: UploadRecord): String {
        val versionMaterial = if (record.recordType == "steps") {
            gson.toJson(record.payload)
        } else {
            sqliteTimestamp(record.clientModifiedAt)
        }
        val material = listOf(record.recordType, record.sourceRecordId, versionMaterial)
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun rows(
        record: UploadRecord,
        receivedAt: Instant,
        exportedAt: Instant,
    ): List<List<Any>> {
        val key = versionKey(record)
        val payloadParts = gson.toJson(record.payload).chunked(maxPayloadChars)
        val received = formatSyncTimestamp(receivedAt)
        val exported = formatSyncTimestamp(exportedAt)
        return payloadParts.mapIndexed { index, payload ->
            listOf(
                "$key:${index + 1}",
                key,
                index + 1,
                payloadParts.size,
                record.recordType,
                record.sourceRecordId,
                record.localDate,
                sqliteTimestamp(record.startTime),
                sqliteTimestamp(record.endTime),
                sqliteTimestamp(record.clientModifiedAt),
                received,
                record.dataOrigin,
                payload,
                exported,
            )
        }
    }

    fun formatSyncTimestamp(value: Instant): String = syncTimestamp.format(
        value.truncatedTo(ChronoUnit.MICROS)
    )

    fun sqliteTimestamp(value: String): String = databaseTimestamp.format(
        Instant.parse(value).truncatedTo(ChronoUnit.MICROS)
    )
}
