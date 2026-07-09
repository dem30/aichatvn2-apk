package com.aichatvn.agent.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.core.DiagnosticInfo
import com.aichatvn.agent.core.TraceNode
import com.aichatvn.agent.ui.viewmodels.DiagnosticsViewModel

/**
 * Màn hình trực quan hoá call graph THẬT của AgentKernel (không phải sơ đồ tĩnh mô phỏng).
 * Dữ liệu lấy từ DiagnosticsViewModel.pipelineTrace, vốn được sinh ra bởi
 * AgentKernel.explainDeviceCommand() — mỗi TraceNode phản ánh đúng 1 quyết định/hàm
 * thật đã chạy, theo đúng thứ tự thời gian, kèm CodeReference liên kết động tới
 * hằng số/luật thật của hệ thống (không hardcode chuỗi mô tả riêng).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineGraphScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val pipelineTrace by viewModel.pipelineTrace.collectAsState()
    val isExplaining by viewModel.isExplaining.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedTrace by remember { mutableStateOf<TraceNode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pipeline AI (Node-Graph)") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        query = ""
                        viewModel.clearPipelineTrace()
                    }) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Xoá kết quả")
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
            // ── Ô nhập câu lệnh thử ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Nhập câu lệnh thử, vd: lên lịch bật camera 7h sáng") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.explainCommand(query.trim()) },
                    enabled = query.isNotBlank() && !isExplaining
                ) {
                    if (isExplaining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── Hiển thị các lựa chọn nhanh nếu hệ thống đang cần hỏi thêm thông tin (NeedMoreInfo) ──
            pipelineTrace?.let { info ->
                if (info.options.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "💬 Hệ thống hỏi: ${info.askedQuestion ?: "Vui lòng chọn tiếp:"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            
                            // Hiển thị danh sách Suggestion Chips để chọn nhanh thông tin phản hồi
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                info.options.keys.forEach { optionKey ->
                                    SuggestionChip(
                                        onClick = {
                                            query = optionKey // Điền tự động giá trị lựa chọn vào ô nhập
                                            viewModel.explainCommand(optionKey) // Tiến hành kích hoạt kiểm tra tiếp lượt sau
                                        },
                                        label = { Text("Số $optionKey") }
                                    )
                                }
                                
                                // Nút Reset để xóa nhanh trạng thái dở dang và khởi động lại luồng chẩn đoán
                                SuggestionChip(
                                    onClick = {
                                        query = ""
                                        viewModel.resetPendingSession()
                                    },
                                    label = { Text("Reset luồng") }
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            val info = pipelineTrace
            when {
                isExplaining && info == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                info == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nhập 1 câu lệnh rồi bấm ▶ để xem call graph thật của AgentKernel — " +
                                "mỗi node dưới đây là 1 hàm/1 quyết định thật đã chạy, không phải mô phỏng.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        info.executionOutcome?.let { outcome ->
                            item {
                                OutcomeBanner(outcome)
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        if (info.traces.isEmpty()) {
                            item {
                                Text(
                                    "Câu lệnh này dừng lại rất sớm trong pipeline (vd. bị bypass do phủ định, " +
                                        "hoặc rơi thẳng vào Tầng 5 LLM) nên chưa có vết hàm nào được ghi lại.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        itemsIndexed(info.traces, key = { i, t -> "${t.nodeId}_$i" }) { index, trace ->
                            TraceNodeCard(
                                index = index + 1,
                                trace = trace,
                                onClick = { selectedTrace = trace }
                            )
                            if (index < info.traces.size - 1) {
                                ConnectorArrow(matched = trace.matched)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTrace?.let { trace ->
        ModalBottomSheet(
            onDismissRequest = { selectedTrace = null },
            sheetState = sheetState
        ) {
            TraceDetailSheet(trace)
        }
    }
}

@Composable
private fun OutcomeBanner(outcome: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            outcome,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TraceNodeCard(
    index: Int,
    trace: TraceNode,
    onClick: () -> Unit
) {
    val themeColor = if (trace.matched) Color(0xFF4CAF50) else Color(0xFF9E9E9E)

    val transition = rememberInfiniteTransition(label = "nodeGlow")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .drawBehind {
                if (trace.matched) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            asFrameworkPaint().apply {
                                color = themeColor.copy(alpha = glowAlpha).toArgb()
                                setShadowLayer(
                                    12.dp.toPx(), 0f, 0f,
                                    themeColor.copy(alpha = glowAlpha).toArgb()
                                )
                            }
                        }
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            12.dp.toPx(), 12.dp.toPx(), paint
                        )
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (trace.matched) 2.dp else 1.dp,
            themeColor.copy(alpha = if (trace.matched) 1f else 0.4f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (trace.matched)
                themeColor.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = themeColor.copy(alpha = 0.2f)) {
                        Text(
                            "$index",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        trace.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (trace.matched) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                trace.nodeId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "➔ ${trace.output}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConnectorArrow(matched: Boolean) {
    val color = if (matched) Color(0xFF4CAF50) else Color(0xFF9E9E9E).copy(alpha = 0.5f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val centerX = size.width / 2
        val arrowSize = 8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(centerX, 0f),
            end = Offset(centerX, size.height - arrowSize),
            strokeWidth = 3.dp.toPx()
        )
        val path = Path().apply {
            moveTo(centerX - arrowSize / 2, size.height - arrowSize)
            lineTo(centerX, size.height)
            lineTo(centerX + arrowSize / 2, size.height - arrowSize)
        }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun TraceDetailSheet(trace: TraceNode) {
    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(trace.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            trace.nodeId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        DetailRow("📄 File", trace.codeRef.fileName)
        DetailRow("⚙️ Hàm", trace.codeRef.functionName)
        DetailRow("📥 Đầu vào", trace.input)
        DetailRow("📤 Kết quả", trace.output)
        DetailRow("Trạng thái", if (trace.matched) "✅ Thành công / Khớp" else "⏭️ Không khớp / Bỏ qua")

        Spacer(Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("🔧 Cấu hình cứng (đọc trực tiếp từ code sống)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(trace.codeRef.hardcodedRules, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("📖 Logic nghiệp vụ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(trace.codeRef.businessLogic, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}