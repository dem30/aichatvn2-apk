package com.aichatvn.agent.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichatvn.agent.data.model.HealthCategory
import com.aichatvn.agent.data.model.HealthItem
import com.aichatvn.agent.data.model.HealthStatus
import com.aichatvn.agent.ui.viewmodels.SystemHealthViewModel

/**
 * SystemHealthScreen
 *
 * Màn chi tiết SHAS — liệt kê từng mục theo nhóm (Camera/Thiết bị/Hạ tầng), mỗi mục có icon
 * trạng thái + nút "Áp dụng" riêng cho mục IMPROVABLE+safeToAutoApply, hoặc chỉ mô tả cho
 * mục CONFLICT (không có nút, đúng nguyên tắc "cần người dùng tự quyết định ở đúng màn hình
 * chi tiết" — vd mục Tuya local_key dẫn người dùng sang màn Thiết bị thay vì tự động hoá ở
 * đây).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemHealthScreen(
    onBack: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    viewModel: SystemHealthViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsState()
    val isAuditing by viewModel.isAuditing.collectAsState()
    val isApplying by viewModel.isApplying.collectAsState()
    val resultMessage by viewModel.resultMessage.collectAsState()

    // TODO: khi có SnackbarHost chung của app, hiện resultMessage ở đó rồi consume.
    // Tạm thời chỉ consume để tránh giữ message cũ vô thời hạn trong state.
    LaunchedEffect(resultMessage) {
        if (resultMessage != null) {
            viewModel.consumeResultMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sức khoẻ hệ thống") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isAuditing && report == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Đang quét hệ thống...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                report == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Chưa có dữ liệu.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { viewModel.runAudit() }) {
                            Text("Quét lại")
                        }
                    }
                }
                else -> {
                    val items = report!!.items
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthCategory.values().forEach { category ->
                            val categoryItems = items.filter { it.category == category }
                            if (categoryItems.isNotEmpty()) {
                                item {
                                    Text(
                                        text = categoryTitle(category),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(categoryItems, key = { it.id }) { healthItem ->
                                    HealthItemRow(
                                        item = healthItem,
                                        isApplying = isApplying,
                                        onApply = { viewModel.applyItems(listOf(healthItem)) },
                                        onNavigate = { route -> onNavigateToRoute(route) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryTitle(category: HealthCategory): String = when (category) {
    HealthCategory.CAMERA -> "Camera"
    HealthCategory.DEVICE -> "Thiết bị"
    HealthCategory.INFRA -> "Hạ tầng"
}

@Composable
private fun HealthItemRow(
    item: HealthItem,
    isApplying: Boolean,
    onApply: () -> Unit,
    onNavigate: (String) -> Unit
) {
    // UNSUPPORTED không hiển thị ở màn chi tiết — không có giá trị thông tin cho người dùng
    // cuối ("camera này không hỗ trợ X" không phải hành động họ có thể làm gì với nó).
    if (item.status == HealthStatus.UNSUPPORTED) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(status = item.status)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(text = item.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.status == HealthStatus.IMPROVABLE && item.safeToAutoApply) {
                TextButton(onClick = onApply, enabled = !isApplying) {
                    Text("Áp dụng")
                }
            } else if (item.status == HealthStatus.CONFLICT && item.manualActionRoute != null) {
                // Không tự động áp dụng được (cần người dùng tự quyết định/gọi mạng thật) —
                // nhưng vẫn cho 1 lối đi thay vì chỉ hiện cảnh báo không làm gì được.
                TextButton(onClick = { onNavigate(item.manualActionRoute) }) {
                    Text("Đi tới cài đặt")
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: HealthStatus) {
    when (status) {
        HealthStatus.OPTIMIZED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Đã tối ưu",
            tint = Color(0xFF4CAF50)
        )
        HealthStatus.IMPROVABLE -> Icon(
            Icons.Filled.Info,
            contentDescription = "Có thể cải thiện",
            tint = MaterialTheme.colorScheme.primary
        )
        HealthStatus.CONFLICT -> Icon(
            Icons.Filled.Warning,
            contentDescription = "Cần bạn quyết định",
            tint = Color(0xFFFFA000)
        )
        HealthStatus.UNSUPPORTED -> {
            // Không hiển thị (đã return sớm ở HealthItemRow) — nhánh này giữ để when
            // exhaustive, không có ý nghĩa runtime.
        }
    }
}
