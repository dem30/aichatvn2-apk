package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
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
    val tuyaDevices by viewModel.tuyaDevices.collectAsState()
    val activeCameras by viewModel.activeCameras.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.loadSchedules()
        viewModel.loadDevicesAndCameras()
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(schedules.size) { index ->
                    val schedule = schedules[index]
                    ScheduleCard(
                        index = index + 1,
                        schedule = schedule,
                        onToggle = { viewModel.toggleSchedule(it) },
                        onDelete = { viewModel.deleteSchedule(it) },
                        onEdit = { 
                            editingSchedule = it
                            showAddDialog = true 
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            plugins = viewModel.schedulablePlugins,
            editingSchedule = editingSchedule,
            tuyaDevices = tuyaDevices,
            activeCameras = activeCameras,
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

@Composable
fun ScheduleCard(
    index: Int,
    schedule: ScheduleEntity,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (ScheduleEntity) -> Unit
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
            
            IconButton(onClick = { onDelete(schedule.id) }) {
                Icon(Icons.Default.Delete, "Xóa", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ✅ MỚI: Phục hồi cameraId/deviceId "sạch" từ giá trị đã lưu, phòng trường hợp dữ liệu cũ
// từng bị lưu nhầm thành chuỗi hiển thị "landInfo (id)" hoặc "name (id)" thay vì ID thuần,
// hoặc lệch khoảng trắng đầu/cuối khiến so khớp exact-match thất bại. Nếu không trích được
// gì khớp, trả về nguyên giá trị gốc (đã trim) để không làm mất dữ liệu người dùng đã nhập.
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
    tuyaDevices: List<TuyaDeviceEntity> = emptyList(),
    activeCameras: List<CameraConfigEntity> = emptyList(),
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
                            // ✅ SỬA: tự phục hồi ID sạch nếu dữ liệu cũ bị lưu nhầm dạng
                            // "landInfo (id)" / "name (id)", tránh hiển thị nhầm và gửi sai cameraId
                            "camera" -> normalizeLegacyRefId(raw, activeCameras.map { it.id })
                            "device" -> normalizeLegacyRefId(raw, tuyaDevices.map { it.id })
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

    val requiredParamsFilled = selectedAction?.parameters
        ?.filter { it.required }
        ?.all { p -> p.type == "boolean" || !paramValues[p.name].isNullOrBlank() }
        ?: true

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
                    singleLine = true
                )

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
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
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

                selectedAction?.parameters?.forEach { param ->
                    when {
                        param.type == "boolean" -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${param.name}${if (param.required) " *" else ""}")
                                    Text(
                                        param.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = paramBooleans[param.name] ?: false,
                                    onCheckedChange = { paramBooleans[param.name] = it }
                                )
                            }
                        }
                        
                        param.semanticType == "device" -> {
                            var deviceExpanded by remember { mutableStateOf(false) }
                            val selectedDeviceId = paramValues[param.name] ?: ""
                            val selectedDevice = tuyaDevices.find { it.id.trim() == selectedDeviceId.trim() }
                            val displayText = selectedDevice?.let { dev ->
                                val hasDuplicate = tuyaDevices.count { d -> d.name == dev.name } > 1
                                if (hasDuplicate) "${dev.name} (${dev.id.takeLast(4)})" else dev.name
                            } ?: selectedDeviceId

                            ExposedDropdownMenuBox(
                                expanded = deviceExpanded,
                                onExpandedChange = { deviceExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = displayText,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("${param.name}${if (param.required) " *" else ""} — Chọn thiết bị") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = deviceExpanded,
                                    onDismissRequest = { deviceExpanded = false }
                                ) {
                                    tuyaDevices.forEach { dev ->
                                        val hasDuplicate = tuyaDevices.count { d -> d.name == dev.name } > 1
                                        val itemLabel = if (hasDuplicate) "${dev.name} (${dev.id.takeLast(4)})" else dev.name
                                        DropdownMenuItem(
                                            text = { Text(itemLabel) },
                                            onClick = {
                                                paramValues[param.name] = dev.id
                                                deviceExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        param.semanticType == "camera" -> {
                            var cameraExpanded by remember { mutableStateOf(false) }
                            val selectedCameraId = paramValues[param.name] ?: ""
                            val selectedCamera = activeCameras.find { it.id.trim() == selectedCameraId.trim() }
                            val displayText = selectedCamera?.let { cam ->
                                if (!cam.landinfo.isNullOrBlank()) "${cam.landinfo} (${cam.id})" else cam.id
                            } ?: selectedCameraId

                            ExposedDropdownMenuBox(
                                expanded = cameraExpanded,
                                onExpandedChange = { cameraExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = displayText,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("${param.name}${if (param.required) " *" else ""} — Chọn camera") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = cameraExpanded,
                                    onDismissRequest = { cameraExpanded = false }
                                ) {
                                    activeCameras.forEach { cam ->
                                        val itemLabel = if (!cam.landinfo.isNullOrBlank()) "${cam.landinfo} (${cam.id})" else cam.id
                                        DropdownMenuItem(
                                            text = { Text(itemLabel) },
                                            onClick = {
                                                paramValues[param.name] = cam.id
                                                cameraExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            OutlinedTextField(
                                value = paramValues[param.name] ?: "",
                                onValueChange = { paramValues[param.name] = it },
                                label = { Text("${param.name}${if (param.required) " *" else ""} — ${param.description}") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = param.type != "string" || param.name != "body",
                                keyboardOptions = if (param.type == "number")
                                    KeyboardOptions(keyboardType = KeyboardType.Number)
                                else KeyboardOptions.Default
                            )
                        }
                    }
                }

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
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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
                            singleLine = true
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
                        action.parameters.forEach { p ->
                            when (p.type) {
                                "boolean" -> put(p.name, paramBooleans[p.name] ?: false)
                                "number" -> paramValues[p.name]?.toDoubleOrNull()?.let { put(p.name, it) }
                                // ✅ SỬA: trim() để không lưu lại khoảng trắng thừa (đặc biệt với
                                // cameraId/deviceId — nguồn gốc của nhiều lỗi so khớp trong app này)
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