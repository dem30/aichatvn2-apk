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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.HouseMood
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

    // ✅ MỚI: Ánh xạ thiết bị Quản gia + danh sách thiết bị/camera thật để hiển thị picker
    val protectLightDevice by viewModel.protectLightDevice.collectAsState()
    val protectSirenDevice by viewModel.protectSirenDevice.collectAsState()
    val protectCameraIds by viewModel.protectCameraIds.collectAsState()
    val availableTuyaDevices by viewModel.availableTuyaDevices.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()

    // ✅ MỚI: Trạng thái của bộ Planner Tự Do (No-Code Planner Builder)
    val protectActions by viewModel.protectActions.collectAsState()
    val alertActionPlugins = viewModel.alertActionPlugins
    var showActionSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val themeColorAndIcon = getThemeForMood(situation?.currentMood ?: HouseMood.NORMAL)
    val animatedHeaderColor by animateColorAsState(targetValue = themeColorAndIcon.headerColor)

    // ⚠️ CẦN KIỂM TRA: AlertActionFormSheet được tái sử dụng từ màn hình Camera hiện có
    // trong project (chưa nằm trong các file bạn đã upload) — hãy đảm bảo import đúng
    // package của nó và chữ ký tham số (plugins/tuyaDevices/activeCameras/onSave/onCancel)
    // khớp với bản gốc trước khi build.
    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = sheetState
        ) {
            AlertActionFormSheet(
                plugins = alertActionPlugins,
                tuyaDevices = availableTuyaDevices,
                activeCameras = availableCameras,
                onSave = { cfg ->
                    viewModel.addProtectAction(cfg)
                    showActionSheet = false
                },
                onCancel = { showActionSheet = false }
            )
        }
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

            // 🧠 MỚI: Bảng cấu hình kịch bản tự do (No-Code Planner Builder) — chủ nhà tự thêm
            // bất kỳ số bước nào (trì hoãn, kiểm duyệt điều kiện, gọi plugin bất kỳ) qua UI.
            item {
                CustomPlannerCard(
                    actions = protectActions,
                    onAddAction = { showActionSheet = true },
                    onRemoveAction = { viewModel.removeProtectAction(it) }
                )
            }

            // 🧠 MỚI: Bảng cấu hình ánh xạ thiết bị răn đe (loại bỏ hoàn toàn hardcode tên thiết
            // bị/ID camera). Chủ nhà CHỌN thiết bị Tuya/camera thật của họ bằng picker (dropdown +
            // checkbox), thay vì gõ tay tên thiết bị dễ gõ sai làm gãy kịch bản. Đây là cấu hình
            // dự phòng (fallback) khi kịch bản tự do ở trên chưa được cấu hình.
            item {
                DeviceMappingConfigCard(
                    currentLight = protectLightDevice,
                    currentSiren = protectSirenDevice,
                    currentCameraIds = protectCameraIds,
                    availableDevices = availableTuyaDevices,
                    availableCameras = availableCameras,
                    onSave = { light, siren, cameraIds ->
                        viewModel.saveDeviceMappings(light, siren, cameraIds)
                    }
                )
            }

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

            // 6. Thẻ kích hoạt Báo động khẩn cấp (Panic Action Button)
            // ✅ ĐÃ SỬA: Dùng camera đầu tiên trong danh sách đã cấu hình thay vì hardcode "cam_01".
            item {
                val panicCameraId = protectCameraIds.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "cam_01"
                PanicTriggerCard(onPanic = { viewModel.triggerPanicSequence(panicCameraId) })
            }

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

