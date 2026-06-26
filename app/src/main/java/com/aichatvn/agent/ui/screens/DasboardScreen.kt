package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.viewmodels.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val deviceNodes by viewModel.deviceNodes.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val executionMessage by viewModel.executionMessage.collectAsState()

    var selectedNode by remember { mutableStateOf<DeviceNode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(executionMessage) {
        executionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearExecutionMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sơ đồ điều khiển thiết bị") },
                actions = {
                    IconButton(onClick = { viewModel.refreshDashboardNodes() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            // SƠ ĐỒ ĐỊNH VỊ THIẾT BỊ (Canvas / Coordinate Layout)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                // Background biểu diễn Sơ đồ Nhà (🏠)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🏠", fontSize = 120.sp, modifier = Modifier.clip(CircleShape))
                    Text(
                        text = "AIChatVN Home",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 110.dp)
                    )
                }

                // Vẽ các node thiết bị động dựa theo tọa độ x, y định nghĩa sẵn trong DB/Skill
                deviceNodes.forEach { node ->
                    Box(
                        modifier = Modifier
                            .offset(x = node.x.dp, y = node.y.dp)
                            .clickable { selectedNode = node }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        color = if (node.online)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (node.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(node.icon, fontSize = 24.sp)
                            }
                            Text(
                                text = node.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }

            // Trạng thái hệ thống đang gửi lệnh
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
        }

        // BOTTOM SHEET ĐIỀU KHIỂN CHI TIẾT (PHẦN 7)
        if (selectedNode != null) {
            val node = selectedNode!!
            ModalBottomSheet(
                onDismissRequest = { selectedNode = null },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Tiêu đề Bottom Sheet
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(node.icon, fontSize = 36.sp)
                            Column {
                                Text(node.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Plugin ID: ${node.pluginId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        // Trạng thái kết nối
                        Surface(
                            color = if (node.online) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                            contentColor = if (node.online) Color(0xFF2E7D32) else Color(0xFFC62828),
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (node.online) "ONLINE" else "OFFLINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Thông số kỹ thuật của thiết bị
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Địa chỉ IP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(node.ip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("Pin / Nguồn điện", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${node.battery}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("Mã định danh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(node.deviceId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Text("Hành động", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    // Phân loại các nút điều khiển trực quan dựa trên DeviceType (PHẦN 7)
                    when (node.type) {
                        DeviceType.CAMERA -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.sendDeviceAction(node, "LIVE")
                                        selectedNode = null
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Xem Trực Tiếp")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.sendDeviceAction(node, "SNAPSHOT")
                                        selectedNode = null
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Chụp Ảnh")
                                }
                            }
                        }

                        DeviceType.LIGHT, DeviceType.SWITCH, DeviceType.PUMP -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.sendDeviceAction(node, "ON")
                                        selectedNode = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Power, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("BẬT (ON)")
                                }
                                Button(
                                    onClick = {
                                        viewModel.sendDeviceAction(node, "OFF")
                                        selectedNode = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PowerOff, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("TẮT (OFF)")
                                }
                            }
                        }

                        DeviceType.FLYCAM -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.sendDeviceAction(node, "Takeoff"); selectedNode = null },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("🚀 Cất cánh") }
                                    Button(
                                        onClick = { viewModel.sendDeviceAction(node, "Land"); selectedNode = null },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("📉 Hạ cánh") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.sendDeviceAction(node, "Return Home"); selectedNode = null },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("🏠 Trở về") }
                                    OutlinedButton(
                                        onClick = { viewModel.sendDeviceAction(node, "Follow"); selectedNode = null },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("🏃 Bám theo") }
                                }
                            }
                        }

                        DeviceType.ROBOT -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.sendDeviceAction(node, "Patrol"); selectedNode = null },
                                    modifier = Modifier.weight(1f)
                                ) { Text("🚨 Tuần tra") }
                                Button(
                                    onClick = { viewModel.sendDeviceAction(node, "Go to Base"); selectedNode = null },
                                    modifier = Modifier.weight(1f)
                                ) { Text("⚡ Sạc điện") }
                                OutlinedButton(
                                    onClick = { viewModel.sendDeviceAction(node, "Stop"); selectedNode = null },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                    modifier = Modifier.weight(1f)
                                ) { Text("🛑 Dừng lại") }
                            }
                        }

                        DeviceType.LOCK -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.sendDeviceAction(node, "UNLOCK"); selectedNode = null },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.weight(1f)
                                ) { Text("🔓 Mở khóa") }
                                Button(
                                    onClick = { viewModel.sendDeviceAction(node, "LOCK"); selectedNode = null },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text("🔒 Khóa") }
                            }
                        }

                        else -> {
                            Button(
                                onClick = { viewModel.sendDeviceAction(node, "REFRESH"); selectedNode = null },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Làm mới trạng thái")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}