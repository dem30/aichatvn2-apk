package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.ui.viewmodels.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val summary by viewModel.summary.collectAsState()
    val recentAlerts by viewModel.recentAlerts.collectAsState()
    val unreadAlertCount by viewModel.unreadAlertCount.collectAsState()
    val todayAlertCount by viewModel.todayAlertCount.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanResultMessage by viewModel.scanResultMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(scanResultMessage) {
        scanResultMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearScanResult()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tổng quan") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Tổng quan camera
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard("📷 Tổng", "${summary.totalCameras}", modifier = Modifier.weight(1f))
                SummaryCard(
                    "🟢 Online", "${summary.onlineCameras}",
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    "🔴 Offline", "${summary.offlineCameras}",
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    "⛔ Đã tắt", "${summary.disabledCameras}",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }

            // Cảnh báo hệ thống: cooldown / circuit breaker
            if (summary.cooldownCameras > 0 || summary.circuitBreakerOpenCameras > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (summary.cooldownCameras > 0) {
                            Text("⏳ ${summary.cooldownCameras} camera đang trong thời gian cooldown")
                        }
                        if (summary.circuitBreakerOpenCameras > 0) {
                            Text("🔌 ${summary.circuitBreakerOpenCameras} camera đang bị tạm ngưng quét (mất kết nối liên tục)")
                        }
                    }
                }
            }

            // Cảnh báo trong ngày + lối vào Lịch sử
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🔔 Cảnh báo hôm nay", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "$todayAlertCount cảnh báo" +
                                if (unreadAlertCount > 0) " • $unreadAlertCount chưa đọc" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { navController.navigate("alert_history") }) {
                        Text("Xem tất cả")
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            // Nút quét tất cả ngay
            Button(
                onClick = { viewModel.scanAllNow() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isScanning
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Đang quét...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quét tất cả camera ngay")
                }
            }

            HorizontalDivider()

            // Cảnh báo gần đây
            Text("Cảnh báo gần đây", style = MaterialTheme.typography.titleMedium)

            if (recentAlerts.isEmpty()) {
                Text(
                    "Chưa có cảnh báo nào. Hệ thống sẽ tự động ghi nhận khi AI phát hiện biến động.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentAlerts.forEach { alert ->
                        RecentAlertRow(
                            alert = alert,
                            onClick = { navController.navigate("alert_history") }
                        )
                    }
                }
            }

            // Đi tới quản lý camera
            OutlinedButton(
                onClick = { navController.navigate("camera") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Quản lý Camera")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentAlertRow(alert: AlertEntity, onClick: () -> Unit) {
    val timeText = remember(alert.timestamp) {
        SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(alert.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alert.isRead == 0)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(alert.cameraName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                alert.aiComment,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}
