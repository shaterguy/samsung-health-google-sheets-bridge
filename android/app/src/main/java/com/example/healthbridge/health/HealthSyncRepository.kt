package com.example.healthbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.healthbridge.data.SecureSettings
import com.example.healthbridge.data.SyncReport
import com.example.healthbridge.data.UploadRecord
import com.example.healthbridge.sheets.GoogleAuthorization
import com.example.healthbridge.sheets.GoogleSheetsClient
import com.example.healthbridge.sheets.SheetRowMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthSyncRepository(private val context: Context) {
    val settings = SecureSettings(context)
    val providerStatus: Int
        get() = HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata")

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun hasDataPermissions(): Boolean = grantedPermissions().containsAll(HealthPermissions.dataRead)

    fun requestedPermissions(): Set<String> = HealthPermissions.available(client)

    suspend fun sync(forceFull: Boolean = false): SyncReport {
        require(providerStatus == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect를 사용할 수 없거나 업데이트가 필요합니다."
        }
        require(settings.configured()) {
            "먼저 Google Sheets 연결을 승인해 주세요."
        }
        val granted = grantedPermissions()
        require(granted.containsAll(HealthPermissions.dataRead)) {
            "Health Connect 데이터 권한을 모두 허용해 주세요."
        }

        val started = Instant.now()
        val full = forceFull || !settings.initialSyncComplete
        val overlapStart = settings.lastSyncInstant?.let(Instant::parse)?.minus(Duration.ofHours(48))
        val historyGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        val fullHistoryStart = LocalDate.of(2015, 1, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val start = when {
            full && historyGranted -> fullHistoryStart
            full -> started.minus(Duration.ofDays(30))
            else -> overlapStart ?: started.minus(Duration.ofDays(30))
        }

        val accessToken = GoogleAuthorization.accessToken(context)
        val sheets = GoogleSheetsClient(settings.spreadsheetId)
        val existingKeys = withContext(Dispatchers.IO) {
            sheets.readExistingRowKeys(accessToken)
        }
        var uploaded = 0
        var processed = 0

        suspend fun upload(records: List<UploadRecord>) {
            if (records.isEmpty()) return
            processed += records.size
            val receivedAt = Instant.now()
            val rows = records
                .flatMap { SheetRowMapper.rows(it, receivedAt, started) }
                .filter { existingKeys.add(it.first().toString()) }
            rows.chunked(20).forEach { chunk ->
                withContext(Dispatchers.IO) {
                    sheets.appendHealthRows(accessToken, chunk)
                }
            }
            uploaded += rows.size
        }

        var windowStart = start
        while (windowStart < started) {
            val windowEnd = minOf(
                windowStart.atZone(ZoneId.systemDefault()).plusYears(1).toInstant(),
                started,
            )
            upload(readDailySteps(windowStart, windowEnd))
            readPages(HeartRateRecord::class, windowStart, windowEnd) { upload(it.map(::mapHeartRate)) }
            readPages(SleepSessionRecord::class, windowStart, windowEnd) { upload(it.map(::mapSleep)) }
            readPages(WeightRecord::class, windowStart, windowEnd) { upload(it.map(::mapWeight)) }
            readPages(ExerciseSessionRecord::class, windowStart, windowEnd) { upload(it.map(::mapExercise)) }
            readPages(BloodGlucoseRecord::class, windowStart, windowEnd) { upload(it.map(::mapBloodGlucose)) }
            readPages(OxygenSaturationRecord::class, windowStart, windowEnd) { upload(it.map(::mapOxygenSaturation)) }
            readPages(BloodPressureRecord::class, windowStart, windowEnd) { upload(it.map(::mapBloodPressure)) }
            readPages(TotalCaloriesBurnedRecord::class, windowStart, windowEnd) { upload(it.map(::mapTotalCalories)) }
            readPages(DistanceRecord::class, windowStart, windowEnd) { upload(it.map(::mapDistance)) }
            readPages(PowerRecord::class, windowStart, windowEnd) { upload(it.map(::mapPower)) }
            readPages(SpeedRecord::class, windowStart, windowEnd) { upload(it.map(::mapSpeed)) }
            readPages(Vo2MaxRecord::class, windowStart, windowEnd) { upload(it.map(::mapVo2Max)) }
            readPages(NutritionRecord::class, windowStart, windowEnd) { upload(it.map(::mapNutrition)) }
            readPages(BodyFatRecord::class, windowStart, windowEnd) { upload(it.map(::mapBodyFat)) }
            readPages(BasalMetabolicRateRecord::class, windowStart, windowEnd) { upload(it.map(::mapBasalMetabolicRate)) }
            readPages(HeightRecord::class, windowStart, windowEnd) { upload(it.map(::mapHeight)) }
            windowStart = windowEnd
        }

        val completed = Instant.now()
        val completedKst = SheetRowMapper.formatSyncTimestamp(completed)
        val status = if (uploaded == 0) "success_no_changes" else "success"
        withContext(Dispatchers.IO) {
            sheets.appendSyncLog(
                accessToken,
                listOf(
                    completedKst,
                    "android://health-connect",
                    processed,
                    existingKeys.size,
                    uploaded,
                    status,
                ),
            )
        }

        settings.lastSyncInstant = started.toString()
        settings.initialSyncComplete = true
        val historyNote = if (full && !historyGranted) {
            " (과거 이력 권한이 없어 최근 30일만 처리)"
        } else {
            ""
        }
        settings.lastSyncMessage =
            "${uploaded}개 원본 행을 Google Sheets에 동기화했습니다: ${completedKst} KST$historyNote"
        return SyncReport(uploaded, started.toString(), completed.toString(), full)
    }

    private suspend fun readDailySteps(start: Instant, end: Instant): List<UploadRecord> {
        val zone = ZoneId.systemDefault()
        val calendarStart = start.atZone(zone).toLocalDate().atStartOfDay()
        val calendarEnd = end.atZone(zone).toLocalDateTime()
        if (!calendarStart.isBefore(calendarEnd)) return emptyList()
        val response = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    calendarStart,
                    calendarEnd,
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        return response.mapNotNull { bucket ->
            val count = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            val bucketStart = bucket.startTime.atZone(zone).toInstant()
            val bucketEnd = bucket.endTime.atZone(zone).toInstant()
            val day = bucket.startTime.toLocalDate()
            UploadRecord(
                recordType = "steps",
                sourceRecordId = "aggregate-steps-$day",
                dataOrigin = "health_connect.aggregate",
                startTime = bucketStart.toString(),
                endTime = bucketEnd.toString(),
                localDate = day.toString(),
                clientModifiedAt = end.toString(),
                payload = mapOf("count" to count),
            )
        }
    }

    private suspend fun <T : Record> readPages(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        consume: suspend (List<T>) -> Unit,
    ) {
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 10,
                    pageToken = token,
                )
            )
            if (page.records.isNotEmpty()) consume(page.records)
            token = page.pageToken
        } while (token != null)
    }

    private fun base(record: Record, type: String, start: Instant, end: Instant, localDate: LocalDate, payload: Map<String, Any?>): UploadRecord = UploadRecord(
        recordType = type,
        sourceRecordId = record.metadata.id,
        dataOrigin = record.metadata.dataOrigin.packageName.ifBlank { "unknown" },
        startTime = start.toString(),
        endTime = end.toString(),
        localDate = localDate.toString(),
        clientModifiedAt = record.metadata.lastModifiedTime.toString(),
        payload = payload,
    )

    private fun instant(record: Record, type: String, time: Instant, payload: Map<String, Any?>): UploadRecord =
        base(record, type, time, time, time.atZone(ZoneId.systemDefault()).toLocalDate(), payload)

    private fun mapHeartRate(record: HeartRateRecord): UploadRecord = base(
        record, "heart_rate", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("samples" to record.samples.map { mapOf("time" to it.time.toString(), "bpm" to it.beatsPerMinute) }),
    )

    private fun mapSleep(record: SleepSessionRecord): UploadRecord = base(
        record, "sleep", record.startTime, record.endTime, record.endTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf(
            "duration_minutes" to Duration.between(record.startTime, record.endTime).toMinutes(),
            "stages" to record.stages.map { mapOf("start_time" to it.startTime.toString(), "end_time" to it.endTime.toString(), "stage" to it.stage) },
        ),
    )

    private fun mapWeight(record: WeightRecord): UploadRecord = instant(record, "weight", record.time, mapOf("kg" to record.weight.inKilograms))

    private fun mapExercise(record: ExerciseSessionRecord): UploadRecord = base(
        record, "exercise", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("duration_minutes" to Duration.between(record.startTime, record.endTime).toMinutes(), "exercise_type" to record.exerciseType),
    )

    private fun mapBloodGlucose(record: BloodGlucoseRecord): UploadRecord = instant(
        record, "blood_glucose", record.time,
        mapOf(
            "mg_dl" to record.level.inMilligramsPerDeciliter,
            "mmol_l" to record.level.inMillimolesPerLiter,
            "specimen_source" to record.specimenSource,
            "meal_type" to record.mealType,
            "relation_to_meal" to record.relationToMeal,
        ),
    )

    private fun mapOxygenSaturation(record: OxygenSaturationRecord): UploadRecord =
        instant(record, "oxygen_saturation", record.time, mapOf("percent" to record.percentage.value))

    private fun mapBloodPressure(record: BloodPressureRecord): UploadRecord = instant(
        record, "blood_pressure", record.time,
        mapOf(
            "systolic_mmhg" to record.systolic.inMillimetersOfMercury,
            "diastolic_mmhg" to record.diastolic.inMillimetersOfMercury,
            "body_position" to record.bodyPosition,
            "measurement_location" to record.measurementLocation,
        ),
    )

    private fun mapTotalCalories(record: TotalCaloriesBurnedRecord): UploadRecord = base(
        record, "total_calories_burned", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("kilocalories" to record.energy.inKilocalories),
    )

    private fun mapDistance(record: DistanceRecord): UploadRecord = base(
        record, "distance", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("meters" to record.distance.inMeters, "kilometers" to record.distance.inKilometers),
    )

    private fun mapPower(record: PowerRecord): UploadRecord = base(
        record, "power", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("samples" to record.samples.map { mapOf("time" to it.time.toString(), "watts" to it.power.inWatts) }),
    )

    private fun mapSpeed(record: SpeedRecord): UploadRecord = base(
        record, "speed", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(),
        mapOf("samples" to record.samples.map { mapOf("time" to it.time.toString(), "meters_per_second" to it.speed.inMetersPerSecond, "kilometers_per_hour" to it.speed.inKilometersPerHour) }),
    )

    private fun mapVo2Max(record: Vo2MaxRecord): UploadRecord = instant(
        record, "vo2_max", record.time,
        mapOf("ml_min_kg" to record.vo2MillilitersPerMinuteKilogram, "measurement_method" to record.measurementMethod),
    )

    private fun mapBodyFat(record: BodyFatRecord): UploadRecord =
        instant(record, "body_fat", record.time, mapOf("percent" to record.percentage.value))

    private fun mapBasalMetabolicRate(record: BasalMetabolicRateRecord): UploadRecord = instant(
        record, "basal_metabolic_rate", record.time,
        mapOf("kilocalories_per_day" to record.basalMetabolicRate.inKilocaloriesPerDay),
    )

    private fun mapHeight(record: HeightRecord): UploadRecord = instant(
        record, "height", record.time,
        mapOf("meters" to record.height.inMeters, "centimeters" to record.height.inMeters * 100.0),
    )

    private fun mapNutrition(record: NutritionRecord): UploadRecord {
        val payload = mapOf<String, Any?>(
            "name" to record.name,
            "meal_type" to record.mealType,
            "energy_kcal" to record.energy?.inKilocalories,
            "energy_from_fat_kcal" to record.energyFromFat?.inKilocalories,
            "biotin_g" to record.biotin?.inGrams,
            "caffeine_g" to record.caffeine?.inGrams,
            "calcium_g" to record.calcium?.inGrams,
            "chloride_g" to record.chloride?.inGrams,
            "cholesterol_g" to record.cholesterol?.inGrams,
            "chromium_g" to record.chromium?.inGrams,
            "copper_g" to record.copper?.inGrams,
            "dietary_fiber_g" to record.dietaryFiber?.inGrams,
            "folate_g" to record.folate?.inGrams,
            "folic_acid_g" to record.folicAcid?.inGrams,
            "iodine_g" to record.iodine?.inGrams,
            "iron_g" to record.iron?.inGrams,
            "magnesium_g" to record.magnesium?.inGrams,
            "manganese_g" to record.manganese?.inGrams,
            "molybdenum_g" to record.molybdenum?.inGrams,
            "monounsaturated_fat_g" to record.monounsaturatedFat?.inGrams,
            "niacin_g" to record.niacin?.inGrams,
            "pantothenic_acid_g" to record.pantothenicAcid?.inGrams,
            "phosphorus_g" to record.phosphorus?.inGrams,
            "polyunsaturated_fat_g" to record.polyunsaturatedFat?.inGrams,
            "potassium_g" to record.potassium?.inGrams,
            "protein_g" to record.protein?.inGrams,
            "riboflavin_g" to record.riboflavin?.inGrams,
            "saturated_fat_g" to record.saturatedFat?.inGrams,
            "selenium_g" to record.selenium?.inGrams,
            "sodium_g" to record.sodium?.inGrams,
            "sugar_g" to record.sugar?.inGrams,
            "thiamin_g" to record.thiamin?.inGrams,
            "total_carbohydrate_g" to record.totalCarbohydrate?.inGrams,
            "total_fat_g" to record.totalFat?.inGrams,
            "trans_fat_g" to record.transFat?.inGrams,
            "unsaturated_fat_g" to record.unsaturatedFat?.inGrams,
            "vitamin_a_g" to record.vitaminA?.inGrams,
            "vitamin_b12_g" to record.vitaminB12?.inGrams,
            "vitamin_b6_g" to record.vitaminB6?.inGrams,
            "vitamin_c_g" to record.vitaminC?.inGrams,
            "vitamin_d_g" to record.vitaminD?.inGrams,
            "vitamin_e_g" to record.vitaminE?.inGrams,
            "vitamin_k_g" to record.vitaminK?.inGrams,
            "zinc_g" to record.zinc?.inGrams,
        ).filterValues { it != null }
        return base(
            record, "nutrition", record.startTime, record.endTime, record.startTime.atZone(ZoneId.systemDefault()).toLocalDate(), payload,
        )
    }
}
