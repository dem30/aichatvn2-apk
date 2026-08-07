// File: com/aichatvn/agent/ui/screens/HouseManagerScreen.kt

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
import androidx.compose.ui.platform.LocalLifecycleOwner // ✅ MỚI (UX): refresh khi quay lại màn hình
import androidx.compose.ui.platform.LocalClipboardManager // 🌟 MỚI: sao chép ngữ cảnh AI vào clipboard
import androidx.compose.ui.text.AnnotatedString // 🌟 MỚI: dùng cho LocalClipboardManager.setText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.aichatvn.agent.ui.components.SmartActionFormSheet // 🌟 MỚI: Dùng SmartActionFormSheet chuẩn
import com.aichatvn.agent.ui.viewmodels.HouseManagerViewModel
import androidx.navigation.NavController // 🌟 MỚI: cho điều hướng khi bấm ô chỉ số
import androidx.compose.ui.hapticfeedback.HapticFeedbackType // 🌟 MỚI
import androidx.compose.ui.platform.LocalHapticFeedback // 🌟 MỚI
import androidx.compose.ui.semantics.Role // 🌟 MỚI
import com.aichatvn.agent.ui.navigation.Screen // 🌟 MỚI: dùng Screen.INBOX_ROUTE thay vì gõ tay chuỗi "inbox"
import kotlinx.coroutines.delay // 🌟 MỚI: debounce validate JSON trong ImportWorkflowJsonDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseManagerScreen(
    viewModel: HouseManagerViewModel,
    navController: NavController, // 🌟 MỚI: để SituationOverviewGrid điều hướng khi bấm chỉ số
    modifier: Modifier = Modifier
) {
    val situation by viewModel.situation.collectAsState()
    val activePlans by viewModel.activePlans.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val isAway = situation?.ownerPresent == false
    val isSilentNightEnabled by viewModel.isSilentNightPolicyEnabled.collectAsState()
    val isVacationSafetyEnabled by viewModel.isVacationSafetyPolicyEnabled.collectAsState()
    val lastLearningRun by viewModel.lastLearningRunTime.collectAsState()
    val sleepStartHour by viewModel.sleepStartHour.collectAsState()
    val sleepEndHour by viewModel.sleepEndHour.collectAsState()

    // ✅ MỚI (Sửa lỗi chặn theo alias): danh sách thiết bị thật + 2 tập ID đã gắn thẻ, để
    // chủ nhà tự chọn thiết bị nào là "phụ tải lớn" / "gây ồn" — độc lập với tên/alias.
    val tuyaDevicesForTagging by viewModel.availableTuyaDevices.collectAsState()
    val heavyLoadDeviceIds by viewModel.heavyLoadDeviceIds.collectAsState()
    val noiseDeviceIds by viewModel.noiseDeviceIds.collectAsState()

    val alertActionPlugins = viewModel.alertActionPlugins
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val themeColorAndIcon = getThemeForMood(situation?.currentMood ?: HouseMood.NORMAL)
    val animatedHeaderColor by animateColorAsState(targetValue = themeColorAndIcon.headerColor)

    // ✅ MỚI (UX): trước đây chỉ refreshAll() 1 lần lúc ViewModel khởi tạo — quay lại màn hình
    // này sau khi đọc tin ở Inbox không tự cập nhật lại "Tin chưa đọc"/Mood, khiến chỉ số đứng
    // yên dù thực tế đã đọc hết (chỉ hết khi bấm nút Reload thủ công trên TopAppBar). Giờ tự
    // refresh mỗi khi màn hình quay lại foreground (ON_RESUME) — đúng lúc người dùng vừa đọc
    // xong tin nhắn/kịch bản ở màn con rồi bấm Back trở về đây.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

            // 2. Chế độ Vắng nhà (Vacation Mode Toggle)
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

            // 3b. ✅ MỚI: Gắn thẻ thiết bị nào là "phụ tải lớn" / "gây ồn" theo ID thật —
            // 2 chính sách ở trên chặn dựa vào đây, KHÔNG còn đoán chữ trong tên/alias nữa.
            item {
                DeviceLoadTagCard(
                    devices = tuyaDevicesForTagging,
                    heavyLoadDeviceIds = heavyLoadDeviceIds,
                    noiseDeviceIds = noiseDeviceIds,
                    onToggleHeavyLoad = { deviceId, tagged -> viewModel.toggleHeavyLoadTag(deviceId, tagged) },
                    onToggleNoise = { deviceId, tagged -> viewModel.toggleNoiseTag(deviceId, tagged) }
                )
            }

            // 4. Bảng cấu hình khung giờ ngủ
            item {
                SleepScheduleCard(
                    startHour = sleepStartHour,
                    endHour = sleepEndHour,
                    onSave = { start, end -> viewModel.saveSleepSchedule(start, end) }
                )
            }

            // 5. Bảng điều hành đa Nhóm kịch bản (Workflow Groups)
            item {
                var activeGroupIdForAddingStep by remember { mutableStateOf<String?>(null) }

                if (activeGroupIdForAddingStep != null) {
                    ModalBottomSheet(
                        onDismissRequest = { activeGroupIdForAddingStep = null },
                        sheetState = sheetState
                    ) {
                        // 🌟 DÙNG SMART_ACTION_FORM_SHEET TẬP TRUNG DÙNG CHUNG TOÀN APP
                        SmartActionFormSheet(
                            plugins = alertActionPlugins,
                            optionRegistry = viewModel.optionRegistry,
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

            // 6. Kích hoạt Học máy thủ công
            item {
                HabitMiningCard(
                    lastRun = lastLearningRun,
                    onMineNow = { viewModel.mineHabitsNow() }
                )
            }

            // 7. Thẻ Chỉ số Bản sao số (World State Index Overview)
            item {
                SituationOverviewGrid(situation = situation, navController = navController)
            }

            // Tiêu đề phân khu Kịch bản nền
            item {
                Text(
                    text = "Kịch bản liên hoàn đang vận hành",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 8. Danh sách các kịch bản Planner đang chạy dưới nền
            if (activePlans.isEmpty()) {
                item {
                    EmptyPlansCard()
                }
            } else {
                items(activePlans, key = { it.planId }) { plan ->
                    PlanStatusCard(plan = plan, onDelete = { viewModel.removePlan(plan.planId) })
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
fun DeviceLoadTagCard(
    devices: List<TuyaDeviceEntity>,
    heavyLoadDeviceIds: Set<String>,
    noiseDeviceIds: Set<String>,
    onToggleHeavyLoad: (deviceId: String, tagged: Boolean) -> Unit,
    onToggleNoise: (deviceId: String, tagged: Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gắn thẻ thiết bị cho Chính sách",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Chọn theo thiết bị thật — không phụ thuộc tên gọi/alias đã huấn luyện, vì tên có thể đổi bất cứ lúc nào.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Xem danh sách thiết bị"
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Text(
                        text = "ℹ️ Chưa có thiết bị Tuya nào. Vào quét thiết bị trước.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                } else {
                    devices.forEach { dev ->
                        val isHeavyLoad = dev.id in heavyLoadDeviceIds
                        val isNoise = dev.id in noiseDeviceIds
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = dev.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isHeavyLoad,
                                    onCheckedChange = { checked -> onToggleHeavyLoad(dev.id, checked) },
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "⚡ Phụ tải lớn",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp, end = 16.dp)
                                )
                                Checkbox(
                                    checked = isNoise,
                                    onCheckedChange = { checked -> onToggleNoise(dev.id, checked) },
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "🔊 Gây ồn",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                        Divider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
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
fun SituationOverviewGrid(situation: HouseSituation?, navController: NavController) {
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
                // 🌟 SỬA: "Thiết bị bật" giờ dẫn thẳng tới màn Thiết bị Tuya — Screen.Tuya
                // là màn con (không nằm trong danh sách tab Bottom Nav ở AppNavigator), nên
                // navigate() thẳng an toàn, không đụng chạm cơ chế saveState/restoreState
                // của các tab chính như "dashboard".
                InfoIndicator(
                    label = "Thiết bị bật",
                    value = "${situation?.activeDevicesCount ?: 0}",
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        navController.navigate(Screen.Tuya.route) { launchSingleTop = true }
                    }
                )
                // 🌟 MỚI: Bấm để mở ngay Hộp thư đa kênh (Inbox) — đây là màn con, không phải
                // tab chính, nên navigate() thẳng an toàn, không đụng tới back stack của tab nào.
                InfoIndicator(
                    label = "Tin chưa đọc",
                    value = "${situation?.pendingChatsCount ?: 0}",
                    icon = Icons.Default.Email,
                    modifier = Modifier.weight(1f),
                    isHighlighted = (situation?.pendingChatsCount ?: 0) > 0,
                    onClick = {
                        navController.navigate(Screen.INBOX_ROUTE) { launchSingleTop = true }
                    }
                )
                // 🌟 MỚI: Bấm để mở Lịch sử cảnh báo camera — cũng là màn con, an toàn tương tự.
                InfoIndicator(
                    label = "Bất thường",
                    value = "${situation?.suspiciousObjectsCount ?: 0}",
                    icon = Icons.Default.Warning,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        navController.navigate("alert_history") { launchSingleTop = true }
                    }
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
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, // 🌟 MỚI: null = ô tĩnh (không bấm được), như "Thiết bị bật"
    isHighlighted: Boolean = false // ✅ MỚI (UX): tô màu cảnh báo khi ô này đang là lý do gây chú ý (vd tin chưa đọc > 0)
) {
    val haptic = LocalHapticFeedback.current
    val backgroundColor = if (isHighlighted) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isHighlighted) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .then(
                if (onClick != null) {
                    // Modifier.clickable của Material3 đã tự có ripple mặc định, không cần
                    // code thêm gì cho hiệu ứng bấm.
                    Modifier.clickable(onClickLabel = label, role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                } else Modifier
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 10.sp, color = contentColor.copy(alpha = 0.7f))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = contentColor)
        }
    }
}

@Composable
fun PlanStatusCard(plan: PlanStatus, onDelete: () -> Unit) {
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
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
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
                // ✅ MỚI: Dọn thủ công kịch bản đã chạy xong (COMPLETED/CANCELLED) thay vì
                // phải chờ tự dọn sau 10 phút. Không cho xoá khi đang RUNNING để tránh chủ nhà
                // lỡ tay xoá mất theo dõi 1 kịch bản đang thi hành dở.
                if (plan.status != "RUNNING") {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Dọn kịch bản",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

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
            headerColor = Color(0xFF3F51B5),
            contentColor = Color.White,
            icon = Icons.Default.Star
        )
        HouseMood.BUSY -> MoodTheme(
            headerColor = Color(0xFFFF9800),
            contentColor = Color.White,
            icon = Icons.Default.Notifications
        )
        HouseMood.NIGHT -> MoodTheme(
            headerColor = Color(0xFF1A237E),
            contentColor = Color.White,
            icon = Icons.Default.Star
        )
        HouseMood.VACATION -> MoodTheme(
            headerColor = Color(0xFF009688),
            contentColor = Color.White,
            icon = Icons.Default.Home
        )
        HouseMood.QUIET -> MoodTheme(
            headerColor = Color(0xFF4CAF50),
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

@Composable
fun MultiWorkflowPlannerSection(
    viewModel: HouseManagerViewModel,
    onAddStepClick: (String) -> Unit
) {
    val groups by viewModel.workflowGroups.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val availableDevices by viewModel.availableTuyaDevices.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    // ✅ MỚI (Nhập kịch bản qua AI ngoài): điều khiển dialog dán JSON — tách biệt hoàn toàn với
    // dialog tạo thủ công ở trên, chỉ mở khi chủ nhà chủ động bấm nút "Nhập kịch bản (JSON)".
    var showImportDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

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

    if (showImportDialog) {
        ImportWorkflowJsonDialog(
            viewModel = viewModel,
            onDismiss = {
                showImportDialog = false
                viewModel.clearImportPreview()
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
        Spacer(modifier = Modifier.height(8.dp))
        // ✅ MỚI: Hàng nút phụ cho luồng "nhờ AI ngoài soạn kịch bản hộ" — dành cho chủ nhà thấy
        // VisualTriggerBuilderDialog quá phức tạp, có thể mô tả bằng lời cho 1 AI khác rồi dán
        // JSON AI trả về vào đây thay vì tự bấm từng bước.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(viewModel.buildAiContextForWorkflowCreation()))
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sao chép ngữ cảnh AI", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { showImportDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Nhập kịch bản (JSON)", fontSize = 11.sp)
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

// ✅ MỚI (Nhập kịch bản qua AI ngoài): Dialog dán JSON do AI ngoài sinh ra (dựa trên ngữ cảnh đã
// sao chép ở nút "Sao chép ngữ cảnh AI"). Validate MỖI LẦN nội dung đổi (debounce 400ms) — KHÔNG
// bao giờ lưu thẳng JSON dán vào; chỉ lưu sau khi validate sạch VÀ chủ nhà tự bấm xác nhận trên
// bản tóm tắt tiếng Việt dễ hiểu.
@Composable
fun ImportWorkflowJsonDialog(
    viewModel: HouseManagerViewModel,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val preview by viewModel.importPreview.collectAsState()

    LaunchedEffect(text) {
        if (text.isBlank()) {
            viewModel.clearImportPreview()
            return@LaunchedEffect
        }
        delay(400L) // debounce — tránh validate liên tục khi đang gõ/dán từng ký tự
        viewModel.validateImportJson(text)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nhập kịch bản (dán JSON)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Dán JSON mà AI ngoài trả về (sau khi bạn đưa nó khối \"ngữ cảnh AI\" đã sao chép kèm mô tả kịch bản mong muốn).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    placeholder = { Text("Dán JSON vào đây...", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))

                val result = preview
                if (result != null) {
                    if (result.hasErrors) {
                        Text(
                            text = "Không thể nhập — cần sửa các lỗi sau:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        result.errors.forEach { err ->
                            val prefix = if (err.groupIndex >= 0) "\"${err.groupLabel}\": " else ""
                            Text(
                                text = "• $prefix${err.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (result.validGroups.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sẽ tạo ${result.validGroups.size} kịch bản sau:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        viewModel.summarizeImportPreview().forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val canConfirm = preview?.validGroups?.isNotEmpty() == true
            Button(
                onClick = {
                    viewModel.confirmImportedWorkflows()
                    onDismiss()
                },
                enabled = canConfirm
            ) {
                Text(if (canConfirm) "Xác nhận thêm ${preview?.validGroups?.size} kịch bản" else "Xác nhận")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
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
        // ✅ ĐÃ SỬA: trước đây chỉ có 1 nhãn cứng "KHẨN CẤP" ẩn danh sách từ code cứng phía sau.
        // Nay hiển thị ĐÚNG danh sách từ khoá chủ nhà đã tự nhập, để họ nhìn vào là biết ngay
        // kịch bản này phản ứng với những từ gì mà không cần đoán.
        "keyword" -> {
            val keywords = condition.expected.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (keywords.isNotEmpty()) {
                "phát hiện tin nhắn chứa từ khoá: ${keywords.joinToString(", ") { "\"$it\"" }}"
            } else {
                "nhận tin nhắn (chưa cấu hình từ khoá)"
            }
        }
        else -> "thay đổi giá trị '${condition.attrKey}' thành '${condition.expected}'"
    }

    return "🔥 Ngòi nổ: Khi $friendlyName $actionText"
}

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

    return translateTriggerToFriendlyVietnamese(compiled, availableCameras, availableDevices)
        .removePrefix("🔥 Ngòi nổ: ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualTriggerBuilderDialog(
    availableCameras: List<CameraConfigEntity>,
    availableDevices: List<TuyaDeviceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, source: String, entityId: String, value: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("camera") }
    var selectedEntityId by remember { mutableStateOf("") }
    var selectedExpectedValue by remember { mutableStateOf("suspicious") }

    LaunchedEffect(selectedSource) {
        selectedEntityId = when (selectedSource) {
            "camera" -> availableCameras.firstOrNull()?.id ?: ""
            "tuya" -> availableDevices.firstOrNull()?.id ?: ""
            "chat" -> "*"
            // ✅ MỚI: Ngòi nổ "mood" không gắn với 1 thiết bị/camera cụ thể (là trạng thái toàn
            // nhà) — dùng "*" giống "chat" để luôn hợp lệ (isValid) mà không cần chủ nhà chọn gì
            // ở Bước 2.
            "mood" -> "*"
            else -> ""
        }
        selectedExpectedValue = when (selectedSource) {
            "camera" -> "suspicious"
            "tuya" -> "true"
            // ✅ ĐÃ SỬA: không còn giá trị "high" cứng — chủ nhà sẽ tự gõ từ khoá của mình vào ô
            // nhập bên dưới (xem nhánh "chat" ở Bước 3), nên khởi tạo rỗng để bắt buộc họ nhập.
            "chat" -> ""
            // ✅ MỚI: Mặc định chọn SLEEPING — case dùng nhiều nhất (vd tự động khoá cửa/tắt đèn
            // khi nhà chuyển sang chế độ ngủ).
            "mood" -> "SLEEPING"
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
                    // ✅ MỚI: Thêm nguồn "mood" (Chế độ nhà) — cho phép kịch bản kích hoạt khi
                    // HouseManagerSkillImpl.evaluateSituation() tính ra Chế độ nhà đổi thành 1 giá
                    // trị cụ thể (vd SLEEPING, VACATION), thay vì chỉ dựa vào camera/tuya/chat riêng lẻ.
                    val sources = listOf(
                        "camera" to "📷 Camera",
                        "tuya" to "🔌 Thiết bị Tuya",
                        "chat" to "💬 Khách nhắn",
                        "mood" to "🏠 Chế độ nhà"
                    )
                    sources.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedSource == key,
                            onClick = { selectedSource = key },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("Bước 2: Chọn thiết bị áp dụng", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                when (selectedSource) {
                    "camera" -> {
                        if (availableCameras.isEmpty()) {
                            Text("Chưa lắp camera nào.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } else {
                            DeviceSelectorDropdown(
                                label = "Chọn camera giám sát",
                                list = availableCameras.map { camera ->
                                    val displayLabel = buildString {
                                        append(camera.customername.ifBlank { "Chưa đặt tên" })
                                        append(" (${camera.id})")
                                    }
                                    camera.id to displayLabel
                                },
                                selectedId = selectedEntityId,
                                onSelected = { selectedEntityId = it }
                            )
                        }
                    }
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
                    // ✅ MỚI: "mood" là trạng thái toàn nhà, không cần chọn thiết bị/camera cụ thể ở
                    // Bước 2 — chủ nhà chọn giá trị Chế độ nhà mong muốn trực tiếp ở Bước 3.
                    "mood" -> {
                        Text("Áp dụng tự động khi Chế độ nhà (Mood) thay đổi — không cần chọn thiết bị.", fontSize = 12.sp)
                    }
                }

                Text("Bước 3: Chọn điều kiện kích hoạt", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                when (selectedSource) {
                    // ✅ MỚI: 7 lựa chọn khớp đúng enum HouseMood (data/model/HouseMood.kt) — dùng
                    // displayName() để hiển thị tiếng Việt, nhưng value gửi đi vẫn là tên enum gốc
                    // (vd "SLEEPING") vì đó là giá trị thật world_state ghi ra (xem
                    // HouseManagerSkillImpl.evaluateSituation(), brainJson.put("mood", computedMood.name)).
                    "mood" -> {
                        Text(
                            "Kịch bản sẽ chạy khi Chế độ nhà chuyển đúng sang giá trị đã chọn.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // ⚠️ Dùng 2 Row cố định (không FlowRow) để tránh phải opt-in API thực nghiệm
                        // (ExperimentalLayoutApi) chỉ vì 7 chip — nhất quán với cách các Bước 1/3
                        // khác trong dialog này đã làm (Row + weight, xem "sources" ở Bước 1).
                        val moodOptions = listOf(
                            "NORMAL" to "🙂 Bình thường",
                            "BUSY" to "🏃 Bận rộn",
                            "QUIET" to "🤫 Yên tĩnh",
                            "NIGHT" to "🌆 Ban đêm",
                            "ALERT" to "🚨 Cảnh báo",
                            "SLEEPING" to "😴 Đang ngủ",
                            "VACATION" to "🏖️ Vắng nhà"
                        )
                        moodOptions.chunked(2).forEach { rowItems ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { (value, label) ->
                                    FilterChip(
                                        selected = selectedExpectedValue == value,
                                        onClick = { selectedExpectedValue = value },
                                        label = { Text(label, fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // Đệm khoảng trống nếu hàng cuối lẻ 1 item, tránh chip bị giãn rộng gấp đôi
                                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    "chat" -> {
                        // ✅ ĐÃ SỬA (Từ khoá khẩn cấp tự chọn): trước đây chỉ có 1 chip cứng "Khách
                        // gửi tin nhắn khẩn cấp" (giá trị ẩn "high", do 1 danh sách từ code cứng
                        // quyết định) — chủ nhà không biết "khẩn cấp" là gì. Nay chủ nhà TỰ NHẬP
                        // danh sách từ khoá của riêng mình: nếu tin nhắn đến chứa BẤT KỲ từ khoá nào
                        // trong danh sách này, kịch bản sẽ được kích hoạt.
                        Text(
                            "Kịch bản sẽ chạy khi tin nhắn khách chứa BẤT KỲ từ khoá nào dưới đây.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = selectedExpectedValue,
                            // ⚠️ Chặn ký tự '.' và '=' vì chúng là ký tự phân tách trong cú pháp nội
                            // bộ "chat.*.keyword=..." — nếu lọt vào sẽ làm sai lệch cách tách chuỗi
                            // khi lưu/đọc lại triggerSource.
                            onValueChange = { selectedExpectedValue = it.replace(".", "").replace("=", "") },
                            label = { Text("Từ khoá kích hoạt") },
                            placeholder = { Text("Ví dụ: số điện thoại, đặt hàng, mua, giao hàng") },
                            supportingText = { Text("Cách nhau bởi dấu phẩy. Không phân biệt hoa/thường, không phân biệt dấu.", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val conditions = when (selectedSource) {
                                "camera" -> listOf("suspicious" to "Phát hiện có trộm/nghi vấn")
                                "tuya" -> listOf("true" to "Bị BẬT công tắc", "false" to "Bị TẮT công tắc")
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
                }
            }
        },
        confirmButton = {
            // ✅ MỚI: với ngòi nổ "chat", bắt buộc phải nhập ít nhất 1 từ khoá — nếu không, nhóm
            // kịch bản sẽ được tạo ra nhưng KHÔNG BAO GIỜ kích hoạt được (vì điều kiện keyword rỗng
            // không khớp với gì cả), khiến chủ nhà tưởng đã bật mà thực chất vô tác dụng.
            val isValid = groupName.isNotBlank() && selectedEntityId.isNotBlank() &&
                    (selectedSource != "chat" || selectedExpectedValue.isNotBlank())
            Button(
                onClick = {
                    if (isValid) {
                        onConfirm(groupName.trim(), selectedSource, selectedEntityId, selectedExpectedValue.trim())
                    }
                },
                enabled = isValid
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
    list: List<Pair<String, String>>,
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