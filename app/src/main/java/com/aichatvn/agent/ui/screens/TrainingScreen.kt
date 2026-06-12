package com.aichatvn.agent.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.ui.viewmodels.TrainingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(
    navController: NavController,
    viewModel: TrainingViewModel = hiltViewModel()
) {
    val qaList by viewModel.qaList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingQA by remember { mutableStateOf<QAEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val displayList = if (searchQuery.isBlank()) qaList
    else qaList.filter {
        it.question.contains(searchQuery, ignoreCase = true) ||
        it.answer.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Huấn luyện Q&A") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Thêm Q&A")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Tìm kiếm câu hỏi...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (displayList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chưa có dữ liệu huấn luyện", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayList, key = { it.id }) { qa ->
                        QACard(qa = qa, onEdit = { editingQA = qa }, onDelete = { viewModel.deleteQA(qa.id) })
                    }
                }
            }
        }
    }

    if (showAddDialog || editingQA != null) {
        QADialog(
            existing = editingQA,
            onDismiss = { showAddDialog = false; editingQA = null },
            onSave = { q, a, cat ->
                if (editingQA != null) viewModel.updateQA(editingQA!!.id, q, a, cat)
                else viewModel.addQA(q, a, cat)
                showAddDialog = false; editingQA = null
            }
        )
    }
}

@Composable
fun QACard(qa: QAEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(qa.category, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Q: ${qa.question}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("A: ${qa.answer}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QADialog(existing: QAEntity?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var question by remember { mutableStateOf(existing?.question ?: "") }
    var answer by remember { mutableStateOf(existing?.answer ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "chat") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Thêm Q&A" else "Sửa Q&A") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = question, onValueChange = { question = it },
                    label = { Text("Câu hỏi") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = answer, onValueChange = { answer = it },
                    label = { Text("Câu trả lời") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(value = category, onValueChange = { category = it },
                    label = { Text("Danh mục") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (question.isNotBlank() && answer.isNotBlank()) onSave(question, answer, category) }) {
                Text("Lưu")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}
