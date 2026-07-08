package com.aichatvn.agent.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.viewmodels.DashboardViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val deviceNodes by viewModel.deviceNodes.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val executionMessage by viewModel.executionMessage.collectAsState()

    var selectedNode by remember { mutableStateOf<DeviceNode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Quản lý trạng thái Canvas vô cực (Zoom & Pan)
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Khoảng cách ô lưới để hút tọa độ khi kết thúc kéo thả (Snap-to-Grid)
    val snapGridSize = 20f
    fun snapToGrid(value: Float): Float {
        return (value / snapGridSize).roundToInt() * snapGridSize
    }

    // Đo kích thước màn hình để tính toán tỉ lệ co giãn cơ sở ban đầu
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val designWidth = 360f
    val baseScale = screenWidth.toFloat() / designWidth

    LaunchedEffect(executionMessage) {
        executionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearExecutionMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sơ đồ điều khiển thiết bị") },
                actions = {
                    IconButton(onClick = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Khôi phục thu phóng"
                        )
                    }
                    IconButton(onClick = {
                        viewModel.refreshDashboardNodes()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Làm mới"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Lớp vẽ mạng lưới nền (Grid Background Canvas)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridSpacing = 40.dp.toPx()
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                
                // Vẽ lưới dọc
                var x = panOffset.x % gridSpacing
                while (x < size.width) {
                    if (x >= 0) {
                        drawLine(
                            color = Color.LightGray.copy(alpha = 0.2f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = pathEffect
                        )
                    }
                    x += gridSpacing
                }

                // Vẽ lưới ngang
                var y = panOffset.y % gridSpacing
                while (y < size.height) {
                    if (y >= 0) {
                        drawLine(
                            color = Color.LightGray.copy(alpha = 0.2f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = pathEffect
                        )
                    }
                    y += gridSpacing
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3.0f)
                            panOffset += pan
                        }
                    }
            ) {
                // Lớp Canvas chịu tác động Zoom/Pan chứa toàn bộ cấu phần thiết bị và phân vùng phòng
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panOffset.x,
                            translationY = panOffset.y
                        )
                ) {
                    // Biểu tượng ngôi nhà trung tâm sơ đồ
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🏠", fontSize = 100.sp, modifier = Modifier.clip(CircleShape))
                        Text(
                            text = "AIChatVN Home",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 90.dp)
                        )
                    }

                    // 2. Vẽ phân vùng không gian các phòng mờ ảo (Room bounding boundary)
                    val rooms = deviceNodes
                        .filter { it.room.isNotBlank() && it.room != "Phòng chung" }
                        .groupBy { it.room }

                    rooms.forEach { (roomName, nodes) ->
                        if (nodes.isNotEmpty()) {
                            val minX = nodes.minOf { it.x } - 12f
                            val minY = nodes.minOf { it.y } - 12f
                            val maxX = nodes.maxOf { it.x } + 150f + 12f // 150dp là chiều rộng Card thiết kế
                            val maxY = nodes.maxOf { it.y } + 115f + 12f // 115dp là chiều cao Card thiết kế

                            val width = maxX - minX
                            val height = maxY - minY

                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(minX.roundToInt(), minY.roundToInt()) }
                                    .width(width.dp)
                                    .height(height.dp)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRoundRect(
                                        color = Color.Gray.copy(alpha = 0.25f),
                                        size = this.size,
                                        style = Stroke(
                                            width = 1.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                                        )
                                    )
                                }
                                Text(
                                    text = roomName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }

                    // 3. Hiển thị danh sách thiết bị và phân bổ cử chỉ Tap / Long-press Drag
                    deviceNodes.forEach { node ->
                        key(node.id) {
                            DraggableDeviceNodeItem(
                                node = node,
                                baseScale = baseScale,
                                zoomScale = zoomScale,
                                snapToGrid = ::snapToGrid,
                                onNodeClick = { selectedNode = it },
                                onUpdatePosition = { id, x, y -> viewModel.updateNodePosition(id, x, y) }
                            )
                        }
                    }
                }
            }

            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
        }

        // Tầng BottomSheet cấu hình và kích hoạt lệnh
        if (selectedNode != null) {
            val node = selectedNode!!
            ModalBottomSheet(
                onDismissRequest = { selectedNode = null },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(node.icon, fontSize = 36.sp)
                            Column {
                                Text(node.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Plugin ID: ${node.pluginId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Surface(
                            color = if (node.online) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                            contentColor = if (node.online) Color(0xFF2E7D32) else Color(0xFFC62828),
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (node.online) "ONLINE" else "OFFLINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Địa chỉ IP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(node.ip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("Pin / Nguồn điện", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (node.battery != null) "${node.battery}%" else "Nguồn trực tiếp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column {
                                Text("Mã định danh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(node.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Text("Hành động", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    if (node.supportedActions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            node.supportedActions.chunked(2).forEach { rowActions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowActions.forEach { action ->
                                        val uppercaseId = action.id.uppercase()
                                        val isPositive = uppercaseId in setOf("ON", "UNLOCK", "TAKEOFF", "PATROL")
                                        val isNegative = uppercaseId in setOf("OFF", "LOCK", "STOP", "LAND")

                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    try {
                                                        sheetState.hide()
                                                    } finally {
                                                        selectedNode = null
                                                    }
                                                }
                                                viewModel.sendDeviceAction(node, action, emptyMap())
                                            },
                                            colors = when {
                                                isPositive -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                                isNegative -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                                else -> ButtonDefaults.buttonColors()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(action.icon, fontSize = 16.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(action.title, maxLines = 1)
                                        }
                                    }
                                    if (rowActions.size < 2) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { 
                                coroutineScope.launch {
                                    try {
                                        sheetState.hide()
                                    } finally {
                                        selectedNode = null
                                    }
                                }
                                viewModel.sendDeviceAction(node, node.defaultAction, emptyMap())
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Chạy hành động mặc định")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Thành phần Composable đóng gói kéo thả sau khi nhấn giữ (After Long Press) để phân tách chạm click.
 */
@Composable
fun DraggableDeviceNodeItem(
    node: DeviceNode,
    baseScale: Float,
    zoomScale: Float,
    snapToGrid: (Float) -> Float,
    onNodeClick: (DeviceNode) -> Unit,
    onUpdatePosition: (String, Float, Float) -> Unit
) {
    var localPosition by remember(node.id) { mutableStateOf(Offset(node.x, node.y)) }
    var isDragging by remember(node.id) { mutableStateOf(false) }

    LaunchedEffect(node.x, node.y) {
        if (!isDragging) {
            localPosition = Offset(node.x, node.y)
        }
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (localPosition.x * baseScale).roundToInt(),
                    (localPosition.y * baseScale).roundToInt()
                )
            }
            // 1. Chạm nhanh (Short Tap) kích hoạt mở Bottom Sheet ngay lập tức kèm hiệu ứng Ripple
            .clickable {
                onNodeClick(node)
            }
            // 2. Nhấn giữ lâu (Long Press) kích hoạt chế độ kéo thả di chuyển tự do
            .pointerInput(node.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { 
                        isDragging = true 
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val nextX = localPosition.x + (dragAmount.x / (baseScale * zoomScale))
                        val nextY = localPosition.y + (dragAmount.y / (baseScale * zoomScale))
                        localPosition = Offset(nextX, nextY)
                    },
                    onDragEnd = {
                        isDragging = false
                        val snappedX = snapToGrid(localPosition.x)
                        val snappedY = snapToGrid(localPosition.y)
                        localPosition = Offset(snappedX, snappedY)
                        onUpdatePosition(node.id, snappedX, snappedY)
                    },
                    onDragCancel = {
                        isDragging = false
                        localPosition = Offset(node.x, node.y)
                    }
                )
            }
    ) {
        DeviceNodeCardWidget(node = node)
    }
}

/**
 * Thẻ con Card hiển thị chi tiết thiết bị với hiệu ứng sáng Neon mờ ảo khi đang bật hoạt động.
 */
@Composable
fun DeviceNodeCardWidget(
    node: DeviceNode,
    modifier: Modifier = Modifier
) {
    val isOnline = node.online
    val isActive = node.status.contains("bật", ignoreCase = true) || 
                   node.status.contains("hoạt động", ignoreCase = true)

    val transition = rememberInfiniteTransition(label = "glowTransition")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val activeThemeColor = when (node.type) {
        DeviceType.LIGHT -> Color(0xFFFFD54F)
        DeviceType.CAMERA -> Color(0xFF4FC3F7)
        else -> Color(0xFF81C784)
    }

    val cardBorder = when {
        !isOnline -> BorderStroke(1.5.dp, Color.Red.copy(alpha = 0.35f))
        isActive -> BorderStroke(2.dp, activeThemeColor)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = modifier
            .width(150.dp)
            .height(115.dp)
            .drawBehind {
                if (isOnline && isActive) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            asFrameworkPaint().apply {
                                color = activeThemeColor.copy(alpha = glowAlpha).toArgb()
                                setShadowLayer(
                                    14.dp.toPx(),
                                    0f,
                                    0f,
                                    activeThemeColor.copy(alpha = glowAlpha).toArgb()
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
        border = cardBorder,
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline) {
                if (isActive) activeThemeColor.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOnline) {
                                if (isActive) activeThemeColor.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.LightGray.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isOnline) node.icon else "🔌",
                        fontSize = 15.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color.Green else Color.Red)
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = node.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = node.status.ifBlank { if (isOnline) "Bình thường" else "Ngoại tuyến" },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant else Color.Red.copy(alpha = 0.7f)
                )
            }
        }
    }
}