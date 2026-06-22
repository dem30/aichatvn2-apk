package com.aichatvn.agent.ui.screens

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
                // ✅ Hiện "#1 · camera.scan" thay vì chỉ "camera.scan"
                Text(
                    "#$index · ${schedule.pluginId}.${schedule.action}",
                    style = MaterialTheme.typography.titleSmall
                )
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

    var cron by remember { mutableStateOf("") }
    var intervalMinutes by remember { mutableStateOf("") }

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

    val canSave = selectedPlugin != null &&
        selectedAction != null &&
        requiredParamsFilled &&
        (cron.isNotBlank() || (intervalMinutes.toIntOrNull() ?: 0) > 0)

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
                    Text("Khi nào chạy (chọn 1 trong 2)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = cron,
                        onValueChange = { cron = it },
                        label = { Text("Cron (VD: 0 8 * * *)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = intervalMinutes,
                        onValueChange = { intervalMinutes = it },
                        label = { Text("Hoặc khoảng cách (phút)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val plugin = selectedPlugin ?: return@TextButton
                    val action = selectedAction ?: return@TextButton
                    val interval = intervalMinutes.toIntOrNull() ?: 0

                    val paramsJson = JSONObject().apply {
                        action.parameters.forEach { p ->
                            when (p.type) {
                                "boolean" -> put(p.name, paramBooleans[p.name] ?: false)
                                "number" -> paramValues[p.name]?.toDoubleOrNull()?.let { put(p.name, it) }
                                else -> paramValues[p.name]?.takeIf { it.isNotBlank() }?.let { put(p.name, it) }
                            }
                        }
                    }.toString()

                    val schedule = ScheduleEntity(
                        id = UUID.randomUUID().toString(),
                        pluginId = plugin.id,
                        action = action.name,
                        params = paramsJson,
                        cron = cron,
                        intervalMinutes = interval,
                        enabled = 1,
                        lastRunAt = 0,
                        createdAt = System.currentTimeMillis()
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
