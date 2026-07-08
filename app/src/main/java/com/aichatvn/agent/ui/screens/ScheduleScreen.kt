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
    
    LaunchedEffect(Unit) {
        viewModel.loadSchedules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lịch trình") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
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
                    TextButton(onClick = { showAddDialog = true }) {
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
                // ✅ Dùng index-based items để truyền số thứ tự vào ScheduleCard.
                // User thấy "#1", "#2"... → có thể nói "xoá lịch số 1" với AI.
                items(schedules.size) { index ->
                    val schedule = schedules[index]
                    ScheduleCard(
                        index = index + 1,
                        schedule = schedule,
                        onToggle = { viewModel.toggleSchedule(it) },
                        onDelete = { viewModel.deleteSchedule(it) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            plugins = viewModel.schedulablePlugins,
            onDismiss = { showAddDialog = false },
            onSave = { schedule ->
                viewModel.addSchedule(schedule)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun ScheduleCard(
    index: Int,                     // ✅ Số thứ tự hiển thị cho user và AI
    schedule: ScheduleEntity,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
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
                // ✅ ĐÃ SỬA: Hiện tên gợi nhớ (label) làm tiêu đề chính thay vì "pluginId.action" thô.
                // Fallback về pluginId.action nếu label rỗng (lịch cũ tạo trước khi có field này).
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
            
            IconButton(onClick = { onDelete(schedule.id) }) {
                Icon(Icons.Default.Delete, "Xóa", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleDialog(
    plugins: List<Plugin>,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var pluginExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }

    var selectedPlugin by remember { mutableStateOf<Plugin?>(null) }
    var selectedAction by remember { mutableStateOf<PluginAction?>(null) }

    val paramValues = remember { mutableStateMapOf<String, String>() }
    val paramBooleans = remember { mutableStateMapOf<String, Boolean>() }

    // ✅ MỚI: tên gợi nhớ cho lịch trình — xem ScheduleSkill.handleAdd() / label field trong Entity
    var label by remember { mutableStateOf("") }

    // ✅ MỚI: "Lặp lại" kiểu Smart Life — daily/weekly dùng TimePicker + weekday chips,
    // interval giữ cách cũ (mỗi N phút), advanced để nhập cron thủ công cho trường hợp đặc biệt
    // (không phá khả năng cron tuỳ ý sẵn có của hệ thống).
    var repeatMode by remember { mutableStateOf("daily") } // "daily" | "weekly" | "interval" | "advanced"
    val timePickerState = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)
    val selectedWeekdays = remember { mutableStateListOf<Int>() } // giá trị cron: 0=CN,1=T2...6=T7
    val weekdayOptions = listOf("T2" to 1, "T3" to 2, "T4" to 3, "T5" to 4, "T6" to 5, "T7" to 6, "CN" to 0)

    var cron by remember { mutableStateOf("") }          // dùng khi repeatMode == "advanced"
    var intervalMinutes by remember { mutableStateOf("") } // dùng khi repeatMode == "interval"

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

    // ✅ ĐÃ SỬA: điều kiện "đã chọn thời gian" giờ phụ thuộc repeatMode thay vì chỉ check cron/interval thô
    val timingFilled = when (repeatMode) {
        "daily" -> true // TimePicker luôn có giá trị mặc định
        "weekly" -> selectedWeekdays.isNotEmpty()
        "interval" -> (intervalMinutes.toIntOrNull() ?: 0) > 0
        "advanced" -> cron.isNotBlank() || (intervalMinutes.toIntOrNull() ?: 0) > 0
        else -> false
    }

    val canSave = selectedPlugin != null &&
        selectedAction != null &&
        requiredParamsFilled &&
        timingFilled

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm lịch trình mới") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ✅ MỚI: tên gợi nhớ — optional, để trống sẽ fallback "pluginId.action" (ScheduleSkill)
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
                    if (param.type == "boolean") {
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
                    } else {
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

                if (selectedAction != null) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Khi nào chạy", style = MaterialTheme.typography.labelMedium)

                    // ✅ MỚI: chọn kiểu lặp lại — thay cho 2 ô nhập cron/interval thô trước đây
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
                            // Giờ chạy chung cho cả 2 kiểu
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

                    // ✅ Giữ lối cũ (nhập cron tay) cho trường hợp đặc biệt picker chưa hỗ trợ —
                    // không phá khả năng cron tuỳ ý sẵn có của hệ thống (TaskScheduler/DateTimeParser).
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
                            label = { Text("Cron thủ công (VD: 0 8 * * *) — ghi đè lựa chọn ở trên") },
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

                    // ✅ ĐÃ SỬA: build cron/intervalMinutes theo repeatMode thay vì đọc thẳng
                    // 2 ô nhập tay — vẫn dùng đúng format cron cũ ("$minute $hour * * $days")
                    // để tương thích 100% với TaskScheduler/DateTimeParser hiện có.
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
                        else -> { // "advanced"
                            finalCron = cron
                            finalInterval = intervalMinutes.toIntOrNull() ?: 0
                        }
                    }

                    val paramsJson = JSONObject().apply {
                        action.parameters.forEach { p ->
                            when (p.type) {
                                "boolean" -> put(p.name, paramBooleans[p.name] ?: false)
                                "number" -> paramValues[p.name]?.toDoubleOrNull()?.let { put(p.name, it) }
                                else -> paramValues[p.name]?.takeIf { it.isNotBlank() }?.let { put(p.name, it) }
                            }
                        }
                    }.toString()

                    // ✅ MỚI: fallback label giống hệt logic trong ScheduleSkill.handleAdd() —
                    // để UI hiển thị nhất quán ngay cả trước khi round-trip qua DB.
                    val finalLabel = label.trim().ifBlank { "${plugin.id}.${action.name}" }

                    val schedule = ScheduleEntity(
                        id = UUID.randomUUID().toString(),
                        pluginId = plugin.id,
                        action = action.name,
                        params = paramsJson,
                        cron = finalCron,
                        intervalMinutes = finalInterval,
                        enabled = 1,
                        lastRunAt = 0,
                        createdAt = System.currentTimeMillis(),
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