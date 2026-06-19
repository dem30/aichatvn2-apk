package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.ui.viewmodels.ScheduleViewModel
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
                items(schedules) { schedule ->
                    ScheduleCard(
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
                Text(
                    "${schedule.pluginId}.${schedule.action}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    when {
                        schedule.cron.isNotEmpty() -> "⏰ $schedule.cron"
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
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var pluginId by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("") }
    var intervalMinutes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm lịch trình mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pluginId,
                    onValueChange = { pluginId = it },
                    label = { Text("Plugin ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text("Action") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
                    label = { Text("Khoảng cách (phút)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val interval = intervalMinutes.toIntOrNull() ?: 0
                    val schedule = ScheduleEntity(
                        id = UUID.randomUUID().toString(),
                        pluginId = pluginId,
                        action = action,
                        params = "{}",
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