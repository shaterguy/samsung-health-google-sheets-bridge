package com.example.healthbridge

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.healthbridge.sync.SyncScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.healthbridge.sheets.GoogleAuthorization
import com.example.healthbridge.sync.SyncPolicy
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.AccountPicker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel
    private var pendingGoogleAccount: String? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        vm.refresh()
    }

    private val googleAccountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !accountName.isNullOrBlank()) {
            authorizeGoogleAccount(accountName)
        } else {
            vm.onGoogleAuthorizationError("Google 계정 선택이 취소되었습니다.")
        }
    }

    private val googleConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = pendingGoogleAccount
        if (result.resultCode == Activity.RESULT_OK && !accountName.isNullOrBlank()) {
            authorizeGoogleAccount(accountName)
        } else {
            pendingGoogleAccount = null
            vm.onGoogleAuthorizationError("Google Sheets 승인이 취소되었습니다.")
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val currentVm: MainViewModel = viewModel()
            vm = currentVm
            val state by currentVm.state.collectAsState()
            HealthBridgeScreen(
                state = state,
                onRequestPermissions = {
                    permissionLauncher.launch(currentVm.requestedPermissions())
                },
                onOpenHealthConnect = {
                    startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                },
                onInstallHealthConnect = {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                        )
                    )
                },
                onSaveSpreadsheetId = currentVm::saveSpreadsheetId,
                onConnectGoogle = ::requestGoogleAuthorization,
                onSaveOptions = currentVm::saveSyncOptions,
                onToggleSync = currentVm::setSyncEnabled,
                onSync = currentVm::sync,
                onMessageShown = currentVm::clearMessage,
                onAlarmSettings = { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
                onBatterySettings = { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) },
                onNotificationSettings = {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.refresh()
    }

    private fun openSettings(action: String, packageUri: Boolean = true) {
        val intent = Intent(action)
        if (packageUri) intent.data = Uri.parse("package:$packageName")
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestGoogleAuthorization() {
        val options = AccountPicker.AccountChooserOptions.Builder()
            .setAllowableAccountsTypes(listOf(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
            .setAlwaysShowAccountPicker(true)
            .build()
        googleAccountLauncher.launch(AccountPicker.newChooseAccountIntent(options))
    }

    private fun authorizeGoogleAccount(accountName: String) {
        pendingGoogleAccount = accountName
        lifecycleScope.launch {
            try {
                GoogleAuthorization.accessToken(this@MainActivity, accountName)
                pendingGoogleAccount = null
                vm.onGoogleAuthorized(accountName)
            } catch (error: UserRecoverableAuthException) {
                @Suppress("DEPRECATION")
                val recoveryIntent: Intent? = error.intent
                if (recoveryIntent != null) {
                    googleConsentLauncher.launch(recoveryIntent)
                } else {
                    pendingGoogleAccount = null
                    vm.onGoogleAuthorizationError("Google Sheets 승인 화면을 열 수 없습니다.")
                }
            } catch (error: Exception) {
                pendingGoogleAccount = null
                vm.onGoogleAuthorizationError(
                    "Google Sheets 연결 실패: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthBridgeScreen(
    state: MainUiState,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onSaveSpreadsheetId: (String) -> Unit,
    onConnectGoogle: () -> Unit,
    onSaveOptions: (Int, Int) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onSync: (Boolean) -> Unit,
    onMessageShown: () -> Unit,
    onAlarmSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    var spreadsheetId by rememberSaveable(state.spreadsheetId) { mutableStateOf(state.spreadsheetId) }
    var interval by rememberSaveable(state.syncIntervalHours) { mutableIntStateOf(state.syncIntervalHours) }
    var battery by rememberSaveable(state.minimumBatteryPercent) { mutableIntStateOf(state.minimumBatteryPercent) }
    var confirmFull by remember { mutableStateOf(false) }
    val ready = state.dataPermissionsGranted && state.googleAuthorized && state.spreadsheetId.length >= 20
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF77D6C4), onPrimary = Color(0xFF00382F),
        background = Color(0xFF141415), surface = Color(0xFF19191B),
        surfaceVariant = Color(0xFF232325), onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFFA1A1A6), outlineVariant = Color(0xFF3A3A3D),
    ) else lightColorScheme(
        primary = Color(0xFF006B5F), onPrimary = Color.White,
        background = Color(0xFFFAFAFA), surface = Color.White,
        surfaceVariant = Color(0xFFF4F4F5), onSurface = Color(0xFF171717),
        onSurfaceVariant = Color(0xFF525252), outlineVariant = Color(0xFFE4E4E7),
    )
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }
    MaterialTheme(colorScheme = colors) {
        if (confirmFull) AlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text("전체 이력 다시 대조") },
            text = { Text("읽을 수 있는 전체 이력을 Google Sheets와 비교해 누락된 행을 추가합니다. 데이터가 많으면 시간이 걸릴 수 있습니다.") },
            confirmButton = { TextButton(onClick = { confirmFull = false; onSync(true) }) { Text("대조 시작") } },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("취소") } },
        )
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                    listOf("동기화", "자동 실행", "연결").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = page == index, onClick = { page = index },
                            icon = { BridgeGlyph(index, Modifier.size(24.dp)) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = colors.surfaceVariant),
                        )
                    }
                }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(scrollState).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Samsung Health Bridge", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        Text(listOf("건강정보 동기화", "자동 실행", "연결 관리")[page], fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        Text("Health Connect → Google Sheets", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    when (page) {
                        0 -> {
                            BridgePanel {
                                Text("마지막 동기화", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                                Text(formatTime(state.lastSyncInstant), fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
                                HorizontalDivider(color = colors.outlineVariant)
                                Text("최근 실행 결과", style = MaterialTheme.typography.labelLarge)
                                Text(state.lastSyncMessage ?: "첫 동기화를 실행하면 여기에 결과가 표시됩니다", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Button(
                                    onClick = { onSync(false) }, enabled = ready && !state.busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp),
                                ) { Text(if (state.busy) "동기화 중" else "지금 동기화") }
                                if (!ready) TextButton(onClick = { page = 2 }) { Text("연결 설정 확인") }
                            }
                            BridgePanel {
                                Text("자동 실행", style = MaterialTheme.typography.titleMedium)
                                Text(if (state.syncEnabled) "${state.syncIntervalHours}시간마다 · 배터리 ${state.minimumBatteryPercent}% 이상" else "자동 동기화 꺼짐")
                                if (state.syncEnabled && state.nextSyncAtMillis > 0) Text("다음 예약  ${formatTime(Instant.ofEpochMilli(state.nextSyncAtMillis).toString())}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { page = 1 }) { Text("자동 실행 설정") }
                            }
                            BridgePanel {
                                Text("전송 경로", style = MaterialTheme.typography.titleMedium)
                                StatusLine("Health Connect", if (state.dataPermissionsGranted) "읽기 허용" else "권한 필요", state.dataPermissionsGranted)
                                HorizontalDivider(color = colors.outlineVariant)
                                StatusLine("Google Sheets", if (state.googleAuthorized) "계정 연결됨" else "연결 필요", state.googleAuthorized)
                            }
                            OutlinedButton(onClick = { confirmFull = true }, enabled = ready && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) { Text("전체 다시 대조") }
                        }
                        1 -> {
                            BridgePanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("자동 동기화", style = MaterialTheme.typography.titleMedium)
                                        Text(if (state.syncEnabled) "예약 실행 사용 중" else "예약 실행 중지됨", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Switch(checked = state.syncEnabled, onCheckedChange = onToggleSync)
                                }
                                Text("Wi-Fi·모바일 데이터 등 인터넷 연결 종류와 관계없이 실행합니다", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            BridgePanel {
                                Text("실행 주기", style = MaterialTheme.typography.titleMedium)
                                OptionGrid(SyncPolicy.intervalHours, interval, { "${it}시간" }) { interval = it }
                                Spacer(Modifier.height(4.dp))
                                Text("최소 배터리", style = MaterialTheme.typography.titleMedium)
                                Text("선택한 잔량 이상일 때 자동 동기화", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                OptionGrid(SyncPolicy.batteryPercentages, battery, { "$it%" }) { battery = it }
                                val changed = interval != state.syncIntervalHours || battery != state.minimumBatteryPercent
                                Button(onClick = { onSaveOptions(interval, battery) }, enabled = changed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) { Text(if (changed) "설정 저장" else "저장됨") }
                            }
                            BridgePanel {
                                Text("백그라운드 실행 상태", style = MaterialTheme.typography.titleMedium)
                                StatusLine("정확한 알람", if (state.exactAlarmAllowed) "허용됨" else "설정 필요", state.exactAlarmAllowed)
                                if (!state.exactAlarmAllowed) {
                                    Text("정확한 알람을 허용하면 절전 중에도 예약 시점에 실행을 요청합니다. 미허용 시 Android가 실행을 늦출 수 있습니다", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = onAlarmSettings) { Text("알람 권한 설정") }
                                }
                                HorizontalDivider(color = colors.outlineVariant)
                                StatusLine("배터리 최적화", if (state.batteryOptimizationExempt) "제외됨" else "시스템 제한 적용", state.batteryOptimizationExempt)
                                OutlinedButton(onClick = onBatterySettings) { Text("배터리 설정") }
                                Text("삼성 기기의 절전 앱 목록에서도 제외하면 장기간 미사용에 따른 제한을 줄일 수 있습니다", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider(color = colors.outlineVariant)
                                StatusLine("건강정보 백그라운드 읽기", state.backgroundReadStatus, state.backgroundReadReady)
                                if (!state.backgroundReadReady) OutlinedButton(onClick = onRequestPermissions, enabled = state.providerStatus == HealthConnectClient.SDK_AVAILABLE) { Text("읽기 권한 확인") }
                                OutlinedButton(onClick = onNotificationSettings) { Text("실행 알림 설정") }
                                Text("배터리가 부족하거나 인터넷이 끊기면 재시도하며, 재부팅과 앱 업데이트 뒤에도 예약을 복원합니다", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        2 -> {
                            BridgePanel {
                                Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                                Text(if (state.dataPermissionsGranted) "17종 건강정보 읽기 허용됨" else "건강정보 읽기 권한이 필요합니다", color = colors.onSurfaceVariant)
                                Text("백그라운드 읽기 · ${state.backgroundReadStatus}", style = MaterialTheme.typography.bodyMedium)
                                if (state.providerStatus == HealthConnectClient.SDK_AVAILABLE) {
                                    OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("읽기 권한 요청") }
                                    TextButton(onClick = onOpenHealthConnect) { Text("Health Connect 접근 관리") }
                                } else Button(onClick = onInstallHealthConnect, modifier = Modifier.fillMaxWidth()) { Text("Health Connect 설치·업데이트") }
                            }
                            BridgePanel {
                                Text("Google Sheets", style = MaterialTheme.typography.titleLarge)
                                Text(if (state.googleAuthorized) state.googleAccountEmail ?: "Google 계정 연결됨" else "Google 계정 연결이 필요합니다", color = colors.onSurfaceVariant)
                                OutlinedTextField(value = spreadsheetId, onValueChange = { spreadsheetId = it }, label = { Text("스프레드시트 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                                OutlinedButton(onClick = { onSaveSpreadsheetId(spreadsheetId) }, enabled = spreadsheetId.trim().matches(Regex("[A-Za-z0-9_-]{20,}")) && spreadsheetId.trim() != state.spreadsheetId, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("ID 저장") }
                                Button(onClick = onConnectGoogle, enabled = state.spreadsheetId.length >= 20, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) { Text(if (state.googleAuthorized) "Google 권한 갱신" else "Google Sheets 연결") }
                                Text("연결 설정은 기기의 암호화 저장소에 보관하며 건강정보는 지정한 스프레드시트로 전송합니다", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Health Bridge 0.4.0", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun BridgePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun StatusLine(label: String, status: String, ready: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status, style = MaterialTheme.typography.bodyLarge, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OptionGrid(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    values.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value ->
                val isSelected = selected == value
                OutlinedButton(
                    onClick = { onSelect(value) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = isSelected; role = Role.RadioButton },
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = androidx.compose.foundation.BorderStroke(if (selected == value) 2.dp else 1.dp, if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == value) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                ) { Text(label(value), fontWeight = if (selected == value) FontWeight.SemiBold else FontWeight.Normal) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun BridgeGlyph(index: Int, modifier: Modifier) {
    val color = androidx.compose.material3.LocalContentColor.current
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val w = size.width; val h = size.height
        when (index) {
            0 -> {
                drawArc(color, -50f, 270f, false, topLeft = androidx.compose.ui.geometry.Offset(w * .18f, h * .18f), size = androidx.compose.ui.geometry.Size(w * .64f, h * .64f), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * .84f, h * .18f), androidx.compose.ui.geometry.Offset(w * .84f, h * .42f), stroke.width)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * .62f, h * .42f), androidx.compose.ui.geometry.Offset(w * .84f, h * .42f), stroke.width)
            }
            1 -> {
                drawCircle(color, radius = w * .35f, style = stroke)
                drawLine(color, center, androidx.compose.ui.geometry.Offset(w * .5f, h * .26f), stroke.width)
                drawLine(color, center, androidx.compose.ui.geometry.Offset(w * .68f, h * .6f), stroke.width)
            }
            else -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * .15f, h * .2f), size = androidx.compose.ui.geometry.Size(w * .3f, h * .6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * .55f, h * .2f), size = androidx.compose.ui.geometry.Size(w * .3f, h * .6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * .35f, h * .5f), androidx.compose.ui.geometry.Offset(w * .65f, h * .5f), stroke.width)
            }
        }
    }
}

private fun formatTime(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault(it)
} ?: "아직 성공 기록 없음"
