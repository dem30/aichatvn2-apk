package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.ui.viewmodels.AlertHistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertHistoryScreen(
    navController: NavController,
    viewModel: AlertHistoryViewModel = hiltViewModel()
) {
    val alerts by viewModel.alerts.collectAsState()
    var fullImagePath by remember { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isFiltered && alerts.isNotEmpty()) "Cảnh báo · ${alerts.first().cameraName}"
                        else "Lịch sử cảnh báo"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    if (alerts.isNotEmpty()) {
                        IconButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Đánh dấu đã đọc hết")
                        }
                        IconButton(onClick = { showDeleteAllConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Xóa tất cả")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (alerts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔔", fontSize = TextUnit(48f, TextUnitType.Sp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chưa có cảnh báo nào",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Cảnh báo sẽ xuất hiện ở đây khi AI phát hiện biến động",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alerts, key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        onClick = {
                            if (alert.isRead == 0) viewModel.markAsRead(alert.id)
                            if (alert.imagePath != null) fullImagePath = alert.imagePath
                        },
                        onDelete = { viewModel.deleteAlert(alert.id) },
                        onMarkFalsePositive = { viewModel.markAsFalsePositive(alert) }
                    )
                }
            }
        }
    }

    if (fullImagePath != null) {
        Dialog(onDismissRequest = { fullImagePath = null }) {
            var fullBitmap by remember(fullImagePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
            
            LaunchedEffect(fullImagePath) {
                if (fullImagePath != null) {
                    fullBitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = java.io.File(fullImagePath!!)
                            if (f.exists()) BitmapFactory.decodeFile(fullImagePath) else null
                        }.getOrNull()
                    }
                }
            }

            if (fullBitmap != null) {
                Image(
                    bitmap = fullBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Card { Text("Không tìm thấy ảnh", modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Xóa tất cả cảnh báo?") },
            text = { Text("Toàn bộ lịch sử cảnh báo và ảnh đính kèm sẽ bị xóa vĩnh viễn.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    showDeleteAllConfirm = false
                }) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun AlertCard(
    alert: AlertEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMarkFalsePositive: () -> Unit
) {
    val isUnread = alert.isRead == 0

    // ✅ MỚI (Tuần 3 - Phase 3): Tính toán hiển thị thời gian động khi có sự kiện nén dạng khoảng [bắt đầu] - [kết thúc]
    val timeText = remember(alert.timestamp, alert.endTime) {
        val startText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.timestamp))
        val dateText = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(alert.timestamp))
        if (alert.endTime != null && alert.endTime!! > alert.timestamp) {
            val endText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.endTime!!))
            "$startText - $endText | $dateText"
        } else {
            "$startText $dateText"
        }
    }

    var thumbnailBitmap by remember(alert.imagePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(alert.imagePath) {
        if (alert.imagePath != null) {
            thumbnailBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(alert.imagePath!!)
                    if (f.exists()) BitmapFactory.decodeFile(alert.imagePath) else null
                }.getOrNull()
            }
        } else {
            thumbnailBitmap = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isUnread) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            alert.cameraName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // ✅ MỚI: nút "Báo động giả" — chỉ hiện khi alert còn đang isSuspicious=1,
                        // ẩn đi sau khi đã xác nhận (tránh học lại nhiều lần trên cùng 1 alert).
                        if (alert.isSuspicious == 1) {
                            IconButton(onClick = onMarkFalsePositive, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.ThumbDown,
                                    contentDescription = "Báo động giả",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Xóa", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    alert.aiComment,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "diff=${alert.diff} (ngưỡng ${alert.absDiffTrigger})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (!alert.scheduleLabel.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = alert.scheduleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    if (alert.emailSent == 1) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Email,
                            contentDescription = "Đã gửi email",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}