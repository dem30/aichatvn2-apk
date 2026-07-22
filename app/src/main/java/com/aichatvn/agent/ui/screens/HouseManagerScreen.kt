package com.aichatvn.agent.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.HouseMood
import com.aichatvn.agent.data.model.displayName
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.data.model.TuyaDeviceEntity
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

    // Đọc trạng thái các chính sách và chế độ từ ViewModel
    val isAway = situation?.ownerPresent == false
    val isSilentNightEnabled by viewModel.isSilentNightPolicyEnabled.collectAsState()
    val isVacationSafetyEnabled by viewModel.isVacationSafetyPolicyEnabled.collectAsState()
    val lastLearningRun by viewModel.lastLearningRunTime.collectAsState()
    // ✅ MỚI: Khung giờ "Đang ngủ" chủ nhà tự chỉnh — thay cho hardcode cứng 22h-6h trước đây.
    val sleepStartHour by viewModel.sleepStartHour.collectAsState()
    val sleepEndHour by viewModel.sleepEndHour.collectAsState()

    // ✅ MỚI: Ánh xạ thiết bị Quản gia + danh sách thiết bị/camera thật để hiển thị picker
    // ⚠️ ĐÃ XÓA: protectLightDevice/protectSirenDevice/protectCameraIds — chỉ phục vụ Bảng cấu
    // hình Thiết bị Răn đe + nút Panic độc lập cũ, cả hai đã bị gỡ (xem ghi chú bên dưới).
    val availableTuyaDevices by viewModel.availableTuyaDevices.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    // ✅ MỚI: Danh sách thớt chat khách hàng thật để nạp cho picker chatSession
    val availableChatSessions by viewModel.availableChatSessions.collectAsState()

    // ✅ MỚI: showActionSheet cấp toàn màn hình cũ đã được thay bằng ModalBottomSheet
    // theo-nhóm bên trong MultiWorkflowPlannerSection (mỗi nhóm tự quản lý bước riêng).
    val alertActionPlugins = viewModel.alertActionPlugins
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val themeColorAndIcon = getThemeForMood(situation?.currentMood ?: HouseMood.NORMAL)
    val animatedHeaderColor by animateColorAsState(targetValue = themeColorAndIcon.headerColor)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản Gia AI — Điều Hành", fontWeight = FontWeight.Bold) },
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
            // 1. Thẻ Chế độ / Mood Header Card
            item {
                MoodHeaderCard(
                    mood = situation?.currentMood ?: HouseMood.NORMAL,
                    summary = situation?.summary ?: "Đang quy nạp trạng thái...",
                    theme = themeColorAndIcon
                )
            }

            // 2. Chế độ Vắng nhà (Vacation Mode Toggle) — điều khiển hai chiều
            item {
                VacationModeCard(
                    isAway = isAway,
                    onToggle = { viewModel.setAwayMode(it) }
                )
            }

            // 3. Bảng điều khiển Chính sách (Dynamic Policy Controls)
            item {
                PolicyControlCard(
                    isSilentNightEnabled = isSilentNightEnabled,
                    isVacationSafetyEnabled = isVacationSafetyEnabled,
                    onToggleSilentNight = { viewModel.togglePolicy("silent_night", it) },
                    onToggleVacationSafety = { viewModel.togglePolicy("vacation_safety", it) }
                )
            }

            // 🧠 MỚI: Bảng cấu hình khung giờ ngủ — trước đây "isNightTime()" bị code cứng
            // 22h-6h trong HouseManagerSkillImpl.kt, chủ nhà không xem/sửa được. Nay chủ nhà
            // tự chọn giờ bắt đầu/kết thúc bằng nút bấm, lưu qua AppConfig và áp dụng ngay
            // cho toàn bộ logic Mood tự động (SLEEPING/NIGHT) + chính sách ban đêm yên tĩnh.
            item {
                SleepScheduleCard(
                    startHour = sleepStartHour,
                    endHour = sleepEndHour,
                    onSave = { start, end -> viewModel.saveSleepSchedule(start, end) }
                )
            }

            // 🧠 MỚI: Bảng điều hành đa Nhóm kịch bản (Workflow Groups) — chủ nhà tự tạo không
            // giới hạn số nhóm, mỗi nhóm có ngòi nổ riêng (camera/tuya/chat) và chuỗi bước tự do.
            item {
                var activeGroupIdForAddingStep by remember { mutableStateOf<String?>(null) }

                if (activeGroupIdForAddingStep != null) {
                    ModalBottomSheet(
                        onDismissRequest = { activeGroupIdForAddingStep = null },
                        sheetState = sheetState
                    ) {
                        AlertActionFormSheet(
                            plugins = alertActionPlugins,
                            tuyaDevices = availableTuyaDevices,
                            activeCameras = availableCameras,
                            activeChatSessions = availableChatSessions,
                            onSave = { cfg ->
                                viewModel.addStepToGroup(activeGroupIdForAddingStep!!, cfg)
                                activeGroupIdForAddingStep = null
                            },
                            onCancel = { activeGroupIdForAddingStep = null }
                        )
                    }
                }

                MultiWorkflowPlannerSection(
                    viewModel = viewModel,
                    onAddStepClick = { groupId -> activeGroupIdForAddingStep = groupId }
                )
            }

            // ⚠️ ĐÃ XÓA: Bảng cấu hình ánh xạ thiết bị răn đe (DeviceMappingConfigCard) — cấu hình
            // dự phòng này chỉ phục vụ nút Panic độc lập cũ, nay không còn ai đọc tới nữa.

            // 4. Kích hoạt Học máy thủ công (Manual Habit Mining)
            item {
                HabitMiningCard(
                    lastRun = lastLearningRun,
                    onMineNow = { viewModel.mineHabitsNow() }
                )
            }

            // 5. Thẻ Chỉ số Bản sao số (World State Index Overview)
            item {
                SituationOverviewGrid(situation = situation)
            }

            // ⚠️ ĐÃ XÓA: Thẻ "Kích Hoạt Bảo Vệ Liên Hoàn Khẩn Cấp" (PanicTriggerCard) độc lập —
            // đứng tách biệt khỏi các Nhóm kịch bản chủ nhà thực sự quản lý bên dưới, dễ gây hiểu
            // lầm là 2 hệ thống khác nhau. Nút "Chạy thủ công" giờ nằm ngay trên từng
            // WorkflowGroupCard, chạy đúng nhóm đó — xem MultiWorkflowPlannerSection bên dưới.

            // Tiêu đề phân khu Kịch bản nền (Planner Timeline)
            item {
                Text(
                    text = "Kịch bản liên hoàn đang vận hành",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 7. Danh sách các kịch bản Planner đang chạy dưới nền
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
// UI COMPONENTS ĐIỀU KHIỂN & TRỰC QUAN HÓA
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun VacationModeCard(
    isAway: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAway) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chế độ Vắng nhà (Vacation Mode)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (isAway) "Hệ thống đang siết chặt an ninh tối đa." else "Chủ nhà đang có mặt bình thường.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Switch(
                checked = isAway,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun PolicyControlCard(
    isSilentNightEnabled: Boolean,
    isVacationSafetyEnabled: Boolean,
    onToggleSilentNight: (Boolean) -> Unit,
    onToggleVacationSafety: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Kiểm soát Chính sách Ngôi nhà (Policy Engine)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Dòng 1: silent_night
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Chính sách ban đêm yên tĩnh", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("Chặn tivi, còi báo động ồn ào khi đi ngủ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = isSilentNightEnabled, onCheckedChange = onToggleSilentNight)
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Dòng 2: vacation_safety
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Chính sách an toàn vắng nhà", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("Chặn bật máy bơm, bình nóng lạnh khi vắng nhà", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = isVacationSafetyEnabled, onCheckedChange = onToggleVacationSafety)
            }
        }
    }
}

