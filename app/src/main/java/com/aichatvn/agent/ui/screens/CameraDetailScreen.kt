package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.ui.viewmodels.CameraDetailViewModel
import com.aichatvn.agent.ui.viewmodels.ScheduleDraft
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDetailScreen(
    navController: NavController,
    viewModel: CameraDetailViewModel = hiltViewModel()
) {
    val camera by viewModel.camera.collectAsState()
    val smartMode by viewModel.smartMode.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val liveSnapshot by viewModel.liveSnapshot.collectAsState()
    val recentAlerts by viewModel.recentAlerts.collectAsState()
    val configDraft by viewModel.configDraft.collectAsState()
    val configSaveResult by viewModel.configSaveResult.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    val scheduleDraft by viewModel.scheduleDraft.collectAsState()
    val scheduleResult by viewModel.scheduleResult.collectAsState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showTestDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(testResult) {
        testResult?.let { showTestDialog = true }
    }
    LaunchedEffect(configSaveResult) {
        configSaveResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearConfigSaveResult()
        }
    }
    LaunchedEffect(scheduleResult) {
        scheduleResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearScheduleResult()
        }
    }

    // Bottom sheet form thêm/sửa lịch
    if (scheduleDraft != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeScheduleEditor() },
            sheetState = sheetState
        ) {
            ScheduleFormSheet(
                draft = scheduleDraft!!,
                isLoading = isLoading,
                onUpdate = { viewModel.updateScheduleDraft(it) },
                onSave = { viewModel.saveSchedule() },
                onCancel = { viewModel.closeScheduleEditor() }
            )
        }
    }

    if (showTestDialog && testResult != null) {
        AlertDialog(
            onDismissRequest = {
                showTestDialog = false
                viewModel.clearTestResult()
            },
            title = { Text("📊 Kết quả kiểm tra") },
            text = { Text(testResult ?: "", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showTestDialog = false
                    viewModel.clearTestResult()
                }) { Text("Đóng") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(camera?.customername ?: "Chi tiết camera") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        if (camera == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) CircularProgressIndicator() else Text("Không tìm thấy camera")
            }
            return@Scaffold
        }

        val cam = camera!!
        val isTracking = cam.manualOff == 0
        val isOnline = cam.isOnline == 1

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === Ảnh xem trực tiếp ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val bitmap = remember(liveSnapshot) {
                            liveSnapshot?.let { bytes ->
                                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator()
                                } else {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        OutlinedButton(
                            onClick = { viewModel.loadLiveSnapshot() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Xem ảnh trực tiếp")
                        }
                    }
                }
            }

            // === Trạng thái & thông tin ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Trạng thái", style = MaterialTheme.typography.titleSmall)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    cam.manualOff == 1 -> MaterialTheme.colorScheme.secondaryContainer
                                    !isOnline -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                            ) {
                                Text(
                                    text = when {
                                        cam.manualOff == 1 -> "Đã tắt"
                                        !isOnline -> "Mất kết nối"
                                        else -> "Hoạt động"
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Mã camera", cam.id)
                        DetailRow("Khách hàng", cam.customername.ifBlank { "—" })
                        DetailRow("Mã khách hàng", cam.customerId.ifBlank { "—" })
                        DetailRow("Email", cam.customeremail.ifBlank { "—" })
                        DetailRow("Vị trí", cam.landinfo?.ifBlank { "—" } ?: "—")
                    }
                }
            }

            // === Điều khiển ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Điều khiển", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Theo dõi", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (isTracking) "Đang bật — camera được quét tự động"
                                    else "Đã tắt — không quét",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = isTracking, onCheckedChange = { viewModel.toggleActive() })
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("AI Smart Mode", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (smartMode) "Bật — AI phân tích & gửi email khi có biến động"
                                    else "Tắt — chỉ so sánh ảnh, không gọi AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = smartMode, onCheckedChange = { viewModel.toggleSmartMode() })
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { viewModel.testCamera() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Test ngay")
                            }
                        }
                    }
                }
            }

            // === Học tập thích nghi ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Học tập thích nghi (AI)", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val stats = diagnostics
                        if (stats != null) {
                            CamStatRow("Mẫu học", "${stats["samples"] ?: 0}")
                            CamStatRow("Sự kiện thật", "${stats["realEvents"] ?: 0}")
                            CamStatRow("Ngưỡng delta", "${stats["deltaTrigger"] ?: 10}")
                            CamStatRow("Ngưỡng diff", "${stats["absDiffTrigger"] ?: 18}")
                            CamStatRow("Baseline size", "${stats["baselineSize"] ?: 0}")

                            val inCooldown = stats["inCooldown"] as? Boolean ?: false
                            val circuitOpen = stats["circuitBreakerOpen"] as? Boolean ?: false
                            if (inCooldown || circuitOpen) Spacer(Modifier.height(4.dp))
                            if (inCooldown) {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text("⏳ Đang cooldown", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (circuitOpen) {
                                Spacer(Modifier.height(4.dp))
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                                    Text("⚠️ Circuit Breaker OPEN", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            Text(
                                "📊 Chưa có dữ liệu học tập — sẽ có sau lần quét đầu tiên",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // === Cấu hình camera ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cấu hình", style = MaterialTheme.typography.titleSmall)
                            if (configDraft == null) {
                                TextButton(onClick = { viewModel.openConfigEditor() }) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sửa")
                                }
                            }
                        }

                        AnimatedVisibility(visible = configDraft == null) {
                            // Chế độ xem (read-only)
                            Column {
                                Spacer(Modifier.height(4.dp))
                                DetailRow("Snapshot URL", cam.snapshoturl.ifBlank { "—" })
                                DetailRow("Vị trí", cam.landinfo?.ifBlank { "—" } ?: "—")
                                DetailRow("AI Prompt", cam.aiPrompt.ifBlank { "(mặc định)" })
                                DetailRow("Từ khoá (+)", cam.aiPositiveKeywords.ifBlank { "(mặc định)" })
                                DetailRow("Từ khoá (−)", cam.aiNegativeKeywords.ifBlank { "(mặc định)" })
                            }
                        }

                        AnimatedVisibility(visible = configDraft != null) {
                            // Chế độ chỉnh sửa inline
                            configDraft?.let { draft ->
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draft.snapshotUrl,
                                        onValueChange = { v -> viewModel.updateConfigDraft { copy(snapshotUrl = v) } },
                                        label = { Text("Snapshot URL") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    val gpsContext = androidx.compose.ui.platform.LocalContext.current
                                    var gpsLoading by remember { mutableStateOf(false) }
                                    var gpsError by remember { mutableStateOf<String?>(null) }
                                    OutlinedTextField(
                                        value = draft.landInfo,
                                        onValueChange = { v -> viewModel.updateConfigDraft { copy(landInfo = v) } },
                                        label = { Text("Vị trí / Ghi chú") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    gpsError = null
                                                    val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(gpsContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(gpsContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (!hasFine && !hasCoarse) { gpsError = "Chưa cấp quyền vị trí"; return@IconButton }
                                                    gpsLoading = true
                                                    val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(gpsContext)
                                                    fusedClient.lastLocation
                                                        .addOnSuccessListener { loc ->
                                                            if (loc != null) {
                                                                viewModel.updateConfigDraft { copy(landInfo = "${loc.latitude},${loc.longitude}") }
                                                                gpsLoading = false
                                                            } else {
                                                                val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
                                                                    .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                                                                    .setMaxUpdateAgeMillis(0)
                                                                    .build()
                                                                fusedClient.getCurrentLocation(req, null)
                                                                    .addOnSuccessListener { fresh ->
                                                                        if (fresh != null) viewModel.updateConfigDraft { copy(landInfo = "${fresh.latitude},${fresh.longitude}") }
                                                                        else gpsError = "Không lấy được vị trí"
                                                                        gpsLoading = false
                                                                    }
                                                                    .addOnFailureListener { e -> gpsError = e.message; gpsLoading = false }
                                                            }
                                                        }
                                                        .addOnFailureListener { e -> gpsError = e.message; gpsLoading = false }
                                                },
                                                enabled = !gpsLoading
                                            ) {
                                                if (gpsLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                                else Icon(Icons.Default.MyLocation, contentDescription = "Lấy vị trí")
                                            }
                                        }
                                    )
                                    if (gpsError != null) Text(gpsError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = draft.aiPrompt,
                                        onValueChange = { v -> viewModel.updateConfigDraft { copy(aiPrompt = v) } },
                                        label = { Text("AI Prompt (để trống = dùng mặc định)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 3,
                                        maxLines = 5
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = draft.aiPositiveKeywords,
                                        onValueChange = { v -> viewModel.updateConfigDraft { copy(aiPositiveKeywords = v) } },
                                        label = { Text("Từ khoá cảnh báo (+), cách nhau bằng dấu phẩy") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = draft.aiNegativeKeywords,
                                        onValueChange = { v -> viewModel.updateConfigDraft { copy(aiNegativeKeywords = v) } },
                                        label = { Text("Từ khoá bình thường (−), cách nhau bằng dấu phẩy") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.closeConfigEditor() },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Huỷ") }
                                        Button(
                                            onClick = { viewModel.saveConfig() },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isLoading
                                        ) {
                                            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            else Text("Lưu")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // === Lịch trình ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Lịch trình (${schedules.size})", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { viewModel.openAddSchedule() }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Thêm")
                            }
                        }
                        if (schedules.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Chưa có lịch — nhấn Thêm để tạo lịch quét tự động",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(schedules, key = { it.id }) { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    onToggle = { viewModel.toggleSchedule(schedule) },
                    onEdit = { viewModel.openEditSchedule(schedule) },
                    onDelete = { viewModel.deleteSchedule(schedule.id) }
                )
            }

            // === Cảnh báo gần đây ===
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cảnh báo gần đây", style = MaterialTheme.typography.titleSmall)
                    if (recentAlerts.isNotEmpty()) {
                        TextButton(onClick = {
                            navController.navigate("alert_history?cameraId=${viewModel.cameraId}")
                        }) { Text("Xem tất cả") }
                    }
                }
            }

            if (recentAlerts.isEmpty()) {
                item {
                    Text(
                        "Chưa có cảnh báo nào cho camera này",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(recentAlerts.take(5), key = { it.id }) { alert ->
                    MiniAlertRow(alert)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun CamStatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScheduleRow(
    schedule: ScheduleEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Xoá lịch?") },
            text = { Text("Lịch này sẽ bị xoá vĩnh viễn.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Huỷ") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (schedule.cron.isNotBlank()) "⏰ ${schedule.cron}" else "🔁 mỗi ${schedule.intervalMinutes} phút",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Action: ${schedule.action}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (schedule.lastRunAt > 0L) {
                    val lastRun = remember(schedule.lastRunAt) {
                        SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(schedule.lastRunAt))
                    }
                    Text(
                        text = "Chạy lần cuối: $lastRun",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = schedule.enabled == 1,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Sửa", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Xoá", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ScheduleFormSheet(
    draft: ScheduleDraft,
    isLoading: Boolean,
    onUpdate: (ScheduleDraft.() -> ScheduleDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val isEdit = draft.id.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (isEdit) "Sửa lịch trình" else "Thêm lịch trình",
            style = MaterialTheme.typography.titleMedium
        )

        // Action
        Text("Action", style = MaterialTheme.typography.labelMedium)
        val actions = listOf("scan")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                FilterChip(
                    selected = draft.action == action,
                    onClick = { onUpdate { copy(action = action) } },
                    label = { Text(action) }
                )
            }
        }

        // Cron hoặc interval
        OutlinedTextField(
            value = draft.cron,
            onValueChange = { onUpdate { copy(cron = it) } },
            label = { Text("Cron (vd: 0 7 * * * = 7h sáng mỗi ngày)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Ưu tiên nếu điền cả hai") }
        )

        OutlinedTextField(
            value = if (draft.intervalMinutes > 0) draft.intervalMinutes.toString() else "",
            onValueChange = { v -> onUpdate { copy(intervalMinutes = v.toIntOrNull() ?: 0) } },
            label = { Text("Hoặc interval (phút), vd: 30") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Kích hoạt ngay", style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = draft.enabled,
                onCheckedChange = { onUpdate { copy(enabled = it) } }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Huỷ") }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (isEdit) "Cập nhật" else "Thêm")
            }
        }
    }
}

@Composable
private fun MiniAlertRow(alert: AlertEntity) {
    val timeText = remember(alert.timestamp) {
        SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(alert.timestamp))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (alert.isSuspicious == 1) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = if (alert.isSuspicious == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alert.aiComment, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
