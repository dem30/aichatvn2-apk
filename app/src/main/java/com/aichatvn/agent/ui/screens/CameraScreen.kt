package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.layout.Arrangement
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
    
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    
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
        }
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
                            onToggleActive = { viewModel.toggleCameraActive(camera.id, camera.isOnline != 1) },
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
                    // Status indicator
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
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle active switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Theo dõi", style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = isOnline && camera.manualOff == 0,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (camera == null) "Thêm Camera" else "Sửa Camera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Mã camera") }, modifier = Modifier.fillMaxWidth(), enabled = camera == null)
                OutlinedTextField(value = customerId, onValueChange = { customerId = it }, label = { Text("Mã khách hàng") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customerName, onValueChange = { customerName = it }, label = { Text("Tên khách hàng") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customerEmail, onValueChange = { customerEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = snapshotUrl, onValueChange = { snapshotUrl = it }, label = { Text("URL ảnh chụp") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = landInfo, onValueChange = { landInfo = it }, label = { Text("Thông tin thửa đất") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (id.isNotBlank() && snapshotUrl.isNotBlank()) {
                    onSave(mapOf(
                        "id" to id, "customerId" to customerId,
                        "customername" to customerName, "customeremail" to customerEmail,
                        "snapshoturl" to snapshotUrl, "landinfo" to landInfo
                    ))
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}
