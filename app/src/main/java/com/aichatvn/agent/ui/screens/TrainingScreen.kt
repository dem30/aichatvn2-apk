package com.aichatvn.agent.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.core.DiagnosticInfo // Import từ core
import com.aichatvn.agent.core.DiagnosticTier // Import từ core
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
    val diagnosticInfo by viewModel.diagnosticInfo.collectAsState()
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
                placeholder = { Text("Nhập thử nghiệm câu lệnh thiết bị...") },
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
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchQuery.isNotBlank() && diagnosticInfo != null) {
                            item {
                                AgentKernelDiagnosticsPanel(diagnosticInfo!!)
                            }
                        }

                        if (displayList.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "Không có kết quả nào đạt ngưỡng cấu hình tĩnh (Kiểm tra luồng LLM Tầng 5 ở Panel phía trên)" else "Chưa có dữ liệu huấn luyện",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
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
                                    onDelete = { viewModel.deleteQA(qa.id, "default_user") }
                                )
                            }
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
fun AgentKernelDiagnosticsPanel(info: DiagnosticInfo) {
    var isExpanded by remember { mutableStateOf(true) }
    val rotationState by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Analytics,
                        contentDescription = "Diagnostics",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LUỒNG KIỂM TRA 5 TẦNG AGENT KERNEL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotationState)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    
                    // Hiển thị kết quả thực thi live outcome
                    if (info.executionOutcome != null) {
                        val outcome = info.executionOutcome
                        val isSuccess = outcome.startsWith("✅")
                        val isWarning = outcome.startsWith("⚠️")
                        val isFailure = outcome.startsWith("❌")
                        
                        val containerColor = when {
                            isSuccess -> Color(0xFF2E7D32).copy(alpha = 0.08f)
                            isWarning -> Color(0xFFFFB300).copy(alpha = 0.08f)
                            isFailure -> Color(0xFFC62828).copy(alpha = 0.08f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                        val borderColor = when {
                            isSuccess -> Color(0xFF2E7D32).copy(alpha = 0.3f)
                            isWarning -> Color(0xFFFFB300).copy(alpha = 0.3f)
                            isFailure -> Color(0xFFC62828).copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        val titleColor = when {
                            isSuccess -> Color(0xFF2E7D32)
                            isWarning -> Color(0xFFE65100)
                            isFailure -> Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .background(containerColor, RoundedCornerShape(8.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "KẾT QUẢ THỰC THI THỰC TẾ (LIVE OUTCOME):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = titleColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = outcome,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Trạng thái cấu hình hiện tại
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("Ngưỡng Lọc Tầng 2 (Intent)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${info.intentThreshold}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("Ngưỡng Lọc Tầng 3 (Alias)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${info.aliasThreshold}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // PHẦN 1: DÒNG THỜI GIAN LÀM VIỆC CỦA 5 TẦNG PIPELINE
                    Text(
                        "1. Sơ đồ xử lý tuần tự 5 tầng:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        info.tiers.forEach { tier ->
                            TierRow(tier)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // PHẦN 2: CHI TIẾT DỮ LIỆU ĐỐI KHỚP DƯỚI TẦNG THẤP
                    Text(
                        "2. Các thực thể trích xuất tốt nhất (Best Aliases):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    
                    if (info.bestAliasMatches.isEmpty()) {
                        Text(
                            "Không nhận diện được từ khóa hoặc thực thể nào khớp tĩnh.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            info.bestAliasMatches.forEach { (category, pair) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF2E7D32).copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                        .border(0.5.dp, Color(0xFF2E7D32).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Danh mục (semanticType): $category",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Từ khóa: \"${pair.first.question}\" → Mã ID: \"${pair.first.answer}\"",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = String.format("%.2f", pair.second),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "3. Điểm khớp mẫu (Tĩnh & Heuristic):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    var showRawScores by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showRawScores = !showRawScores },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (showRawScores) "Ẩn chi tiết" else "Xem chi tiết điểm số đối khớp...", fontSize = 11.sp)
                    }

                    AnimatedVisibility(visible = showRawScores) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Text("Ý định (Intent Match list):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (info.intentMatches.isEmpty()) {
                                Text("Trống", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                info.intentMatches.take(3).forEach { (qa, score) ->
                                    val isPassed = score >= info.intentThreshold
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Q: \"${qa.question}\" -> A: \"${qa.answer}\"",
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            color = if (isPassed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            String.format("%.2f [%s]", score, if (isPassed) "ĐẠT" else "LOẠI"),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPassed) Color(0xFF2E7D32) else Color(0xFFC62828)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Thực thể thô (Alias Match list):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (info.aliasMatches.isEmpty()) {
                                Text("Trống", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                info.aliasMatches.take(3).forEach { (qa, score) ->
                                    val isPassed = score >= info.aliasThreshold
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "\"${qa.question}\" (${qa.category}) -> \"${qa.answer}\"",
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            color = if (isPassed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            String.format("%.2f [%s]", score, if (isPassed) "ĐẠT" else "LOẠI"),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPassed) Color(0xFF2E7D32) else Color(0xFFC62828)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TierRow(tier: DiagnosticTier) {
    val isMatched = tier.matched
    val activeBgColor = if (isMatched) Color(0xFF2E7D32).copy(alpha = 0.08f) else Color.Transparent
    val borderStrokeWidth = if (isMatched) 1.5.dp else 0.5.dp
    val borderColor = if (isMatched) Color(0xFF2E7D32).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = activeBgColor),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(borderStrokeWidth, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = if (isMatched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${tier.tierNum}",
                    color = if (isMatched) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tier.tierName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isMatched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = (if (isMatched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = if (isMatched) "KÍCH HOẠT" else "BỎ QUA",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMatched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                if (tier.score > 0) {
                    Text(
                        text = "Score: ${String.format("%.2f", tier.score)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isMatched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = tier.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.5.sp,
                    color = if (isMatched) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (qa.type == "intent") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                qa.type.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (qa.type == "intent") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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

