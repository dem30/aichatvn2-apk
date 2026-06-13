package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.clickable
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
    val context = LocalContext.current
    val cameras by viewModel.cameras.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState(initial = false)
    val testResult by viewModel.testResult.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    
    // Snackbar for test result
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(testResult) {
        testResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTestResult()
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.loadCameras()
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
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
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
                            onEdit = { selectedCamera = camera },
                            onDelete = { viewModel.deleteCamera(camera.id) },
                            onToggleActive = { viewModel.toggleCameraActive(camera.id, camera.manualOff == 0 && camera.isOnline == 1) },
                            onTest = { viewModel.testCamera(camera.id) }
                        )
                    }
                }
            }
        }
    }
    
    // Add/Edit dialog
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit,
    onTest: () -> Unit
) {
    val isOnline = camera.isOnline == 1 && camera.manualOff == 0
    val cardColor = when {
        camera.manualOff == 1 -> MaterialTheme.colorScheme.surfaceVariant
        !isOnline -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = camera.customername,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = camera.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOnline) MaterialTheme.colorScheme.primaryContainer
                               else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = when {
                                camera.manualOff == 1 -> "Đã tắt"
                                !isOnline -> "Mất kết nối"
                                else -> "Hoạt động"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = camera.landinfo ?: "Chưa có thông tin thửa đất",
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Hiển thị AI Prompt preview
            if (camera.aiPrompt.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🤖 Prompt: ${camera.aiPrompt.take(50)}${if (camera.aiPrompt.length > 50) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Theo dõi", style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = isOnline,
                            onCheckedChange = { onToggleActive() },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    IconButton(onClick = onTest) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test")
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
    camera: com.aichatvn.agent.data.model.CameraConfigEntity?,
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
                OutlinedTextField(
                    value = id, 
                    onValueChange = { id = it }, 
                    label = { Text("Mã camera") }, 
                    modifier = Modifier.fillMaxWidth(), 
                    enabled = camera == null
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
                    label = { Text("Email") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = snapshotUrl, 
                    onValueChange = { snapshotUrl = it }, 
                    label = { Text("URL ảnh chụp") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = landInfo, 
                    onValueChange = { landInfo = it }, 
                    label = { Text("Thông tin thửa đất") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Cài đặt AI", style = MaterialTheme.typography.titleSmall)
                
                OutlinedTextField(
                    value = aiPrompt,
                    onValueChange = { aiPrompt = it },
                    label = { Text("AI Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 4
                )
                
                OutlinedTextField(
                    value = aiPositiveKeywords,
                    onValueChange = { aiPositiveKeywords = it },
                    label = { Text("Từ khóa cảnh báo (cách nhau bằng dấu phẩy)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ví dụ: cảnh báo, phát hiện, xâm nhập") }
                )
                
                OutlinedTextField(
                    value = aiNegativeKeywords,
                    onValueChange = { aiNegativeKeywords = it },
                    label = { Text("Từ khóa bình thường (cách nhau bằng dấu phẩy)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ví dụ: bình thường, không có") }
                )

                val context = LocalContext.current
                var locationError by remember { mutableStateOf<String?>(null) }

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
                                if (!hasFine) {
                                    locationError = "Chưa cấp quyền vị trí (Location)"
                                    return@OutlinedButton
                                }
                                val providers = lm.getProviders(true)
                                var bestLocation: android.location.Location? = null
                                for (provider in providers) {
                                    val l = lm.getLastKnownLocation(provider) ?: continue
                                    if (bestLocation == null || l.accuracy < bestLocation!!.accuracy) {
                                        bestLocation = l
                                    }
                                }
                                if (bestLocation != null) {
                                    val lat = bestLocation!!.latitude
                                    val lng = bestLocation!!.longitude
                                    val coordText = "$lat,$lng"
                                    landInfo = if (landInfo.isBlank()) {
                                        coordText
                                    } else {
                                        "$landInfo\nVị trí: $coordText"
                                    }
                                } else {
                                    locationError = "Không lấy được vị trí (GPS chưa có dữ liệu, hãy mở Google Maps 1 lần rồi thử lại)"
                                }
                            } catch (e: Exception) {
                                locationError = "Lỗi lấy vị trí: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📍 Lấy vị trí")
                    }

                    OutlinedButton(
                        onClick = {
                            locationError = null
                            val coordRegex = Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)")
                            val match = coordRegex.find(landInfo)
                            if (match != null) {
                                val lat = match.groupValues[1]
                                val lng = match.groupValues[2]
                                try {
                                    val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    locationError = "Không mở được bản đồ: ${e.message}"
                                }
                            } else {
                                locationError = "Chưa có tọa độ trong ô 'Thông tin thửa đất'. Hãy bấm 'Lấy vị trí' trước."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🗺️ Mở bản đồ")
                    }
                }

                if (locationError != null) {
                    Text(
                        text = locationError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (id.isNotBlank() && snapshotUrl.isNotBlank()) {
                    onSave(mapOf(
                        "id" to id, 
                        "customerId" to customerId,
                        "customername" to customerName, 
                        "customeremail" to customerEmail,
                        "snapshoturl" to snapshotUrl, 
                        "landinfo" to landInfo,
                        "aiPrompt" to aiPrompt,
                        "aiPositiveKeywords" to aiPositiveKeywords,
                        "aiNegativeKeywords" to aiNegativeKeywords
                    ))
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}