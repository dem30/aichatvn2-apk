package com.aichatvn.agent.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.ui.viewmodels.DiagnosticInfo
import com.aichatvn.agent.ui.viewmodels.DiagnosticTier
import com.aichatvn.agent.ui.viewmodels.TrainingViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(
    navController: NavController,
    viewModel: TrainingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val qaList by viewModel.qaList.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val diagnosticInfo by viewModel.diagnosticInfo.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingQA by remember { mutableStateOf<QAEntity?>(null) }

    // Launcher cho nạp file JSON/CSV
    val jsonPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.importQAFromUri(context, it) }
    }
    val csvPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.importQAFromCsvUri(context, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Huấn luyện Q&A") },
                actions = {
                    IconButton(onClick = { viewModel.exportQAToJson(context) }) {
                        Icon(Icons.Default.Download, contentDescription = "Xuất dữ liệu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Thêm mới Q&A")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thanh Tìm kiếm & Giả lập chẩn đoán
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it.isNotBlank()) {
                        viewModel.searchQAs(it)
                    } else {
                        viewModel.clearSearch()
                    }
                },
                label = { Text("Tìm kiếm hoặc Giả lập câu lệnh...") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = ""; viewModel.clearSearch() }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true
            )

            // Hiển thị kết quả Import/Export
            if (importResult != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(importResult!!, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { viewModel.clearImportResult() }) { Icon(Icons.Default.Close, null) }
                    }
                }
            }
            if (exportResult != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(exportResult!!, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { viewModel.clearExportResult() }) { Icon(Icons.Default.Close, null) }
                    }
                }
            }

            // ✅ CHẨN ĐOÁN THÔNG MINH: Vẽ sơ đồ giả lập 5 Tầng trực quan khớp hoàn chỉnh với Kernel
            diagnosticInfo?.let { info ->
                DiagnosticInfoSection(diagnosticInfo = info)
            }

            // Danh sách các cặp QA hiện hành
            val displayList = if (searchQuery.isNotBlank()) searchResults else qaList
            
            Text(
                text = if (searchQuery.isNotBlank()) "Kết quả tìm kiếm (${displayList.size})" else "Tất cả bản ghi huấn luyện",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (displayList.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Chưa có bản ghi huấn luyện nào.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayList) { qa ->
                        QARow(
                            qa = qa,
                             onEdit = { editingQA = it },
onDelete = { viewModel.deleteQA(qa.id, "default_user") }
                        )
                    }

                    // Tải thêm trang tiếp theo nếu đang hiển thị danh sách toàn bộ
                    if (searchQuery.isBlank() && hasMore) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                Button(onClick = { viewModel.loadMoreQAs() }, enabled = !isLoading) {
                                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    else Text("Tải thêm bản ghi")
                                }
                            }
                        }
                    }
                }
            }

            // Các nút tiện ích nhập CSV/JSON ở chân màn hình
            if (searchQuery.isBlank()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { jsonPickerLauncher.launch("application/json") }, modifier = Modifier.weight(1f)) {
                        Text("📥 Nhập JSON", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { csvPickerLauncher.launch("text/comma-separated-values") }, modifier = Modifier.weight(1f)) {
                        Text("📥 Nhập CSV", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    // Dialog Thêm mới Q&A
    if (showAddDialog) {
        AddEditQADialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { q, a, t, c ->
                viewModel.addQA(q, a, t, c)
                showAddDialog = false
            }
        )
    }

    // Dialog Chỉnh sửa Q&A
    if (editingQA != null) {
        AddEditQADialog(
            qa = editingQA,
            onDismiss = { editingQA = null },
            onConfirm = { q, a, t, c ->
                viewModel.updateQA(editingQA!!.id, q, a, t, c)
                editingQA = null
            }
        )
    }
}

// ✅ THIẾT KẾ MỚI: Khối hiển thị chẩn đoán giả lập 5 Tầng giao diện chuẩn hóa trực quan [1]
@Composable
private fun DiagnosticInfoSection(diagnosticInfo: DiagnosticInfo) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "🔍 Phân Tích Giả Lập Điều Phối (5 Tầng Core)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                
                // Vẽ tuần tự 5 tầng
                diagnosticInfo.tiers.forEach { tier ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (tier.matched) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tier.tierName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tier.matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (tier.matched) "🟢 ĐẠT" else "⚪ BỎ QUA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tier.matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (tier.score > 0) {
                                Text(
                                    text = "Độ tương đồng: ${String.format("%.2f", tier.score)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tier.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (diagnosticInfo.resolvedIntents.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("🎯 Ý định tìm thấy trong DB:", style = MaterialTheme.typography.labelMedium)
                    diagnosticInfo.resolvedIntents.forEach { intent ->
                        Text("• $intent", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (diagnosticInfo.resolvedAliases.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("📦 Aliases bóc tách:", style = MaterialTheme.typography.labelMedium)
                    diagnosticInfo.resolvedAliases.forEach { (category, answer) ->
                        Text("• $category -> $answer", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(
                    text = "Bấm để xem phân tích chi tiết cách Kernel xử lý câu lệnh này hằng ngày.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun QARow(
    qa: QAEntity,
    onEdit: (QAEntity) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = qa.type.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Danh mục: ${qa.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Text("Q: ${qa.question}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("A: ${qa.answer}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            IconButton(onClick = { onEdit(qa) }) {
                Icon(Icons.Default.Edit, contentDescription = "Sửa", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddEditQADialog(
    qa: QAEntity? = null,
    onDismiss: () -> Unit,
    onConfirm: (question: String, answer: String, type: String, category: String) -> Unit
) {
    var question by remember { mutableStateOf(qa?.question ?: "") }
    var answer by remember { mutableStateOf(qa?.answer ?: "") }
    var type by remember { mutableStateOf(qa?.type ?: "alias") }
    var category by remember { mutableStateOf(qa?.category ?: "general") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (qa == null) "Thêm mới Q&A" else "Cập nhật Q&A") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text("Câu hỏi (Trigger)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("Câu trả lời (Answer / Schema)") }, modifier = Modifier.fillMaxWidth())
                
                Text("Loại ghi chép (Type)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == "alias", onClick = { type = "alias" })
                        Text("Alias (Dịch nghĩa)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == "intent", onClick = { type = "intent" })
                        Text("Intent (Lệnh)")
                    }
                }

                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Danh mục (Ví dụ: camera, light, general)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(question, answer, type, category) },
                enabled = question.isNotBlank() && answer.isNotBlank()
            ) { Text("Xác nhận") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy bỏ") }
        }
    )
}