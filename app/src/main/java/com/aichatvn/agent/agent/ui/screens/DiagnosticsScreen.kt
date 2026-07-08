package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.AuditMessageResult
import com.aichatvn.agent.ui.viewmodels.DiagnosticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val stats by viewModel.combinedStats.collectAsState()
    val learningStats = stats["learningStats"] as? Map<String, Any> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val cameras = stats["cameras"] as? List<Map<String, Any>> ?: emptyList()
    val totalCameras = stats["totalCameras"] as? Int ?: 0
    val onlineCameras = stats["onlineCameras"] as? Int ?: 0
    val offlineCameras = stats["offlineCameras"] as? Int ?: 0
    val disabledCameras = stats["disabledCameras"] as? Int ?: 0

    // ✅ MỚI: Tổng quan hệ thống — gộp từ check_status của HousekeeperSkill cũ
    val onlineTuya = stats["onlineTuyaDevices"] as? Int ?: 0
    val totalTuya = stats["totalTuyaDevices"] as? Int ?: 0
    val unreadAlerts = stats["unreadAlerts"] as? Int ?: 0
    val activeSchedules = stats["activeSchedules"] as? Int ?: 0
    val totalSchedules = stats["totalSchedules"] as? Int ?: 0

    // ✅ MỚI: Kiểm toán tin nhắn khách hàng
    val messageResults by viewModel.messageFilterResults.collectAsState()
    val isFilteringMessages by viewModel.isFilteringMessages.collectAsState()
    var keyword by remember { mutableStateOf("") }
    var onlyWithPhone by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var resetMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resetMessage) {
        resetMessage?.let {
            snackbarHostState.showSnackbar(it)
            resetMessage = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chẩn đoán & Kiểm toán hệ thống") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Circuit Breaker ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("🔌 Circuit Breaker", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Tạm ngưng quét camera khi mất kết nối liên tục",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.resetAllCircuitBreakers()
                                resetMessage = "✅ Đã reset tất cả Circuit Breaker"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("🔄 Reset all")
                        }
                    }
                }
            }

            // ── Camera summary ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatSummaryCard("📷 Tổng", "$totalCameras", modifier = Modifier.weight(1f))
                    StatSummaryCard(
                        "🟢 Online", "$onlineCameras",
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatSummaryCard(
                        "🔴 Offline", "$offlineCameras",
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatSummaryCard(
                        "⛔ Tắt", "$disabledCameras",
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Tổng quan hệ thống (gộp từ Housekeeper.check_status) ──
            item {
                Text(
                    "📋 Tổng quan hệ thống",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatSummaryCard("🔌 Tuya online", "$onlineTuya/$totalTuya", modifier = Modifier.weight(1f))
                    // ✅ MỚI: Trước đây con số này chỉ để xem, không bấm được — AlertHistoryScreen
                    // đã có sẵn route "alert_history" trong AppNavigator nhưng không nơi nào gọi
                    // navigate() tới nó. Đây là entry point thật đầu tiên cho màn Lịch sử cảnh báo.
                    StatSummaryCard(
                        "🔔 Cảnh báo chưa đọc", "$unreadAlerts",
                        color = if (unreadAlerts > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { navController.navigate("alert_history") }
                    )
                    StatSummaryCard("🗓 Lịch hoạt động", "$activeSchedules/$totalSchedules", modifier = Modifier.weight(1f))
                }
            }

            // ── Danh sách camera ──
            if (cameras.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📭", fontSize = TextUnit(48f, TextUnitType.Sp))
                            Spacer(Modifier.height(8.dp))
                            Text("Chưa có camera nào được cấu hình", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(cameras, key = { it["id"] as? String ?: "" }) { camera ->
                    val cameraId = camera["id"] as? String ?: ""
                    val cameraName = camera["name"] as? String ?: ""
                    val cameraStatus = camera["status"] as? String ?: "Unknown"
                    val cameraStats = learningStats[cameraId] as? Map<String, Any>

                    CameraDiagnosticCard(
                        cameraId = cameraId,
                        cameraName = cameraName,
                        status = cameraStatus,
                        stats = cameraStats,
                        onResetCircuitBreaker = {
                            viewModel.resetCircuitBreaker(cameraId)
                            resetMessage = "✅ Đã reset Circuit Breaker cho camera $cameraName"
                        }
                    )
                }
            }

            // ── Kiểm toán tin nhắn khách hàng ──
            item {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Text("🔍 Kiểm toán tin nhắn khách hàng", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Lọc theo từ khóa hoặc số điện thoại — chỉ Admin xem được, không liên quan tới chat của khách.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("Từ khóa trong nội dung") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlyWithPhone, onCheckedChange = { onlyWithPhone = it })
                        Text("Chỉ tin nhắn có số điện thoại")
                    }
                    Row {
                        if (messageResults.isNotEmpty()) {
                            TextButton(onClick = {
                                viewModel.clearMessageFilter()
                                keyword = ""
                                onlyWithPhone = false
                            }) { Text("Xoá lọc") }
                        }
                        Button(onClick = {
                            viewModel.filterMessages(keyword = keyword, onlyWithPhone = onlyWithPhone)
                        }) {
                            Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isFilteringMessages) "Đang lọc..." else "Lọc")
                        }
                    }
                }
            }
            if (messageResults.isEmpty() && !isFilteringMessages) {
                item {
                    Text(
                        "Chưa có kết quả — nhập điều kiện lọc rồi bấm Lọc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(messageResults) { msg ->
                    AuditMessageCard(msg)
                }
            }
        }
    }
}

@Composable
private fun AuditMessageCard(msg: AuditMessageResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(msg.username, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(msg.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(msg.content, style = MaterialTheme.typography.bodySmall)
            if (msg.phones.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "📞 ${msg.phones.joinToString()}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatSummaryCard(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color)) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun CameraDiagnosticCard(
    cameraId: String,
    cameraName: String,
    status: String,
    stats: Map<String, Any>?,
    onResetCircuitBreaker: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(cameraName, style = MaterialTheme.typography.titleSmall)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (status) {
                        "Hoạt động" -> MaterialTheme.colorScheme.primaryContainer
                        "Mất kết nối" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Text(
                text = "ID: $cameraId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            if (stats != null) {
                StatRow("Mẫu học", "${stats["samples"] ?: 0}")
                StatRow("Sự kiện thật", "${stats["realEvents"] ?: 0}")
                StatRow("Ngưỡng delta", "${stats["deltaTrigger"] ?: 10}")
                StatRow("Ngưỡng diff", "${stats["absDiffTrigger"] ?: 18}")
                StatRow("Baseline size", "${stats["baselineSize"] ?: 0}")

                val inCooldown = stats["inCooldown"] as? Boolean ?: false
                if (inCooldown) {
                    Spacer(Modifier.height(4.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "⏳ Đang cooldown",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                val circuitOpen = stats["circuitBreakerOpen"] as? Boolean ?: false
                if (circuitOpen) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                "⚠️ Circuit Breaker OPEN",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(
                            onClick = onResetCircuitBreaker,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Refresh,
                                contentDescription = "Reset",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                Text(
                    text = if (status == "Mất kết nối")
                        "⚠️ Camera offline, chưa thu thập được dữ liệu"
                    else
                        "📊 Chưa có dữ liệu học tập — sẽ có sau lần quét đầu tiên",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}