package com.example.healthbridge.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import com.example.healthbridge.health.HealthSyncRepository
import com.example.healthbridge.health.HealthPermissions
import com.example.healthbridge.sheets.GoogleAuthorizationRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One due check and one automatic transfer, shared by alarm and WorkManager entry points. */
object AutomaticSync {
    private val mutex = Mutex()

    suspend fun run(context: Context, foreground: Boolean) = mutex.withLock {
        val repository = HealthSyncRepository(context)
        val settings = repository.settings
        if (!settings.syncEnabled || !settings.configured()) return@withLock
        if (!SyncPolicy.isDue(System.currentTimeMillis(), settings.nextSyncAtMillis)) return@withLock

        // A worker without background access must not consume the alarm's due marker.
        // The exact-alarm foreground service can still perform a foreground read.
        if (!foreground && repository.providerStatus == HealthConnectClient.SDK_AVAILABLE) {
            val available = repository.client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            val granted = repository.grantedPermissions()
            if (!available || HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
                settings.lastSyncMessage = "백그라운드 읽기 권한 대기 · 정확한 알람 실행 또는 연결 설정을 확인해 주세요"
                return@withLock
            }
        }

        // Arm recovery before I/O, so process death cannot consume the only pending alarm.
        SyncScheduler.after(context, SyncPolicy.retryDelayMillis)
        if (!SyncWorker.isNetworkConnected(context)) {
            settings.lastSyncMessage = "인터넷 연결 대기 · 30분 뒤 다시 확인합니다"
            return@withLock
        }
        val battery = SyncWorker.currentBatteryPercent(context)
        if (battery == null || battery < settings.minimumBatteryPercent) {
            settings.lastSyncMessage = if (battery == null) {
                "배터리 잔량 확인 대기 · 30분 뒤 다시 확인합니다"
            } else {
                "배터리 $battery% · 최소 ${settings.minimumBatteryPercent}% 도달 후 동기화합니다"
            }
            return@withLock
        }
        try {
            if (repository.providerStatus != HealthConnectClient.SDK_AVAILABLE) {
                settings.lastSyncMessage = "Health Connect 설치 또는 업데이트가 필요합니다"
                SyncScheduler.nextInterval(context)
                return@withLock
            }
            val permissions = repository.grantedPermissions()
            if (!permissions.containsAll(HealthPermissions.dataRead)) {
                settings.lastSyncMessage = "자동 동기화에 필요한 Health Connect 읽기 권한을 확인해 주세요"
                SyncScheduler.nextInterval(context)
                return@withLock
            }
            repository.sync()
            SyncScheduler.nextInterval(context)
        } catch (error: CancellationException) {
            // Preserve the recovery alarm and propagate coroutine cancellation.
            throw error
        } catch (error: GoogleAuthorizationRequiredException) {
            settings.googleAuthorized = false
            settings.lastSyncMessage = error.message
            SyncScheduler.nextInterval(context)
        } catch (error: SecurityException) {
            settings.lastSyncMessage = "Health Connect 읽기 권한을 확인해 주세요"
            SyncScheduler.nextInterval(context)
        } catch (error: Exception) {
            settings.lastSyncMessage = "자동 동기화 실패 · 30분 뒤 재시도: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}