// 🧠 MỚI: Cho phép chủ nhà tự chỉnh khung giờ "Đang ngủ / Ban đêm" thay vì code cứng 22h-6h.
// Giờ bắt đầu > giờ kết thúc nghĩa là khung giờ vắt qua nửa đêm (vd 22h -> 6h sáng hôm sau),
// giống cách hiểu tự nhiên của người dùng — không cần giải thích kỹ thuật gì thêm.
@Composable
fun SleepScheduleCard(
    startHour: Int,
    endHour: Int,
    onSave: (start: Int, end: Int) -> Unit
) {
    var draftStart by remember(startHour) { mutableStateOf(startHour) }
    var draftEnd by remember(endHour) { mutableStateOf(endHour) }
    val hasChanges = draftStart != startHour || draftEnd != endHour

    fun formatHour(h: Int) = "%02d:00".format(h)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Khung giờ ngủ của gia đình",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Quản gia dùng khung giờ này để tự chuyển sang Chế độ Đang ngủ / Ban đêm và áp dụng Chính sách ban đêm yên tĩnh ở trên.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HourStepper(
                    label = "Bắt đầu ngủ",
                    hour = draftStart,
                    onChange = { draftStart = it }
                )
                HourStepper(
                    label = "Thức dậy",
                    hour = draftEnd,
                    onChange = { draftEnd = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Đang áp dụng: từ ${formatHour(draftStart)} đến ${formatHour(draftEnd)} hằng ngày",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (hasChanges) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onSave(draftStart, draftEnd) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lưu khung giờ ngủ", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HourStepper(
    label: String,
    hour: Int,
    onChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ⚠️ Dùng nút chữ thay vì Icons.Default.Remove: icon này không có trong bộ icon
            // lõi (core), chỉ có trong material-icons-extended -> gây lỗi unresolved reference
            // nếu project chưa thêm dependency đó (giống trường hợp Icons.Default.School trước đây).
            IconButton(onClick = { onChange(((hour - 1) + 24) % 24) }) {
                Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "%02d:00".format(hour),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = { onChange((hour + 1) % 24) }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Tăng giờ")
            }
        }
    }
}

