package com.aichatvn.agent.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichatvn.agent.data.model.HouseMood
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.skills.PlanStatus
import com.aichatvn.agent.ui.viewmodels.HouseManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseManagerScreen(
    viewModel: HouseManagerViewModel,
    modifier: Modifier = Modifier
) {
    val situation by viewModel.situation.collectAsState()
    val activePlans by viewModel.activePlans.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    // 1. Phối màu giao diện thích ứng theo trạng thái Mood thực tế của Ngôi nhà
    val themeColorAndIcon = getThemeForMood(situation?.currentMood ?: HouseMood.NORMAL)
    val animatedHeaderColor by animateColorAsState(targetValue = themeColorAndIcon.headerColor)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản Gia AI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Tải lại",
                            tint = if (isRefreshing) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = animatedHeaderColor,
                    titleContentColor = themeColorAndIcon.contentColor
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Thẻ Tâm trạng & Chế độ vận hành hiện tại
            item {
                MoodHeaderCard(
                    mood = situation?.currentMood ?: HouseMood.NORMAL,
                    summary = situation?.summary ?: "Đang quy nạp trạng thái...",
                    theme = themeColorAndIcon
                )
            }

            // Thẻ Chỉ số Bản sao số (World State Index)
            item {
                SituationOverviewGrid(situation = situation)
            }

            // Thẻ Kịch bản khẩn cấp (Panic Action Button)
            item {
                PanicTriggerCard(onPanic = { viewModel.triggerPanicSequence("cam_01") })
            }

            // Tiêu đề phân khu Kịch bản nền (Planner)
            item {
                Text(
                    text = "Kịch bản liên hoàn đang vận hành",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Danh sách các Plan đang chạy thực tế
            if (activePlans.isEmpty()) {
                item {
                    EmptyPlansCard()
                }
            } else {
                items(activePlans, key = { it.planId }) { plan ->
                    PlanStatusCard(plan = plan)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// UI COMPONENTS HỖ TRỢ
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun MoodHeaderCard(
    mood: HouseMood,
    summary: String,
    theme: MoodTheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.headerColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = theme.icon,
                contentDescription = mood.name,
                tint = theme.contentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CHẾ ĐỘ: ${mood.name}",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = theme.contentColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.contentColor.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SituationOverviewGrid(situation: HouseSituation?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Chỉ số Bản sao số (World State)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoIndicator(
                    label = "Thiết bị bật",
                    value = "${situation?.activeDevicesCount ?: 0}",
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f)
                )
                InfoIndicator(
                    label = "Tin chưa đọc",
                    value = "${situation?.pendingChatsCount ?: 0}",
                    icon = Icons.Default.Email,
                    modifier = Modifier.weight(1f)
                )
                InfoIndicator(
                    label = "Bất thường",
                    value = "${situation?.suspiciousObjectsCount ?: 0}",
                    icon = Icons.Default.Warning,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun InfoIndicator(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PanicTriggerCard(onPanic: () -> Unit) {
    Button(
        onClick = onPanic,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = "Báo động")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Kích Hoạt Bảo Vệ Liên Hoàn Khẩn Cấp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun PlanStatusCard(plan: PlanStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plan.goalName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = plan.status,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (plan.status) {
                        "RUNNING" -> MaterialTheme.colorScheme.primary
                        "COMPLETED" -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Thanh tiến trình chạy các bước
            // ✅ ĐÃ SỬA: bảo vệ chia 0 -> tránh NaN khi totalSteps == 0 (coerceIn không chặn được NaN)
            LinearProgressIndicator(
                progress = {
                    if (plan.totalSteps > 0)
                        (plan.currentStepIndex.toFloat() / plan.totalSteps.toFloat()).coerceIn(0f, 1f)
                    else 0f
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Bước ${plan.currentStepIndex}/${plan.totalSteps}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Nhật ký hành động Planner:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Hiện 3 dòng log chạy mới nhất
            plan.logs.takeLast(3).forEach { log ->
                Text(
                    text = "• $log",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun EmptyPlansCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = "Không có kịch bản",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Không có kịch bản nền nào đang chạy.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// CƠ CHẾ QUY QUY ĐỊNH TÔNG MÀU ĐỘNG (THEME MAPPER)
// ─────────────────────────────────────────────────────────────────────────

data class MoodTheme(val headerColor: Color, val contentColor: Color, val icon: ImageVector)

@Composable
fun getThemeForMood(mood: HouseMood): MoodTheme {
    return when (mood) {
        HouseMood.ALERT -> MoodTheme(
            headerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Default.Warning
        )
        HouseMood.SLEEPING -> MoodTheme(
            headerColor = Color(0xFF3F51B5), // Tím than huyền bí
            contentColor = Color.White,
            icon = Icons.Default.Star
        )
        HouseMood.BUSY -> MoodTheme(
            headerColor = Color(0xFFFF9800), // Cam nhộn nhịp
            contentColor = Color.White,
            icon = Icons.Default.Notifications
        )
        HouseMood.NIGHT -> MoodTheme(
            headerColor = Color(0xFF1A237E), // Indigo đậm
            contentColor = Color.White,
            icon = Icons.Default.Star
        )
        HouseMood.VACATION -> MoodTheme(
            headerColor = Color(0xFF009688), // Teal tươi mát
            contentColor = Color.White,
            icon = Icons.Default.Home
        )
        HouseMood.QUIET -> MoodTheme(
            headerColor = Color(0xFF4CAF50), // Xanh thanh tịnh
            contentColor = Color.White,
            icon = Icons.Default.Face
        )
        HouseMood.NORMAL -> MoodTheme(
            headerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = Icons.Default.Home
        )
    }
}
