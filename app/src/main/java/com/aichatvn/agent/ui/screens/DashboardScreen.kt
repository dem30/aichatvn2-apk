package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private fun saveFloorplanImageToInternalStorage(context: android.content.Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "floorplan")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "floorplan_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val deviceNodes by viewModel.deviceNodes.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val executionMessage by viewModel.executionMessage.collectAsState()
    val floorplanPath by viewModel.floorplanPath.collectAsState()
    val floorplanScale by viewModel.floorplanScale.collectAsState()
    
    // ✅ ĐÃ THÊM: Theo dõi danh sách đề xuất thói quen tự học của AI
    val aiRecommendations by viewModel.aiRecommendations.collectAsState()

    var selectedNode by remember { mutableStateOf<DeviceNode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var floorplanBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(floorplanPath) {
        floorplanBitmap = floorplanPath?.let { path ->
            withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    val pickFloorplanImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    saveFloorplanImageToInternalStorage(context, uri)
                }
                if (savedPath != null) {
                    viewModel.setFloorplanPath(savedPath)
                } else {
                    snackbarHostState.showSnackbar("❌ Không thể lưu ảnh sơ đồ nhà, thử lại")
                }
            }
        }
    }
    var showFloorplanMenu by remember { mutableStateOf(false) }
    var showFloorplanScaleDialog by remember { mutableStateOf(false) }

    // Quản lý trạng thái Canvas vô cực (Zoom & Pan)
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val snapGridSize = 20f
    fun snapToGrid(value: Float): Float {
        return (value / snapGridSize).roundToInt() * snapGridSize
    }

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

    if (showFloorplanScaleDialog) {
        var tempScale by remember { mutableStateOf(floorplanScale) }
        AlertDialog(
            onDismissRequest = { showFloorplanScaleDialog = false },
            title = { Text("Kích thước sơ đồ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Điều chỉnh tỷ lệ ảnh nền cho phù hợp với số lượng thiết bị:")
                    Text(
                        text = "Tỷ lệ hiện tại: ${"%.1f".format(tempScale)}x",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = tempScale,
                        onValueChange = { tempScale = it },
                        valueRange = 0.5f..4.0f,
                        steps = 35
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setFloorplanScale(tempScale)
                    showFloorplanScaleDialog = false
                }) {
                    Text("Lưu")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFloorplanScaleDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sơ đồ thiết bị") },
                actions = {
                    Box {
                        IconButton(onClick = {
                            if (floorplanPath == null) {
                                pickFloorplanImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } else {
                                showFloorplanMenu = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "Tải ảnh sơ đồ nhà"
                            )
                        }
                        DropdownMenu(
                            expanded = showFloorplanMenu,
                            onDismissRequest = { showFloorplanMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Đổi ảnh sơ đồ nhà") },
                                onClick = {
                                    showFloorplanMenu = false
                                    pickFloorplanImage.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Điều chỉnh tỷ lệ sơ đồ") },
                                onClick = {
                                    showFloorplanMenu = false
                                    showFloorplanScaleDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá ảnh sơ đồ nhà") },
                                onClick = {
                                    showFloorplanMenu = false
                                    viewModel.clearFloorplanPath()
                                }
                            )
                        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ✅ ĐÃ THÊM: Hộp thông báo gợi ý tự động hóa thông minh (Proactive Suggestions)
            if (aiRecommendations.isNotEmpty()) {
                val recommendation = aiRecommendations.first()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 28.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Đề xuất tự động hóa thông minh",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = recommendation.answer,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { viewModel.approvePattern(recommendation) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Đồng ý (OK)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.ignorePattern(recommendation) },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Bỏ qua", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Sử dụng BoxWithConstraints để nhận chính xác kích thước vùng Canvas hiển thị
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // ✅ ĐÃ SỬA: Box chiếm không gian còn lại bên dưới thẻ Card đề xuất
            ) {
                val viewportWidth = maxWidth.value
                val viewportHeight = maxHeight.value

                // Lớp vẽ mạng lưới nền (Grid Background Canvas)
                val gridAlpha = if (floorplanBitmap != null) 0.06f else 0.2f
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridSpacing = 40.dp.toPx()
                    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                    
                    var x = panOffset.x % gridSpacing
                    while (x < size.width) {
                        if (x >= 0) {
                            drawLine(
                                color = Color.LightGray.copy(alpha = gridAlpha),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = pathEffect
                            )
                        }
                        x += gridSpacing
                    }

                    var y = panOffset.y % gridSpacing
                    while (y < size.height) {
                        if (y >= 0) {
                            drawLine(
                                color = Color.LightGray.copy(alpha = gridAlpha),
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
                    // Lớp Canvas chịu tác động Zoom/Pan chứa toàn bộ cấu phần thiết bị và sơ đồ
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
                        // Ảnh sơ đồ nhà nền (được scale động dựa trên cấu hình floorplanScale)
                        floorplanBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "Sơ đồ nhà",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .offset { IntOffset(0, 0) }
                                    .requiredWidth(((FLOORPLAN_DESIGN_WIDTH * floorplanScale) * baseScale).dp)
                                    .requiredHeight(((FLOORPLAN_DESIGN_HEIGHT * floorplanScale) * baseScale).dp)
                                    .alpha(0.9f)
                            )
                        }

                        if (floorplanBitmap == null) {
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
                        }

                        // Vẽ phân vùng các phòng
                        val rooms = deviceNodes
                            .filter { it.room.isNotBlank() && it.room != "Phòng chung" }
                            .groupBy { it.room }

                        rooms.forEach { (roomName, nodes) ->
                            if (nodes.isNotEmpty()) {
                                val minX = nodes.minOf { it.x } - 12f
                                val minY = nodes.minOf { it.y } - 12f
                                val maxX = nodes.maxOf { it.x } + 150f + 12f
                                val maxY = nodes.maxOf { it.y } + 115f + 12f

                                val width = maxX - minX
                                val height = maxY - minY

                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(minX.roundToInt(), minY.roundToInt()) }
                                        .requiredWidth(width.dp)
                                        .requiredHeight(height.dp)
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

                        // Hiển thị danh sách thiết bị
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

                // Nút Căn giữa (Home) thông minh dạng Floating Action Button
                FloatingActionButton(
                    onClick = {
                        if (deviceNodes.isNotEmpty()) {
                            val minX = deviceNodes.minOf { it.x }
                            val minY = deviceNodes.minOf { it.y }
                            val maxX = deviceNodes.maxOf { it.x + 150f }
                            val maxY = deviceNodes.maxOf { it.y + (if (it.type == DeviceType.CAMERA) 128f else 115f) }

                            val pixelMinX = minX * baseScale
                            val pixelMinY = minY * baseScale
                            val pixelMaxX = maxX * baseScale
                            val pixelMaxY = maxY * baseScale

                            val contentWidthPx = pixelMaxX - pixelMinX
                            val contentHeightPx = pixelMaxY - pixelMinY

                            val paddingPx = 32f * baseScale
                            val usableWidth = viewportWidth - paddingPx * 2
                            val usableHeight = viewportHeight - paddingPx * 2

                            val scaleX = if (contentWidthPx > 0) usableWidth / contentWidthPx else 1f
                            val scaleY = if (contentHeightPx > 0) usableHeight / contentHeightPx else 1f
                            zoomScale = minOf(scaleX, scaleY).coerceIn(0.5f, 2.5f)

                            val contentCenterX = (pixelMinX + pixelMaxX) / 2f
                            val contentCenterY = (pixelMinY + pixelMaxY) / 2f

                            panOffset = Offset(
                                x = (viewportWidth / 2f) - (contentCenterX * zoomScale),
                                y = (viewportHeight / 2f) - (contentCenterY * zoomScale)
                            )
                        } else if (floorplanBitmap != null) {
                            val imageWidth = (FLOORPLAN_DESIGN_WIDTH * floorplanScale) * baseScale
                            val imageHeight = (FLOORPLAN_DESIGN_HEIGHT * floorplanScale) * baseScale

                            val scaleX = viewportWidth / imageWidth
                            val scaleY = viewportHeight / imageHeight
                            zoomScale = minOf(scaleX, scaleY).coerceIn(0.5f, 2.5f)

                            panOffset = Offset(
                                x = (viewportWidth / 2f) - ((imageWidth / 2f) * zoomScale),
                                y = (viewportHeight / 2f) - ((imageHeight / 2f) * zoomScale)
                            )
                        } else {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Căn giữa sơ đồ nhà"
                    )
                }

                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                    )
                }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = node.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 90.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(node.id))
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("📋 Đã sao chép mã: ${node.id}")
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Sao chép mã định danh",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
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
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(action.icon, fontSize = 16.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = action.title,
                                                fontSize = 12.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 14.sp
                                            )
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

private const val FLOORPLAN_DESIGN_WIDTH = 1024f
private const val FLOORPLAN_DESIGN_HEIGHT = 1024f

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
            .clickable {
                onNodeClick(node)
            }
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
            .height(if (node.type == DeviceType.CAMERA) 128.dp else 115.dp)
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
                if (node.type == DeviceType.CAMERA) {
                    Text(
                        text = node.id,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
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