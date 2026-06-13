package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.ui.viewmodels.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    navController: NavController,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameras by viewModel.cameras.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val customerSettings by viewModel.customerSettings.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(testResult) {
        testResult?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearTestResult()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar("⚠️ $it")
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Camera") },
                actions = {
                    if (isAdmin) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Thêm camera")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (cameras.isEmpty()) {
                Text(
                    text = "Chưa có camera nào. Hãy thêm camera mới!",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Nhóm camera theo customerId
                val grouped = cameras.groupBy { it.customerId }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (customerId, cameraList) ->
                        val setting = customerSettings[customerId]
                        item {
                            CustomerSection(
                                customerId = customerId,
                                setting = setting,
                                isAdmin = isAdmin,
                                onSetSmartMode = { enabled -> viewModel.setSmartMode(customerId, enabled) },
                                onSetActive = { active -> viewModel.setCustomerActive(customerId, active) },
                                onDeleteCustomer = { viewModel.deleteCustomer(customerId) }
                            )
                        }
                        items(cameraList) { camera ->
                            CameraCard(
                                camera = camera,
                                isAdmin = isAdmin,
                                onEdit = { selectedCamera = camera },
                                onDelete = { viewModel.deleteCamera(camera.id) },
                                onToggleActive = { viewModel.toggleCameraActive(camera.id, camera.manualOff) },
                                onTest = { viewModel.testCamera(camera.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog || selectedCamera != null) {
        CameraDialog(
            camera = selectedCamera,
            onDismiss = {
                showAddDialog = false
                selectedCamera = null
            },
            onSave = { config ->
                viewModel.saveCamera(config)
                showAddDialog = false
                selectedCamera = null
            }
        )
    }
}

// ─── CustomerSection: hiển thị SmartMode + isActive cho từng nhóm khách hàng ───

@Composable
fun CustomerSection(
    customerId: String,
    setting: CustomerSettingEntity?,
    isAdmin: Boolean,
    onSetSmartMode: (Boolean) -> Unit,
    onSetActive: (Boolean) -> Unit,
    onDeleteCustomer: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Khách hàng: $customerId",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (setting == null) {
                        Text(
                            text = "⚠️ Chưa có CustomerSetting",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (isAdmin) {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Xoá khách hàng")
                    }
                }
            }

            if (isAdmin && setting != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Smart Mode
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Smart AI", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = setting.smartMode == 1,
                            onCheckedChange = { onSetSmartMode(it) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                    // isActive (dịch vụ bật/tắt)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dịch vụ", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = setting.isActive == 1,
                            onCheckedChange = { onSetActive(it) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Xoá khách hàng") },
            text = { Text("Xoá toàn bộ camera và dữ liệu của khách hàng \"$customerId\"? Hành động này không thể hoàn tác.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCustomer()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Xoá") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Huỷ") }
            }
        )
    }
}

// ─── CameraCard ───────────────────────────────────────────────────────────────

@Composable
fun CameraCard(
    camera: CameraConfigEntity,
    isAdmin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit,
    onTest: () -> Unit
) {
    // manualOff == 0 → đang theo dõi; manualOff == 1 → user đã tắt
    val isTracking = camera.manualOff == 0
    val isOnline = camera.isOnline == 1

    val cardColor = when {
        !isTracking -> MaterialTheme.colorScheme.surfaceVariant
        !isOnline   -> MaterialTheme.colorScheme.errorContainer
        else        -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(camera.customername, style = MaterialTheme.typography.titleMedium)
                    Text(
                        camera.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(isTracking = isTracking, isOnline = isOnline)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Thông tin thửa đất
            if (!camera.landinfo.isNullOrBlank()) {
                Text(
                    text = "📍 ${camera.landinfo}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // URL snapshot
            Text(
                text = "🔗 ${camera.snapshoturl.take(60)}${if (camera.snapshoturl.length > 60) "..." else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // AI Prompt preview
            if (camera.aiPrompt.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "🤖 ${camera.aiPrompt.take(60)}${if (camera.aiPrompt.length > 60) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Admin controls
            if (isAdmin) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle theo dõi (manualOff)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Theo dõi", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = isTracking,
                            onCheckedChange = { onToggleActive() },
                            modifier = Modifier.height(32.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        IconButton(onClick = onTest) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Test camera", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Sửa")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Xoá", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(isTracking: Boolean, isOnline: Boolean) {
    val (label, color) = when {
        !isTracking -> "Đã tắt" to MaterialTheme.colorScheme.surfaceVariant
        !isOnline   -> "Mất kết nối" to MaterialTheme.colorScheme.errorContainer
        else        -> "Hoạt động" to MaterialTheme.colorScheme.primaryContainer
    }
    Surface(shape = RoundedCornerShape(12.dp), color = color) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ─── CameraDialog ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDialog(
    camera: CameraConfigEntity?,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any>) -> Unit
) {
    var id by remember { mutableStateOf(camera?.id ?: java.util.UUID.randomUUID().toString()) }
    var customerId by remember { mutableStateOf(camera?.customerId ?: "") }
    var customerName by remember { mutableStateOf(camera?.customername ?: "") }
    var customerEmail by remember { mutableStateOf(camera?.customeremail ?: "") }
    var snapshotUrl by remember { mutableStateOf(camera?.snapshoturl ?: "") }
    var landInfo by remember { mutableStateOf(camera?.landinfo ?: "") }
    var aiPrompt by remember {
        mutableStateOf(
            camera?.aiPrompt?.ifBlank { null }
                ?: "Camera giám sát thửa đất. Hãy xem có người/xe? hoặc xây dựng không. Nếu có ghi: cảnh báo và mô tả. Ngược lại ghi: Bình thường và mô tả."
        )
    }
    var aiPositiveKeywords by remember { mutableStateOf(camera?.aiPositiveKeywords?.ifBlank { null } ?: "cảnh báo") }
    var aiNegativeKeywords by remember { mutableStateOf(camera?.aiNegativeKeywords?.ifBlank { null } ?: "bình thường") }

    // Validation
    var idError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (camera == null) "Thêm Camera" else "Sửa Camera") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it; idError = false },
                    label = { Text("Mã camera *") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = camera == null,
                    isError = idError,
                    supportingText = if (idError) ({ Text("Bắt buộc") }) else null
                )
                OutlinedTextField(
                    value = customerId,
                    onValueChange = { customerId = it },
                    label = { Text("Mã khách hàng") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("Tên khách hàng") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customerEmail,
                    onValueChange = { customerEmail = it },
                    label = { Text("Email nhận cảnh báo") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = snapshotUrl,
                    onValueChange = { snapshotUrl = it; urlError = false },
                    label = { Text("URL ảnh chụp (snapshot) *") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = urlError,
                    supportingText = if (urlError) ({ Text("Bắt buộc") }) else null,
                    placeholder = { Text("http://192.168.1.x/snapshot.jpg") }
                )

                // Lấy vị trí GPS
                val context = LocalContext.current
                var locationError by remember { mutableStateOf<String?>(null) }

                OutlinedTextField(
                    value = landInfo,
                    onValueChange = { landInfo = it },
                    label = { Text("Thông tin thửa đất / toạ độ") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            locationError = null
                            try {
                                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasFine) { locationError = "Chưa cấp quyền vị trí"; return@OutlinedButton }
                                val providers = lm.getProviders(true)
                                var best: android.location.Location? = null
                                for (p in providers) {
                                    val l = lm.getLastKnownLocation(p) ?: continue
                                    if (best == null || l.accuracy < best!!.accuracy) best = l
                                }
                                if (best != null) {
                                    val coord = "${best!!.latitude},${best!!.longitude}"
                                    landInfo = if (landInfo.isBlank()) coord else "$landInfo\nVị trí: $coord"
                                } else {
                                    locationError = "Không lấy được vị trí. Hãy mở Maps một lần rồi thử lại."
                                }
                            } catch (e: Exception) {
                                locationError = "Lỗi: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("📍 Lấy GPS") }

                    OutlinedButton(
                        onClick = {
                            locationError = null
                            val match = Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").find(landInfo)
                            if (match != null) {
                                val (lat, lng) = match.destructured
                                try {
                                    val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                } catch (e: Exception) {
                                    locationError = "Không mở được bản đồ"
                                }
                            } else {
                                locationError = "Chưa có toạ độ, hãy bấm 'Lấy GPS' trước."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("🗺️ Xem bản đồ") }
                }

                if (locationError != null) {
                    Text(locationError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Cài đặt AI", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = aiPrompt,
                    onValueChange = { aiPrompt = it },
                    label = { Text("AI Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 5
                )
                OutlinedTextField(
                    value = aiPositiveKeywords,
                    onValueChange = { aiPositiveKeywords = it },
                    label = { Text("Từ khoá cảnh báo (phân cách bằng dấu phẩy)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("cảnh báo, phát hiện, xâm nhập") }
                )
                OutlinedTextField(
                    value = aiNegativeKeywords,
                    onValueChange = { aiNegativeKeywords = it },
                    label = { Text("Từ khoá bình thường") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("bình thường, không có") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                idError = id.isBlank()
                urlError = snapshotUrl.isBlank()
                if (!idError && !urlError) {
                    onSave(
                        mapOf(
                            "id" to id,
                            "customerId" to customerId,
                            "customername" to customerName,
                            "customeremail" to customerEmail,
                            "snapshoturl" to snapshotUrl,
                            "landinfo" to landInfo,
                            "aiPrompt" to aiPrompt,
                            "aiPositiveKeywords" to aiPositiveKeywords,
                            "aiNegativeKeywords" to aiNegativeKeywords
                        )
                    )
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}