// 🧠 MỚI: Thiết kế Composable CustomPlannerCard quản lý danh sách hành động tự do của Quản gia —
// hiển thị từng bước theo thứ tự, diễn giải riêng 2 hành động đặc biệt delay/check_precondition
// thành câu tiếng Việt dễ hiểu, các hành động khác hiển thị dạng plugin.action(params).
@Composable
fun CustomPlannerCard(
    actions: List<AlertActionConfig>,
    onAddAction: () -> Unit,
    onRemoveAction: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kịch bản răn đe Tự Do (Custom Planner)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Thiết lập các bước tự chọn khi phát hiện trộm",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                OutlinedButton(
                    onClick = onAddAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Thêm", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thêm bước", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (actions.isEmpty()) {
                Text(
                    text = "ℹ️ Chưa cấu hình kịch bản tự do — Quản gia sẽ tự động chạy kịch bản 5 bước mặc định làm dự phòng.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else {
                actions.forEachIndexed { index, cfg ->
                    val stepDescription = when {
                        cfg.pluginId == "house_manager" && cfg.action == "delay" ->
                            "⏳ Trì hoãn ${cfg.params["delayMs"] ?: "0"} ms"
                        cfg.pluginId == "house_manager" && cfg.action == "check_precondition" ->
                            "🔒 Kiểm duyệt điều kiện: '${cfg.params["precondition"]}'"
                        else ->
                            "⚡ Gọi ${cfg.pluginId}.${cfg.action} (${cfg.params.values.joinToString(", ")})"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bước ${index + 1}: $stepDescription",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveAction(index) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Xóa",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

// 🧠 MỚI: Bảng cấu hình ánh xạ thiết bị răn đe — chủ nhà CHỌN thiết bị Tuya/camera thật của



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceMappingConfigCard(
    currentLight: String, // Đang lưu trữ ID thật
    currentSiren: String, // Đang lưu trữ ID thật
    currentCameraIds: String,
    availableDevices: List<TuyaDeviceEntity>,
    availableCameras: List<CameraConfigEntity>,
    onSave: (light: String, siren: String, cameraIds: List<String>) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var lightSelection by remember(currentLight, isEditing) { mutableStateOf(currentLight) }
    var sirenSelection by remember(currentSiren, isEditing) { mutableStateOf(currentSiren) }
    var selectedCameraIds by remember(currentCameraIds, isEditing) {
        mutableStateOf(currentCameraIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    fun cameraLabel(cameraId: String): String {
        val cam = availableCameras.firstOrNull { it.id.trim() == cameraId }
        return cam?.customername?.takeIf { it.isNotBlank() } ?: cameraId
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ánh xạ Thiết bị Răn đe (Planner Settings)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    if (isEditing) {
                        lightSelection = currentLight
                        sirenSelection = currentSiren
                        selectedCameraIds = currentCameraIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                    isEditing = !isEditing
                }) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Hủy" else "Sửa",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                // Truyền ID đang chọn vào Picker
                DevicePickerDropdown(
                    label = "Thiết bị đèn dọa trộm",
                    devices = availableDevices,
                    selectedId = lightSelection,
                    onSelected = { lightSelection = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DevicePickerDropdown(
                    label = "Thiết bị còi báo động",
                    devices = availableDevices,
                    selectedId = sirenSelection,
                    onSelected = { sirenSelection = it }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Camera kích hoạt kịch bản (chọn nhiều — để trống = áp dụng tất cả)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (availableCameras.isEmpty()) {
                    Text(
                        text = "Chưa có camera nào được đồng bộ vào hệ thống.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                } else {
                    Column {
                        availableCameras.forEach { camera ->
                            val camId = camera.id.trim()
                            val checked = selectedCameraIds.contains(camId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCameraIds = if (checked) selectedCameraIds - camId else selectedCameraIds + camId
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedCameraIds = if (isChecked) selectedCameraIds + camId else selectedCameraIds - camId
                                    }
                                )
                                Text(
                                    text = "${camera.customername.ifBlank { camId }} ($camId)",
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSave(lightSelection.trim(), sirenSelection.trim(), selectedCameraIds.toList())
                        isEditing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lưu cấu hình", fontWeight = FontWeight.Bold)
                }
            } else {
                // ✅ ĐÃ SỬA: Quy đổi ID đang lưu thành tên hiển thị thân thiện khi ở chế độ xem
                val lightFriendlyName = availableDevices.find { it.id.trim() == currentLight.trim() }?.name ?: currentLight
                val sirenFriendlyName = availableDevices.find { it.id.trim() == currentSiren.trim() }?.name ?: currentSiren

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "💡 Đèn kích hoạt: $lightFriendlyName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "🔊 Còi báo động: $sirenFriendlyName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val cameraSummary = if (currentCameraIds.isBlank()) {
                        "Tất cả camera"
                    } else {
                        currentCameraIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { cameraLabel(it) }
                    }
                    Text(text = "📷 Camera áp dụng: $cameraSummary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}






// Picker chọn 1 thiết bị Tuya từ danh sách thật của chủ nhà (dropdown), thay vì gõ tay.

// Picker chọn 1 thiết bị Tuya từ danh sách thật, lưu ID thật (device.id) y hệt các file khác
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerDropdown(
    label: String,
    devices: List<TuyaDeviceEntity>,
    selectedId: String, // Đổi từ selectedName sang selectedId
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    // Tìm tên thân thiện để hiển thị lên ô nhập từ ID đang chọn
    val selectedDevice = devices.find { it.id.trim() == selectedId.trim() }
    val displayText = selectedDevice?.name ?: selectedId

    if (devices.isEmpty()) {
        OutlinedTextField(
            value = selectedId,
            onValueChange = onSelected,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            supportingText = { Text("Chưa có thiết bị nào đồng bộ — gõ tay ID thiết bị", fontSize = 10.sp) }
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = displayText, // Hiển thị tên thân thiện
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(8.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name) },
                    onClick = {
                        onSelected(device.id) // ✅ SỬA: Lưu ID thật (device.id) thay vì device.name
                        expanded = false
                    }
                )
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