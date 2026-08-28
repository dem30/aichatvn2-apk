// File: com/aichatvn/agent/ui/screens/ScheduleScreen.kt

package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.core.plugin.DynamicOptionRegistry
import com.aichatvn.agent.core.plugin.OptionItem
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.ui.viewmodels.ScheduleViewModel
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    navController: NavController,
    viewModel: ScheduleViewModel = hiltViewModel()
) {
    val schedules by viewModel.schedules.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadSchedules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lịch trình") },
                actions = {
                    IconButton(onClick = {
                        editingSchedule = null
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, "Thêm lịch")
                    }
                }
            )
        }
    ) { padding ->
        if (schedules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏰", fontSize = MaterialTheme.typography.displayMedium.fontSize)
                    Text("Chưa có lịch trình nào")
                    TextButton(onClick = {
                        editingSchedule = null
                        showAddDialog = true
                    }) {
                        Text("Thêm lịch trình đầu tiên")
                    }
                }
            }
        } else {
            // ✅ MỚI: nhóm lịch theo thiết bị/nguồn — trích "device" (hoặc "camera_id" cho
            // camera.*) từ params JSON, KHÔNG có cột deviceId riêng ở ScheduleEntity nên phải
            // parse mỗi lần render (chấp nhận được, danh sách lịch không lớn, không phải hot
            // path như DeviceNodeWidget). Lịch không xác định được thiết bị (camera.scan quét
            // toàn hệ thống, hoặc params rỗng/hỏng) rơi vào nhóm "Khác" ở cuối.
            //
            // deviceNames: map id -> tên hiển thị, lấy qua viewModel.optionRegistry (đã gộp mọi
            // driver Tuya/MQTT — xem DynamicOptionRegistry.getOptions("device")), KHÔNG tự query
            // database.tuyaDeviceDao() ở đây để tránh lặp lại logic gộp driver đã có sẵn.
            var deviceNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            LaunchedEffect(schedules) {
                deviceNames = viewModel.optionRegistry.getOptions("device")
                    .associate { it.value to it.label }
            }
            val grouped = remember(schedules, deviceNames) {
                groupSchedulesByDevice(schedules, deviceNames)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (groupLabel, groupSchedules) ->
                    item(key = "header_$groupLabel") {
                        Text(
                            groupLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
                        )
                    }
                    items(groupSchedules.size, key = { "item_${groupSchedules[it].id}" }) { i ->
                        val schedule = groupSchedules[i]
                        ScheduleCard(
                            index = schedules.indexOf(schedule) + 1,
                            schedule = schedule,
                            onToggle = { viewModel.toggleSchedule(it) },
                            onDelete = { viewModel.deleteSchedule(it) },
                            onEdit = {
                                editingSchedule = it
                                showAddDialog = true
                            },
                            // ✅ MỚI: sao chép — tạo bản ghi mới cùng pluginId/action/params/cron,
                            // chỉ đổi id mới + nhãn thêm hậu tố "(bản sao)" để phân biệt.
                            onCopy = {
                                viewModel.addSchedule(
                                    schedule.copy(
                                        id = java.util.UUID.randomUUID().toString(),
                                        label = "${schedule.label.ifBlank { "${schedule.pluginId}.${schedule.action}" }} (bản sao)",
                                        lastRunAt = 0,
                                        createdAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            plugins = viewModel.schedulablePlugins,
            editingSchedule = editingSchedule,
            optionRegistry = viewModel.optionRegistry, // 🌟 Dùng duy nhất Registry trung tâm
            onDismiss = {
                showAddDialog = false
                editingSchedule = null
            },
            onSave = { schedule ->
                if (editingSchedule != null) {
                    viewModel.updateSchedule(schedule)
                } else {
                    viewModel.addSchedule(schedule)
                }
                showAddDialog = false
                editingSchedule = null
            }
        )
    }
}

// ✅ MỚI: nhóm danh sách lịch theo thiết bị — đọc "device" (SmartSwitchSkill dùng key này,
// xem PluginParameter("device", "string", ...) ở SmartSwitchSkill.kt) hoặc "camera_id"
// (CameraSkill) từ params JSON. Không sửa được ScheduleEntity thêm cột deviceId trong phạm vi
// này (cần migration DB riêng, rủi ro hơn so với việc chỉ parse JSON lúc hiển thị).
private fun groupSchedulesByDevice(
    schedules: List<ScheduleEntity>,
    deviceNames: Map<String, String>
): Map<String, List<ScheduleEntity>> {
    val result = LinkedHashMap<String, MutableList<ScheduleEntity>>()
    schedules.forEach { schedule ->
        val deviceId = try {
            val json = JSONObject(schedule.params.ifBlank { "{}" })
            json.optString("device", "").takeIf { it.isNotBlank() }
                ?: json.optString("camera_id", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        // deviceNames đã chứa "displayName (id 4 số cuối)" (xem DynamicOptionRegistry) — dùng
        // trực tiếp làm groupKey nếu tìm thấy id, fallback về chính id thô nếu id lạ (thiết bị
        // đã bị xoá khỏi hệ thống nhưng lịch cũ vẫn còn), cuối cùng mới rơi về nhóm "Khác".
        val groupKey = deviceId?.let { deviceNames[it] ?: it }
            ?: "⚙️ Khác (${schedule.pluginId})"
        result.getOrPut(groupKey) { mutableListOf() }.add(schedule)
    }
    return result
}

@Composable
fun ScheduleCard(
    index: Int,
    schedule: ScheduleEntity,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (ScheduleEntity) -> Unit,
    // ✅ MỚI: sao chép lịch — mặc định rỗng để KHÔNG bắt buộc mọi nơi gọi ScheduleCard() khác
    // (nếu có) phải sửa theo, dù hiện chỉ có đúng 1 nơi gọi trong ScheduleScreen.kt.
    onCopy: (ScheduleEntity) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (schedule.enabled == 1)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "#$index · ${schedule.label.ifBlank { "${schedule.pluginId}.${schedule.action}" }}",
                    style = MaterialTheme.typography.titleSmall
                )
                if (schedule.label.isNotBlank()) {
                    Text(
                        "${schedule.pluginId}.${schedule.action}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    when {
                        schedule.cron.isNotEmpty() -> "⏰ ${schedule.cron}"
                        schedule.intervalMinutes > 0 -> "🔄 ${schedule.intervalMinutes} phút/lần"
                        else -> "⏸ Không có lịch"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (schedule.lastRunAt > 0) {
                    Text(
                        "Lần cuối: ${java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(schedule.lastRunAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = schedule.enabled == 1,
                onCheckedChange = { onToggle(schedule.id) }
            )

            IconButton(onClick = { onEdit(schedule) }) {
                Icon(Icons.Default.Edit, "Sửa", tint = MaterialTheme.colorScheme.primary)
            }

            // ✅ MỚI: Sao chép
            IconButton(onClick = { onCopy(schedule) }) {
                Icon(Icons.Default.ContentCopy, "Sao chép", tint = MaterialTheme.colorScheme.secondary)
            }

            IconButton(onClick = { onDelete(schedule.id) }) {
                Icon(Icons.Default.Delete, "Xóa", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ✅ GIỮ LẠI (không có trong bản patch đề xuất): tương thích ngược cho các lịch cũ đã lưu
// device/camera dưới dạng chuỗi hiển thị "Tên (ID)" thay vì ID thô — nếu bỏ hàm này, mở sửa
// một lịch cũ sẽ hiện Dropdown trống vì không khớp value nào trong danh sách optionRegistry trả về.
private fun normalizeLegacyRefId(raw: String, validIds: List<String>): String {
    val trimmed = raw.trim()
    if (validIds.any { it.trim() == trimmed }) return trimmed
    val extracted = Regex("\\(([^()]+)\\)\\s*$").find(trimmed)?.groupValues?.get(1)?.trim()
    if (extracted != null && validIds.any { it.trim() == extracted }) return extracted
    return trimmed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleDialog(
    plugins: List<Plugin>,
    editingSchedule: ScheduleEntity? = null,
    optionRegistry: DynamicOptionRegistry, // 🌟 Inject registry
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var pluginExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }

    val initialPlugin = remember(editingSchedule) {
        editingSchedule?.let { schedule ->
            plugins.find { it.id == schedule.pluginId }
        }
    }
    var selectedPlugin by remember(initialPlugin) { mutableStateOf(initialPlugin) }

    val initialAction = remember(editingSchedule, selectedPlugin) {
        editingSchedule?.let { schedule ->
            selectedPlugin?.getActions()?.find { it.name == schedule.action }
        }
    }
    var selectedAction by remember(initialAction) { mutableStateOf(initialAction) }

    val paramValues = remember { mutableStateMapOf<String, String>() }
    val paramBooleans = remember { mutableStateMapOf<String, Boolean>() }

    var label by remember(editingSchedule) { mutableStateOf(editingSchedule?.label ?: "") }

    val parsedRepeatMode = remember(editingSchedule) {
        when {
            editingSchedule == null -> "daily"
            editingSchedule.intervalMinutes > 0 -> "interval"
            editingSchedule.cron.isNotEmpty() -> {
                val parts = editingSchedule.cron.trim().split("\\s+".toRegex())
                if (parts.size == 5 && parts[4] != "*") "weekly" else "daily"
            }
            else -> "daily"
        }
    }
    var repeatMode by remember(parsedRepeatMode) { mutableStateOf(parsedRepeatMode) }

    val parsedIntervalMinutes = remember(editingSchedule) {
        if (editingSchedule != null && editingSchedule.intervalMinutes > 0) {
            editingSchedule.intervalMinutes.toString()
        } else ""
    }
    var intervalMinutes by remember(parsedIntervalMinutes) { mutableStateOf(parsedIntervalMinutes) }

    val parsedCron = remember(editingSchedule) {
        if (editingSchedule != null && editingSchedule.cron.isNotEmpty() && parsedRepeatMode == "advanced") {
            editingSchedule.cron
        } else ""
    }
    var cron by remember(parsedCron) { mutableStateOf(parsedCron) }

    val parsedTime = remember(editingSchedule) {
        if (editingSchedule != null && editingSchedule.cron.isNotEmpty()) {
            val parts = editingSchedule.cron.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val m = parts[0].toIntOrNull() ?: 0
                val h = parts[1].toIntOrNull() ?: 7
                Pair(h, m)
            } else Pair(7, 0)
        } else Pair(7, 0)
    }
    val timePickerState = rememberTimePickerState(
        initialHour = parsedTime.first,
        initialMinute = parsedTime.second,
        is24Hour = true
    )

    val parsedWeekdays = remember(editingSchedule) {
        val list = mutableListOf<Int>()
        if (editingSchedule != null && editingSchedule.cron.isNotEmpty()) {
            val parts = editingSchedule.cron.trim().split("\\s+".toRegex())
            if (parts.size == 5 && parts[4] != "*") {
                parts[4].split(",").mapNotNull { it.toIntOrNull() }.forEach { list.add(it) }
            }
        }
        list
    }
    val selectedWeekdays = remember { mutableStateListOf<Int>().apply { addAll(parsedWeekdays) } }
    val weekdayOptions = listOf("T2" to 1, "T3" to 2, "T4" to 3, "T5" to 4, "T6" to 5, "T7" to 6, "CN" to 0)

    // Khôi phục dữ liệu tham số khi sửa (kèm chuẩn hoá ID cũ cho device/camera — xem
    // normalizeLegacyRefId ở trên, giữ tương thích ngược với lịch tạo trước khi có Registry).
    LaunchedEffect(selectedAction) {
        paramValues.clear()
        paramBooleans.clear()
        selectedAction?.parameters?.forEach { p ->
            if (p.type == "boolean") paramBooleans[p.name] = false else paramValues[p.name] = ""
        }

        if (selectedAction != null && editingSchedule != null &&
            editingSchedule.pluginId == selectedPlugin?.id &&
            editingSchedule.action == selectedAction?.name
        ) {
            try {
                val json = JSONObject(editingSchedule.params)
                selectedAction?.parameters?.forEach { p ->
                    if (p.type == "boolean") {
                        paramBooleans[p.name] = json.optBoolean(p.name, false)
                    } else {
                        val raw = json.opt(p.name)?.toString() ?: ""
                        paramValues[p.name] = when (p.semanticType) {
                            "camera", "device" -> {
                                val validIds = optionRegistry.getOptions(p.semanticType, emptyMap()).map { it.value }
                                normalizeLegacyRefId(raw, validIds)
                            }
                            else -> raw
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun selectPlugin(p: Plugin) {
        selectedPlugin = p
        selectedAction = null
        paramValues.clear()
        paramBooleans.clear()
    }

    fun selectAction(a: PluginAction) {
        selectedAction = a
        paramValues.clear()
        paramBooleans.clear()
        a.parameters.forEach { p ->
            if (p.type == "boolean") paramBooleans[p.name] = false else paramValues[p.name] = ""
        }
    }

    // 🌟 LỌC THAM SỐ THÔNG MINH DỰA TRÊN DEPENDS_ON & VISIBLE_WHEN
    val visibleParameters = remember(selectedAction, paramValues.toMap()) {
        selectedAction?.parameters?.filter { param ->
            if (param.dependsOn == null) {
                true
            } else {
                val parentValue = paramValues[param.dependsOn]
                if (parentValue.isNullOrBlank()) {
                    false
                } else if (param.visibleWhen != null) {
                    param.visibleWhen.contains(parentValue)
                } else {
                    true
                }
            }
        } ?: emptyList()
    }

    val requiredParamsFilled = visibleParameters
        .filter { it.required }
        .all { p -> p.type == "boolean" || !paramValues[p.name].isNullOrBlank() }

    val timingFilled = when (repeatMode) {
        "daily" -> true
        "weekly" -> selectedWeekdays.isNotEmpty()
        "interval" -> (intervalMinutes.toIntOrNull() ?: 0) > 0
        "advanced" -> cron.isNotBlank() || (intervalMinutes.toIntOrNull() ?: 0) > 0
        else -> false
    }

    val canSave = selectedPlugin != null && selectedAction != null && requiredParamsFilled && timingFilled

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingSchedule != null) "Chỉnh sửa lịch trình" else "Thêm lịch trình mới") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Tên lịch trình (tuỳ chọn)") },
                    placeholder = { Text("VD: Bật đèn phòng khách") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // 1. Dropdown Chọn Plugin
                ExposedDropdownMenuBox(
                    expanded = pluginExpanded,
                    onExpandedChange = { pluginExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedPlugin?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Plugin") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pluginExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = pluginExpanded,
                        onDismissRequest = { pluginExpanded = false }
                    ) {
                        plugins.forEach { plugin ->
                            DropdownMenuItem(
                                text = { Text(plugin.name) },
                                onClick = {
                                    selectPlugin(plugin)
                                    pluginExpanded = false
                                }
                            )
                        }
                    }
                }

                // 2. Dropdown Chọn Action
                selectedPlugin?.let { plugin ->
                    ExposedDropdownMenuBox(
                        expanded = actionExpanded,
                        onExpandedChange = { actionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedAction?.let { "${it.name} — ${it.description}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Hành động") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = actionExpanded,
                            onDismissRequest = { actionExpanded = false }
                        ) {
                            plugin.getActions().forEach { act ->
                                DropdownMenuItem(
                                    text = { Text("${act.name} — ${act.description}") },
                                    onClick = {
                                        selectAction(act)
                                        actionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 🌟 3. RENDER THAM SỐ TỰ ĐỘNG BẰNG DYNAMIC OPTION REGISTRY (Không hardcode tuyaDevices/activeCameras)
                visibleParameters.forEach { param ->
                    if (param.type == "boolean") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${param.description}${if (param.required) " *" else ""}")
                            }
                            Switch(
                                checked = paramBooleans[param.name] ?: false,
                                onCheckedChange = { paramBooleans[param.name] = it }
                            )
                        }
                    } else {
                        var options by remember { mutableStateOf<List<OptionItem>>(emptyList()) }

                        LaunchedEffect(param.semanticType, paramValues.toMap()) {
                            options = optionRegistry.getOptions(param.semanticType, paramValues.toMap())
                        }

                        if (options.isNotEmpty()) {
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            val currentValue = paramValues[param.name] ?: ""
                            val currentLabel = options.find { it.value == currentValue }?.label ?: currentValue

                            ExposedDropdownMenuBox(
                                expanded = dropdownExpanded,
                                onExpandedChange = { dropdownExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = currentLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("${param.description}${if (param.required) " *" else ""}") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    options.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text(item.label) },
                                            onClick = {
                                                val oldVal = paramValues[param.name]
                                                paramValues[param.name] = item.value

                                                if (oldVal != item.value) {
                                                    selectedAction?.parameters?.filter { it.dependsOn == param.name }?.forEach { child ->
                                                        paramValues.remove(child.name)
                                                    }
                                                }
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = paramValues[param.name] ?: "",
                                onValueChange = { paramValues[param.name] = it },
                                label = { Text("${param.description}${if (param.required) " *" else ""}") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = param.type != "string" || param.name != "body",
                                keyboardOptions = if (param.type == "number")
                                    KeyboardOptions(keyboardType = KeyboardType.Number)
                                else KeyboardOptions.Default,
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                // 4. Chọn thời gian thực thi (Hàng ngày, Theo tuần, Lặp theo phút)
                if (selectedAction != null) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Khi nào chạy", style = MaterialTheme.typography.labelMedium)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = repeatMode == "daily",
                            onClick = { repeatMode = "daily" },
                            label = { Text("Hàng ngày") }
                        )
                        FilterChip(
                            selected = repeatMode == "weekly",
                            onClick = { repeatMode = "weekly" },
                            label = { Text("Theo tuần") }
                        )
                        FilterChip(
                            selected = repeatMode == "interval",
                            onClick = { repeatMode = "interval" },
                            label = { Text("Lặp theo phút") }
                        )
                    }

                    when (repeatMode) {
                        "daily", "weekly" -> {
                            TimePicker(state = timePickerState)

                            if (repeatMode == "weekly") {
                                Text("Chọn ngày trong tuần", style = MaterialTheme.typography.labelSmall)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    weekdayOptions.forEach { (dayLabel, cronValue) ->
                                        FilterChip(
                                            selected = cronValue in selectedWeekdays,
                                            onClick = {
                                                if (cronValue in selectedWeekdays) {
                                                    selectedWeekdays.remove(cronValue)
                                                } else {
                                                    selectedWeekdays.add(cronValue)
                                                }
                                            },
                                            label = { Text(dayLabel) }
                                        )
                                    }
                                }
                            }
                        }
                        "interval" -> {
                            OutlinedTextField(
                                value = intervalMinutes,
                                onValueChange = { intervalMinutes = it },
                                label = { Text("Khoảng cách (phút)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    var showAdvanced by remember { mutableStateOf(false) }
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Ẩn tuỳ chỉnh nâng cao" else "Tuỳ chỉnh nâng cao (cron thủ công)")
                    }
                    if (showAdvanced) {
                        OutlinedTextField(
                            value = cron,
                            onValueChange = {
                                cron = it
                                if (it.isNotBlank()) repeatMode = "advanced"
                            },
                            label = { Text("Cron thủ công (VD: 0 8 * * *)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val plugin = selectedPlugin ?: return@TextButton
                    val action = selectedAction ?: return@TextButton

                    val finalCron: String
                    val finalInterval: Int
                    when (repeatMode) {
                        "daily" -> {
                            finalCron = "${timePickerState.minute} ${timePickerState.hour} * * *"
                            finalInterval = 0
                        }
                        "weekly" -> {
                            val days = selectedWeekdays.sorted().joinToString(",")
                            finalCron = "${timePickerState.minute} ${timePickerState.hour} * * $days"
                            finalInterval = 0
                        }
                        "interval" -> {
                            finalCron = ""
                            finalInterval = intervalMinutes.toIntOrNull() ?: 0
                        }
                        else -> {
                            finalCron = cron
                            finalInterval = intervalMinutes.toIntOrNull() ?: 0
                        }
                    }

                    val paramsJson = JSONObject().apply {
                        visibleParameters.forEach { p ->
                            when (p.type) {
                                "boolean" -> put(p.name, paramBooleans[p.name] ?: false)
                                "number" -> paramValues[p.name]?.toDoubleOrNull()?.let { put(p.name, it) }
                                else -> paramValues[p.name]?.trim()?.takeIf { it.isNotBlank() }?.let { put(p.name, it) }
                            }
                        }
                    }.toString()

                    val finalLabel = label.trim().ifBlank { "${plugin.id}.${action.name}" }

                    val schedule = ScheduleEntity(
                        id = editingSchedule?.id ?: UUID.randomUUID().toString(),
                        pluginId = plugin.id,
                        action = action.name,
                        params = paramsJson,
                        cron = finalCron,
                        intervalMinutes = finalInterval,
                        enabled = editingSchedule?.enabled ?: 1,
                        lastRunAt = editingSchedule?.lastRunAt ?: 0,
                        createdAt = editingSchedule?.createdAt ?: System.currentTimeMillis(),
                        label = finalLabel
                    )
                    onSave(schedule)
                }
            ) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}