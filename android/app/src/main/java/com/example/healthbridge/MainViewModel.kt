package com.example.healthbridge

import android.app.Application
import androidx.health.connect.client.permission.HealthPermission
import com.example.healthbridge.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthbridge.health.HealthPermissions
import com.example.healthbridge.health.HealthSyncRepository
import com.example.healthbridge.sync.SyncPolicy
import com.example.healthbridge.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val providerStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val granted: Set<String> = emptySet(),
    val googleAuthorized: Boolean = false,
    val googleAccountEmail: String? = null,
    val spreadsheetId: String = "",
    val syncEnabled: Boolean = false,
    val syncIntervalHours: Int = SyncPolicy.defaultIntervalHours,
    val minimumBatteryPercent: Int = SyncPolicy.defaultBatteryPercentage,
    val initialSyncComplete: Boolean = false,
    val lastSyncMessage: String? = null,
    val lastSyncInstant: String? = null,
    val nextSyncAtMillis: Long = 0L,
    val exactAlarmAllowed: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val backgroundReadReady: Boolean = false,
    val backgroundReadStatus: String = "확인 중",
    val busy: Boolean = false,
    val message: String? = null,
) {
    val dataPermissionsGranted: Boolean
        get() = granted.containsAll(HealthPermissions.dataRead)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HealthSyncRepository(application)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val available = repository.providerStatus == HealthConnectClient.SDK_AVAILABLE
            val granted = if (available) runCatching { repository.grantedPermissions() }.getOrDefault(emptySet()) else emptySet()
            val backgroundSupported = available && runCatching { HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in repository.requestedPermissions() }.getOrDefault(false)
            val backgroundGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            _state.value = _state.value.copy(
                providerStatus = repository.providerStatus,
                granted = granted,
                googleAuthorized = repository.settings.googleAuthorized,
                googleAccountEmail = repository.settings.googleAccountEmail,
                spreadsheetId = repository.settings.spreadsheetId,
                syncEnabled = repository.settings.syncEnabled,
                syncIntervalHours = repository.settings.syncIntervalHours,
                minimumBatteryPercent = repository.settings.minimumBatteryPercent,
                initialSyncComplete = repository.settings.initialSyncComplete,
                lastSyncMessage = repository.settings.lastSyncMessage,
                lastSyncInstant = repository.settings.lastSuccessfulSyncInstant ?: repository.settings.lastSyncInstant,
                nextSyncAtMillis = repository.settings.nextSyncAtMillis,
                exactAlarmAllowed = SyncScheduler.canScheduleExact(getApplication()),
                batteryOptimizationExempt = SyncScheduler.isBatteryOptimizationExempt(getApplication()),
                backgroundReadReady = backgroundSupported && backgroundGranted,
                backgroundReadStatus = when {
                    !available -> "Health Connect 연결 필요"
                    !backgroundSupported -> "이 기기에서 지원하지 않음"
                    backgroundGranted -> "허용됨"
                    else -> "권한 필요"
                },
            )
        }
    }

    fun requestedPermissions(): Set<String> = repository.requestedPermissions()

    fun saveSpreadsheetId(value: String) {
        val normalized = value.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9_-]{20,}"))) {
            _state.value = _state.value.copy(
                message = "Google 스프레드시트 ID 형식을 확인해 주세요.",
            )
            return
        }

        val changed = normalized != repository.settings.spreadsheetId
        repository.settings.spreadsheetId = normalized
        if (changed) {
            repository.settings.clearSyncCursor()
        }
        _state.value = _state.value.copy(
            spreadsheetId = normalized,
            initialSyncComplete = if (changed) false else _state.value.initialSyncComplete,
            lastSyncInstant = if (changed) null else _state.value.lastSyncInstant,
            lastSyncMessage = repository.settings.lastSyncMessage,
            message = "스프레드시트 ID를 암호화 저장했습니다.",
        )
    }

    fun onGoogleAuthorized(accountEmail: String) {
        if (repository.settings.spreadsheetId.length < 20) {
            onGoogleAuthorizationError("먼저 Google 스프레드시트 ID를 저장해 주세요.")
            return
        }
        repository.settings.googleAuthorized = true
        repository.settings.googleAccountEmail = accountEmail
        if (repository.settings.syncEnabled) {
            SyncWorker.schedule(getApplication())
        }
        _state.value = _state.value.copy(
            googleAuthorized = true,
            googleAccountEmail = accountEmail,
            message = "Google Sheets 연결을 승인했습니다.",
        )
    }

    fun onGoogleAuthorizationError(message: String) {
        repository.settings.googleAuthorized = false
        _state.value = _state.value.copy(
            googleAuthorized = false,
            message = message,
        )
    }

    fun saveSyncOptions(intervalHours: Int, minimumBatteryPercent: Int) {
        repository.settings.syncIntervalHours = intervalHours
        repository.settings.minimumBatteryPercent = minimumBatteryPercent
        if (repository.settings.syncEnabled) {
            SyncWorker.schedule(getApplication(), reset = true)
        }
        _state.value = _state.value.copy(
            nextSyncAtMillis = repository.settings.nextSyncAtMillis,
            syncIntervalHours = repository.settings.syncIntervalHours,
            minimumBatteryPercent = repository.settings.minimumBatteryPercent,
            message = "자동 동기화 조건을 저장했습니다.",
        )
    }

    fun setSyncEnabled(enabled: Boolean) {
        if (enabled && !repository.settings.configured()) {
            _state.value = _state.value.copy(
                syncEnabled = false,
                message = "스프레드시트 ID를 저장하고 Google Sheets 연결을 승인해 주세요.",
            )
            return
        }
        repository.settings.syncEnabled = enabled
        if (enabled) {
            SyncWorker.schedule(getApplication(), reset = true)
        } else {
            SyncWorker.cancel(getApplication())
        }
        _state.value = _state.value.copy(
            syncEnabled = enabled,
            nextSyncAtMillis = repository.settings.nextSyncAtMillis,
            message = if (enabled) {
                "${repository.settings.syncIntervalHours}시간 간격 자동 동기화를 예약했습니다."
            } else {
                "자동 동기화를 중지했습니다."
            },
        )
    }

    fun sync(full: Boolean) {
        if (!repository.settings.configured()) {
            _state.value = _state.value.copy(
                message = "스프레드시트 ID를 저장하고 Google Sheets 연결을 승인해 주세요.",
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                message = if (full) {
                    "전체 이력을 Google Sheets와 대조하는 중입니다."
                } else {
                    "새 건강정보를 Google Sheets에 동기화하는 중입니다."
                },
            )
            try {
                val report = withContext(Dispatchers.IO) {
                    repository.sync(forceFull = full)
                }
                _state.value = _state.value.copy(
                    busy = false,
                    initialSyncComplete = true,
                    lastSyncMessage = repository.settings.lastSyncMessage,
                    message = "${report.uploaded}개 원본 행을 동기화했습니다.",
                )
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                repository.settings.lastSyncMessage = "동기화 실패: ${error.message}"
                _state.value = _state.value.copy(
                    busy = false,
                    lastSyncMessage = repository.settings.lastSyncMessage,
                    message = "동기화 실패: ${error.message}",
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
