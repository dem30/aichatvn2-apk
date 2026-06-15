package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.LogViewModel
import com.aichatvn.agent.utils.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    navController: NavController,
    viewModel: LogViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val logs by viewModel.logs.collectAsState()
    val filterLevel by viewModel.filterLevel.collectAsState()
    val filteredLogs = remember(logs, filterLevel) {
        logs.filter { it.level.ordinal >= filterLevel.ordinal }
    }
    val listState = rememberLazyListState()
    var expandedFilterMenu by remember { mutableStateOf(false) }
    var selectedLogEntry by remember { mutableStateOf<Logger.LogEntry?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // Auto scroll to top when new log arrives (logs mới được thêm vào đầu danh sách)
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Logs") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Filter button
                    Box {
                        IconButton(onClick = { expandedFilterMenu = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter")
                        }

                        DropdownMenu(
                            expanded = expandedFilterMenu,
                            onDismissRequest = { expandedFilterMenu = false }
                        ) {
                            Logger.LogLevel.values().forEach { level ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(getLevelIcon(level))
                                            Spacer(Modifier.width(8.dp))
                                            Text(level.name)
                                            if (filterLevel == level) {
                                                Spacer(Modifier.weight(1f))
                                                Icon(Icons.Default.Check, null)
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setFilterLevel(level)
                                        expandedFilterMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Export button
                    IconButton(onClick = {
                        try {
                            val file = viewModel.exportLogs()
                            
                            exportMessage = "Đã lưu: ${file.absolutePath}"
                        } catch (e: Exception) {
                            exportMessage = "Lỗi xuất log: ${e.message}"
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Export")
                    }

                    // Clear button
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
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
            // Filter info bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 Hiển thị: ${filterLevel.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Tổng: ${filteredLogs.size} logs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            exportMessage?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { exportMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng")
                        }
                    }
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Không có log nào",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogEntryCard(
                            entry = log,
                            onClick = { selectedLogEntry = log }
                        )
                    }
                }
            }
        }
    }

    // Detail dialog
    val entry = selectedLogEntry
    if (entry != null) {
        AlertDialog(
            onDismissRequest = { selectedLogEntry = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(getLevelIcon(entry.level))
                    Spacer(Modifier.width(8.dp))
                    Text("${entry.level.name} - ${entry.tag}")
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (entry.throwable != null) {
                        Divider()
                        Text(
                            text = "Stack trace:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = entry.throwable.stackTraceToString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLogEntry = null }) {
                    Text("Đóng")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val text = buildString {
                            append("${entry.level.name} [${entry.tag}] ${entry.message}")
                            entry.throwable?.let {
                                append("\n")
                                append(it.stackTraceToString())
                            }
                        }
                        clipboardManager.setText(AnnotatedString(text))
                    }
                ) {
                    Text("Copy")
                }
            }
        )
    }
}

@Composable
fun LogEntryCard(
    entry: Logger.LogEntry,
    onClick: () -> Unit
) {
    val backgroundColor = when (entry.level) {
        Logger.LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        Logger.LogLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        Logger.LogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = getLevelIcon(entry.level),
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 8.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun getLevelIcon(level: Logger.LogLevel): String {
    return when (level) {
        Logger.LogLevel.VERBOSE -> "📝"
        Logger.LogLevel.DEBUG -> "🔍"
        Logger.LogLevel.INFO -> "ℹ️"
        Logger.LogLevel.WARNING -> "⚠️"
        Logger.LogLevel.ERROR -> "❌"
    }
}

fun formatTimestamp(timestamp: Long): String {
    val format = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
    return format.format(java.util.Date(timestamp))
}
