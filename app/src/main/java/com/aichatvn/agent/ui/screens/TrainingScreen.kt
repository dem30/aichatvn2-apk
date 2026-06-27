package com.aichatvn.agent.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.ui.viewmodels.TrainingViewModel

val PRESET_CATEGORIES = listOf("chat", "email", "device", "camera", "faq", "general", "alert")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(
    navController: NavController,
    viewModel: TrainingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val qaList by viewModel.qaList.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val importResult by viewModel.importResult.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingQA by remember { mutableStateOf<QAEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedQAs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val displayList = if (searchQuery.isNotBlank()) searchResults else qaList

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importQAFromUri(context, it) }
    }

    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importQAFromCsvUri(context, it) }
    }

    LaunchedEffect(exportResult) {
        exportResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearExportResult()
        }
    }

    LaunchedEffect(importResult) {
        importResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearImportResult()
        }
    }

    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= displayList.size - 3 && hasMore && !isLoading && searchQuery.isBlank()) {
            viewModel.loadMoreQAs()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Huấn luyện Q&A (${qaList.size})") },
                actions = {
                    IconButton(onClick = { viewModel.exportQAToJson(context) }) {
                        Icon(Icons.Default.Download, contentDescription = "Export JSON")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Xóa tất cả")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Thêm Q&A")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it.isNotBlank()) viewModel.searchQAs(it) else viewModel.clearSearch()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Tìm kiếm câu hỏi...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = ""; viewModel.clearSearch() }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { jsonPickerLauncher.launch("application/json") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import JSON")
                }
                OutlinedButton(
                    onClick = { csvPickerLauncher.launch("text/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import CSV")
                }
            }

            if (selectedQAs.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Đã chọn ${selectedQAs.size}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row {
                            TextButton(onClick = { selectedQAs = emptySet() }) {
                                Text("Bỏ chọn")
                            }
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Xoá")
                            }
                        }
                    }
                }
            }

            when {
                isLoading && displayList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                displayList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "Không tìm thấy kết quả" else "Chưa có dữ liệu huấn luyện",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayList, key = { it.id }) { qa ->
                            QACard(
                                qa = qa,
                                isSelected = selectedQAs.contains(qa.id),
                                onSelect = {
                                    selectedQAs = if (selectedQAs.contains(qa.id))
                                        selectedQAs - qa.id
                                    else
                                        selectedQAs + qa.id
                                },
                                onEdit = { editingQA = qa },
                                onDelete = { viewModel.deleteQA(qa.id) }
                            )
                        }

                        if (hasMore && searchQuery.isBlank() && isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Xác nhận xóa") },
            text = {
                Text(
                    if (selectedQAs.isNotEmpty())
                        "Xóa ${selectedQAs.size} mục đã chọn?"
                    else
                        "Xóa tất cả Q&A? Hành động này không thể hoàn tác!"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedQAs.isNotEmpty()) {
                            viewModel.batchDeleteQAs(selectedQAs.toList())
                            selectedQAs = emptySet()
                        } else {
                            viewModel.deleteAllQAs()
                        }
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Hủy") }
            }
        )
    }

    if (showAddDialog || editingQA != null) {
        QADialog(
            existing = editingQA,
            onDismiss = { showAddDialog = false; editingQA = null },
            onSave = { q, a, cat, t ->
                if (editingQA != null) viewModel.updateQA(editingQA!!.id, q, a, t, cat)
                else viewModel.addQA(q, a, t, cat)
                showAddDialog = false; editingQA = null
            }
        )
    }
}

@Composable
fun QACard(
    qa: QAEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
                modifier = Modifier.padding(start = 4.dp, top = 12.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            qa.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Row {
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete, null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Text("Q: ${qa.question}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A: ${qa.answer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QADialog(
    existing: QAEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var question by remember { mutableStateOf(existing?.question ?: "") }
    var answer by remember { mutableStateOf(existing?.answer ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "chat") }
    var type by remember { mutableStateOf(existing?.type ?: "alias") }
    var expanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Thêm Q&A" else "Sửa Q&A") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Câu hỏi") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("Câu trả lời") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Danh mục (semanticType)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        PRESET_CATEGORIES.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset) },
                                onClick = { category = preset; expanded = false }
                            )
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Loại QA") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        listOf(
                            "alias" to "Alias — tra cứu giá trị (tên, email...)",
                            "intent" to "Intent — lệnh điều khiển (JSON)"
                        ).forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { type = t; typeExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (question.isNotBlank() && answer.isNotBlank()) onSave(question, answer, category, type)
                }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}