// ⚠️ ĐÃ XÓA: CustomPlannerCard (kịch bản răn đe tự do kiểu cũ, thao tác trên 1 danh sách
// "actions" phẳng) không còn được gọi ở bất kỳ đâu trên màn hình nữa — đã bị thay thế hoàn
// toàn bởi MultiWorkflowPlannerSection/WorkflowGroupCard (kiến trúc đa Nhóm kịch bản). Đây là
// code chết (dead code), đồng thời cũng mắc lỗi hiển thị "Không xác định"/"null" y hệt bug đã
// sửa bên dưới — xóa hẳn thay vì vá lỗi cho code không bao giờ chạy.

// ⚠️ ĐÃ XÓA: DeviceMappingConfigCard + DevicePickerDropdown — cấu hình thiết bị dự phòng
// (đèn/còi/camera) chỉ phục vụ nút Panic độc lập cũ; nút đó đã bị gỡ, chọn thiết bị thật giờ
// làm trực tiếp trong bước kịch bản qua VisualTriggerBuilderDialog/DeviceSelectorDropdown.

@Composable
fun HabitMiningCard(
    lastRun: String,
    onMineNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tự học Thói quen (Learning)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Chạy gần nhất: $lastRun",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Button(
                onClick = onMineNow,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ✅ ĐÃ SỬA: Icons.Default.School không có trong bộ icon lõi (core),
                // chỉ có trong material-icons-extended -> gây lỗi unresolved reference
                // nếu project chưa thêm dependency đó. Thay bằng icon lõi PlayArrow.
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Tự học")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Học ngay", fontSize = 12.sp)
            }
        }
    }
}

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
                contentDescription = mood.displayName(),
                tint = theme.contentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CHẾ ĐỘ: ${mood.displayName()}",
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

// ⚠️ ĐÃ XÓA: PanicTriggerCard — nút Panic độc lập cũ. Xem WorkflowGroupCard bên dưới cho nút
// "Chạy thủ công" mới, gắn liền với từng Nhóm kịch bản thay vì đứng tách biệt.

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

            // ✅ Bảo vệ phép chia 0 -> phòng chống crash NaN khi totalSteps == 0
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
// CƠ CHẾ QUY ĐỊNH TÔNG MÀU ĐỘNG (THEME MAPPER)
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

