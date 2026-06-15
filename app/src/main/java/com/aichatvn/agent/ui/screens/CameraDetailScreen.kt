package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.ui.viewmodels.CameraDetailViewModel
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

    var showTestDialog by remember { mutableStateOf(false) }
    LaunchedEffect(testResult) {
        testResult?.let { showTestDialog = true }
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
                            StatRow("Mẫu học", "${stats["samples"] ?: 0}")
                            StatRow("Sự kiện thật", "${stats["realEvents"] ?: 0}")
                            StatRow("Ngưỡng delta", "${stats["deltaTrigger"] ?: 10}")
                            StatRow("Ngưỡng diff", "${stats["absDiffTrigger"] ?: 18}")
                            StatRow("Baseline size", "${stats["baselineSize"] ?: 0}")

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
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.padding(start = 8.dp))
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
