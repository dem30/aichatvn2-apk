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
import com.aichatvn.agent.ui.viewmodels.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    navController: NavController,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameras by viewModel.cameras.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState(initial = false)
    val testResult by viewModel.testResult.collectAsState()
    val smartModes by viewModel.smartModes.collectAsState()

    val alertHistoryViewModel: com.aichatvn.agent.ui.viewmodels.AlertHistoryViewModel = hiltViewModel()
    val unreadAlertCount by alertHistoryViewModel.unreadCount.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    var showTestDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Hiển thị kết quả test trong dialog thay vì snackbar (vì text dài)
    LaunchedEffect(testResult) {
        testResult?.let { showTestDialog = true }
    }

    LaunchedEffect(Unit) {
        viewModel.loadCameras()
    }

    // Dialog hiển thị kết quả test chi tiết
    if (showTestDialog && testResult != null) {
        AlertDialog(
            onDismissRequest = {
                showTestDialog = false
                viewModel.clearTestResult()
            },
            title = { Text("📊 Kết quả kiểm tra") },
            text = {
                Text(
                    text = testResult ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTestDialog = false
                    viewModel.clearTestResult()
                }) { Text("Đóng") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý Camera") },
                actions = {
                    IconButton(onClick = { navController.navigate("alert_history") }) {
                        if (unreadAlertCount > 0) {
                            BadgedBox(badge = { Badge { Text("$unreadAlertCount") } }) {
                                Icon(Icons.Default.Notifications, contentDescription = "Lịch sử cảnh báo")
                            }
                        } else {
                            Icon(Icons.Default.Notifications, contentDescription = "Lịch sử cảnh báo")
                        }
                    }
                    if (isAdmin) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Thêm camera")
                        }
                        IconButton(onClick = { viewModel.syncFromCloud() }) {
                            Icon(Icons.Default.Sync, contentDescription = "Đồng bộ")
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
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cameras) { camera ->
                        CameraCard(
                            camera = camera,
                            isAdmin = isAdmin,
                            isSmartMode = (smartModes[camera.customerId] ?: false) && (camera.smartMode == 1),
                            onEdit = { selectedCamera = camera },
                            onDelete = { viewModel.deleteCamera(camera.id) },
                            onToggleActive = { viewModel.toggleCameraActive(camera.id) },
                            onToggleSmartMode = { viewModel.toggleCameraSmartMode(camera.id) },
                            onTest = { viewModel.testCamera(camera.id) },
                            onOpenDetail = { navController.navigate("camera_detail/${camera.id}") }
                        )
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

@Composable
fun CameraCard(
    camera: CameraConfigEntity,
    isAdmin: Boolean,
    isSmartMode: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleSmartMode: () -> Unit,
    onTest: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val isTracking = camera.manualOff == 0  // đang theo dõi = chưa tắt thủ công
    val isOnline = camera.isOnline == 1

    val cardColor = when {
        camera.manualOff == 1 -> MaterialTheme.colorScheme.surfaceVariant
        !isOnline -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: tên + trạng thái
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

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        camera.manualOff == 1 -> MaterialTheme.colorScheme.surfaceVariant
                        !isOnline -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = when {
                            camera.manualOff == 1 -> "⏸ Đã tắt"
                            !isOnline -> "🔴 Mất kết nối"
                            else -> "🟢 Hoạt động"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = camera.landinfo ?: "Chưa có thông tin thửa đất",
                style = MaterialTheme.typography.bodyMedium
            )

            if (camera.aiPrompt.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🤖 ${camera.aiPrompt.take(60)}${if (camera.aiPrompt.length > 60) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isAdmin) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Toggle theo dõi
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
                    Switch(
                        checked = isTracking,
                        onCheckedChange = { onToggleActive() }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Toggle smart mode (AI phân tích)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AI Smart Mode", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (isSmartMode) "Bật — AI phân tích & gửi email khi có biến động"
                            else "Tắt — chỉ so sánh ảnh, không gọi AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSmartMode,
                        onCheckedChange = { onToggleSmartMode() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Nút hành động
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onTest) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test ngay")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Sửa")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa")
                    }
                }
            }
        }
    }
}

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
    var aiPrompt by remember { mutableStateOf(camera?.aiPrompt ?: "Camera giám sát thửa đất. Hãy xem có người/xe? hoặc xây dựng không. Nếu có ghi: cảnh báo và mô tả. Ngược lại ghi: Bình thường và mô tả.") }
    var aiPositiveKeywords by remember { mutableStateOf(camera?.aiPositiveKeywords ?: "cảnh báo") }
    var aiNegativeKeywords by remember { mutableStateOf(camera?.aiNegativeKeywords ?: "bình thường") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (camera == null) "Thêm Camera" else "Sửa Camera") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Mã camera") }, modifier = Modifier.fillMaxWidth(), enabled = camera == null)
                OutlinedTextField(value = customerId, onValueChange = { customerId = it }, label = { Text("Mã khách hàng") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customerName, onValueChange = { customerName = it }, label = { Text("Tên khách hàng") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customerEmail, onValueChange = { customerEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = snapshotUrl, onValueChange = { snapshotUrl = it }, label = { Text("URL ảnh chụp") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landInfo, onValueChange = { landInfo = it }, label = { Text("Thông tin thửa đất") }, modifier = Modifier.fillMaxWidth())

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Cài đặt AI", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(value = aiPrompt, onValueChange = { aiPrompt = it }, label = { Text("AI Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 4)
                OutlinedTextField(value = aiPositiveKeywords, onValueChange = { aiPositiveKeywords = it }, label = { Text("Từ khóa cảnh báo (phân cách bằng dấu phẩy)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("cảnh báo, phát hiện, xâm nhập") })
                OutlinedTextField(value = aiNegativeKeywords, onValueChange = { aiNegativeKeywords = it }, label = { Text("Từ khóa bình thường (phân cách bằng dấu phẩy)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("bình thường, không có") })

                val context = androidx.compose.ui.platform.LocalContext.current
                var locationError by remember { mutableStateOf<String?>(null) }
                var gpsLoading by remember { mutableStateOf(false) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            locationError = null
                            try {
                                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasFine && !hasCoarse) { locationError = "Chưa cấp quyền vị trí"; return@OutlinedButton }
                                // Thử cache trước
                                var best: android.location.Location? = null
                                for (p in lm.getProviders(true)) {
                                    val l = lm.getLastKnownLocation(p) ?: continue
                                    if (best == null || l.accuracy < best!!.accuracy) best = l
                                }
                                if (best != null) {
                                    val coord = "${best.latitude},${best.longitude}"
                                    landInfo = if (landInfo.isBlank()) coord else "$landInfo\nVị trí: $coord"
                                } else {
                                    // Không có cache → request 1 lần
                                    val provider = when {
                                        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
                                        lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
                                        else -> null
                                    }
                                    if (provider == null) {
                                        locationError = "GPS và mạng đều tắt, vào Settings bật lên"
                                    } else {
                                        gpsLoading = true
                                        locationError = "Đang lấy vị trí..."
                                        lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                                            override fun onLocationChanged(loc: android.location.Location) {
                                                val coord = "${loc.latitude},${loc.longitude}"
                                                landInfo = if (landInfo.isBlank()) coord else "$landInfo\nVị trí: $coord"
                                                locationError = null
                                                gpsLoading = false
                                            }
                                            @Deprecated("Deprecated in Java")
                                            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                                            override fun onProviderEnabled(p: String) {}
                                            override fun onProviderDisabled(p: String) {
                                                locationError = "Provider $p bị tắt"
                                                gpsLoading = false
                                            }
                                        }, android.os.Looper.getMainLooper())
                                    }
                                }
                            } catch (e: Exception) { locationError = "Lỗi: ${e.message}"; gpsLoading = false }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !gpsLoading
                    ) {
                        if (gpsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (gpsLoading) "Đang lấy..." else "📍 Lấy vị trí")
                    }

                    OutlinedButton(
                        onClick = {
                            locationError = null
                            val m = Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").find(landInfo)
                            if (m != null) {
                                val lat = m.groupValues[1]; val lng = m.groupValues[2]
                                try {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
                                } catch (e: Exception) { locationError = "Không mở được bản đồ" }
                            } else locationError = "Chưa có tọa độ, bấm 'Lấy vị trí' trước"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("🗺️ Mở bản đồ") }
                }

                if (locationError != null) {
                    Text(locationError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (id.isNotBlank() && snapshotUrl.isNotBlank()) {
                    onSave(mapOf(
                        "id" to id, "customerId" to customerId,
                        "customername" to customerName, "customeremail" to customerEmail,
                        "snapshoturl" to snapshotUrl, "landinfo" to landInfo,
                        "aiPrompt" to aiPrompt, "aiPositiveKeywords" to aiPositiveKeywords,
                        "aiNegativeKeywords" to aiNegativeKeywords
                    ))
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}