// ─────────────────────────────────────────────────────────────────────────
// TRÌNH SOẠN THẢO ĐA NHÓM KỊCH BẢN (VISUAL MULTI-WORKFLOW EDITOR)
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun MultiWorkflowPlannerSection(
    viewModel: HouseManagerViewModel,
    onAddStepClick: (String) -> Unit // Truyền groupId đang được chọn để thêm bước
) {
    val groups by viewModel.workflowGroups.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val availableDevices by viewModel.availableTuyaDevices.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        VisualTriggerBuilderDialog(
            availableCameras = availableCameras,
            availableDevices = availableDevices,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, source, entity, value ->
                viewModel.createWorkflowGroup(name, source, entity, value)
                showCreateDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Danh sách kịch bản tự động",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { showCreateDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Tạo")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Tạo kịch bản mới", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chưa có kịch bản nào được thiết lập. Hãy bấm nút phía trên để tạo kịch bản tự động đầu tiên.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            groups.forEach { group ->
                WorkflowGroupCard(
                    group = group,
                    availableCameras = availableCameras,
                    availableDevices = availableDevices,
                    onToggle = { enabled -> viewModel.toggleWorkflowGroup(group.id, enabled) },
                    onDelete = { viewModel.deleteWorkflowGroup(group.id) },
                    onAddStep = { onAddStepClick(group.id) },
                    onRemoveStep = { index -> viewModel.removeStepFromGroup(group.id, index) },
                    onRunNow = { viewModel.triggerWorkflowGroupManually(group.id) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun WorkflowGroupCard(
    group: com.aichatvn.agent.skills.WorkflowGroup,
    availableCameras: List<CameraConfigEntity>,
    availableDevices: List<TuyaDeviceEntity>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onAddStep: () -> Unit,
    onRemoveStep: (Int) -> Unit,
    onRunNow: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Tự động dịch chuỗi trigger kỹ thuật dưới nền thành câu tiếng Việt thân thiện
    val friendlyTriggerText = remember(group.triggerSource, availableCameras, availableDevices) {
        translateTriggerToFriendlyVietnamese(group.triggerSource, availableCameras, availableDevices)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = friendlyTriggerText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = group.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.scale(0.8f)
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Xóa nhóm",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ✅ MỚI: Nút "Chạy thủ công" thay cho nút Panic độc lập cũ — chạy đúng nhóm kịch
            // bản này ngay lập tức, bất kể ngòi nổ tự động đã xảy ra hay chưa. Chỉ hiện khi nhóm
            // có ít nhất 1 bước, tránh chạy một kế hoạch rỗng.
            if (group.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRunNow,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = "Chạy thủ công", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chạy thủ công ngay", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Xem chi tiết",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isExpanded) "Thu gọn danh sách bước" else "Xem chi tiết các bước (${group.steps.size} bước)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                if (group.steps.isEmpty()) {
                    Text(
                        text = "ℹ️ Kịch bản này chưa có bước hành động nào. Hãy bấm 'Thêm bước' bên dưới để xây dựng kịch bản.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    group.steps.forEachIndexed { index, step ->
                        val stepText = when {
                            step.pluginId == "house_manager" && step.action == "delay" ->
                                "⏳ Trì hoãn ${step.params["delayMs"] ?: "0"} mili-giây"
                            step.pluginId == "house_manager" && step.action == "check_precondition" ->
                                "🔒 Kiểm duyệt điều kiện thực tế: ${describeCheckPrecondition(step, availableCameras, availableDevices)}"
                            else ->
                                "⚡ Gọi hành động ${step.pluginId}.${step.action}"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Bước ${index + 1}: $stepText", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveStep(index) }, modifier = Modifier.size(24.dp)) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Xóa bước", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onAddStep,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Thêm bước", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thêm bước hành động", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * ✅ BỘ CHUYỂN ĐỔI NGÔN NGỮ TỰ NHIÊN: Dịch trigger thô thành tiếng Việt
 */
fun translateTriggerToFriendlyVietnamese(
    triggerSource: String,
    availableCameras: List<CameraConfigEntity>,
    availableDevices: List<TuyaDeviceEntity>
): String {
    val condition = com.aichatvn.agent.utils.WorldStateHelper.parseCondition(triggerSource)
        ?: return "Khi kích hoạt thủ công kịch bản"

    val friendlyName = when (condition.source) {
        "camera" -> {
            val cam = availableCameras.find { it.id.trim() == condition.sourceId.trim() }
            "Camera ${cam?.customername?.ifBlank { condition.sourceId } ?: condition.sourceId}"
        }
        "tuya" -> {
            val dev = availableDevices.find { it.id.trim() == condition.sourceId.trim() }
            "Thiết bị ${dev?.name?.ifBlank { condition.sourceId } ?: condition.sourceId}"
        }
        "chat" -> {
            if (condition.sourceId == "*") "Mọi kênh hỗ trợ khách hàng" else "Kênh chat ${condition.sourceId}"
        }
        else -> condition.sourceId
    }

    val actionText = when (condition.attrKey) {
        "state" -> when (condition.expected) {
            "suspicious" -> "phát hiện có dấu hiệu nghi vấn (xâm nhập/có người)"
            "true" -> "được BẬT lên"
            "false" -> "bị TẮT đi"
            else -> "thay đổi trạng thái sang '${condition.expected}'"
        }
        "urgency" -> {
            if (condition.expected == "high") "phát hiện tin nhắn chứa nội dung KHẨN CẤP" else "nhận tin nhắn thường"
        }
        else -> "thay đổi giá trị '${condition.attrKey}' thành '${condition.expected}'"
    }

    return "🔥 Ngòi nổ: Khi $friendlyName $actionText"
}

// ✅ ĐÃ SỬA: Lệch pha hiển thị (UI) vs. thực thi (nền) cho bước check_precondition. Khi bạn tạo
// bước này bằng bộ chọn Dropdown trực quan, dữ liệu được lưu dưới dạng tham số RỜI RẠC
// (source/camera/device/chatSession/attribute/expected) — HouseManagerSkillImpl.kt khi CHẠY vẫn
// tự ghép đúng các tham số này thành điều kiện và kiểm duyệt chính xác. Nhưng UI cũ chỉ tìm mỗi
// khóa "precondition" (vốn để trống với bước tạo từ dropdown) nên luôn hiện "Không xác định" dù
// kịch bản chạy đúng dưới nền. Hàm này ghép lại y hệt cách HouseManagerSkillImpl ghép, rồi tái sử
// dụng translateTriggerToFriendlyVietnamese để dịch sang câu tiếng Việt — đảm bảo UI hiển thị
// đúng 100% với những gì thực sự được kiểm duyệt.
fun describeCheckPrecondition(
    step: AlertActionConfig,
    availableCameras: List<CameraConfigEntity>,
    availableDevices: List<TuyaDeviceEntity>
): String {
    val rawPrecondition = step.params["precondition"]
    val compiled = if (!rawPrecondition.isNullOrBlank()) {
        rawPrecondition
    } else {
        val source = step.params["source"] ?: ""
        val attr = step.params["attribute"] ?: "state"
        val expected = step.params["expected"] ?: ""
        val sourceId = when (source) {
            "tuya" -> step.params["device"] ?: ""
            "camera" -> step.params["camera"] ?: ""
            "chat" -> step.params["chatSession"] ?: ""
            else -> ""
        }
        if (source.isNotEmpty() && sourceId.isNotEmpty()) "$source.$sourceId.$attr=$expected" else null
    }

    if (compiled.isNullOrBlank()) return "Chưa cấu hình điều kiện"

    // Câu trả về của translateTriggerToFriendlyVietnamese nói theo giọng "ngòi nổ sự kiện"
    // ("🔥 Ngòi nổ: Khi ... xảy ra"), phù hợp để diễn giải một điều kiện kiểm duyệt trạng thái
    // hiện tại — chỉ bỏ tiền tố "🔥 Ngòi nổ: " đi cho khớp ngữ cảnh "đang kiểm tra", không phải
    // "đang chờ xảy ra".
    return translateTriggerToFriendlyVietnamese(compiled, availableCameras, availableDevices)
        .removePrefix("🔥 Ngòi nổ: ")
}

/**
 * Hộp thoại dựng kịch bản thả chọn — người dùng không cần gõ bất kỳ chuỗi kỹ thuật nào.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualTriggerBuilderDialog(
    availableCameras: List<CameraConfigEntity>,
    availableDevices: List<TuyaDeviceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, source: String, entityId: String, value: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("camera") } // camera | tuya | chat
    var selectedEntityId by remember { mutableStateOf("") }
    var selectedExpectedValue by remember { mutableStateOf("suspicious") }

    LaunchedEffect(selectedSource) {
        selectedEntityId = when (selectedSource) {
            "camera" -> availableCameras.firstOrNull()?.id ?: ""
            "tuya" -> availableDevices.firstOrNull()?.id ?: ""
            "chat" -> "*"
            else -> ""
        }
        selectedExpectedValue = when (selectedSource) {
            "camera" -> "suspicious"
            "tuya" -> "true"
            "chat" -> "high"
            else -> ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo kịch bản tự động mới", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Tên gợi nhớ kịch bản") },
                    placeholder = { Text("Ví dụ: Báo động trộm sân sau") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Bước 1: Chọn nguồn kích hoạt kịch bản", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sources = listOf("camera" to "📷 Camera", "tuya" to "🔌 Thiết bị Tuya", "chat" to "💬 Khách nhắn")
                    sources.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedSource == key,
                            onClick = { selectedSource = key },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }



                

                // Tìm đoạn code này trong VisualTriggerBuilderDialog ở HouseManagerScreen.kt:
Text("Bước 2: Chọn thiết bị áp dụng", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
when (selectedSource) {
    "camera" -> {
        if (availableCameras.isEmpty()) {
            Text("Chưa lắp camera nào.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } else {
            // Thay thế đoạn DeviceSelectorDropdown bên dưới bằng bản cập nhật hiển thị chi tiết:
            DeviceSelectorDropdown(
                label = "Chọn camera giám sát",
                list = availableCameras.map { camera ->
                    val displayLabel = buildString {
                        append(camera.customername.ifBlank { "Chưa đặt tên" })
                        
                        append(" (${camera.id})") // Đính kèm ID camera thật ở cuối để bảo đảm phân biệt được
                    }
                    camera.id to displayLabel
                },
                selectedId = selectedEntityId,
                onSelected = { selectedEntityId = it }
            )
        }
    }
    // Các nhánh "tuya" và "chat" bên dưới giữ nguyên...





                    
                    "tuya" -> {
                        if (availableDevices.isEmpty()) {
                            Text("Chưa đồng bộ thiết bị Tuya nào.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } else {
                            DeviceSelectorDropdown(
                                label = "Chọn thiết bị Tuya",
                                list = availableDevices.map { it.id to it.name },
                                selectedId = selectedEntityId,
                                onSelected = { selectedEntityId = it }
                            )
                        }
                    }
                    "chat" -> {
                        Text("Áp dụng tự động cho mọi kênh nhắn tin (Facebook/Telegram/Web).", fontSize = 12.sp)
                    }
                }

                Text("Bước 3: Chọn điều kiện kích hoạt", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val conditions = when (selectedSource) {
                        "camera" -> listOf("suspicious" to "Phát hiện có trộm/nghi vấn")
                        "tuya" -> listOf("true" to "Bị BẬT công tắc", "false" to "Bị TẮT công tắc")
                        "chat" -> listOf("high" to "Khách gửi tin nhắn khẩn cấp")
                        else -> emptyList()
                    }
                    conditions.forEach { (value, label) ->
                        FilterChip(
                            selected = selectedExpectedValue == value,
                            onClick = { selectedExpectedValue = value },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank() && selectedEntityId.isNotBlank()) {
                        onConfirm(groupName.trim(), selectedSource, selectedEntityId, selectedExpectedValue)
                    }
                },
                enabled = groupName.isNotBlank() && selectedEntityId.isNotBlank()
            ) {
                Text("Xác nhận tạo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy bỏ")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectorDropdown(
    label: String,
    list: List<Pair<String, String>>, // id to friendlyName
    selectedId: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = list.find { it.first == selectedId }?.second ?: selectedId

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            list.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    }
                )
            }
        }
    }
}