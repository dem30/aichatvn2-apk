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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// ✅ MỚI (Dimmer + Đặt lịch nhanh): import cho QuickScheduleDialog
import com.aichatvn.agent.data.model.ScheduleEntity
import org.json.JSONObject
import java.util.UUID
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb // Giữ hoặc xóa dòng này tùy thuộc vào cấu hình, ở dưới đã có hàm thay thế toArgbInt() để phòng ngừa
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
// ✅ MỚI: cho nút "Mời bạn dùng thử"
import com.aichatvn.agent.ui.invite.InviteShareHelper
import com.aichatvn.agent.ui.viewmodels.InviteUiState
import androidx.navigation.NavController
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.viewmodels.DashboardViewModel
import com.aichatvn.agent.ui.navigation.Screen // ✅ MỚI: để điều hướng tới màn hình Gọi (DIAL_ROUTE)
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

// Hàm mở rộng tự chuyển đổi màu Compose sang định dạng số nguyên ARGB dùng cho Paint nền tảng
private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt() and 0xFF
    val r = (red * 255f + 0.5f).toInt() and 0xFF
    val g = (green * 255f + 0.5f).toInt() and 0xFF
    val b = (blue * 255f + 0.5f).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel(),
    // ✅ MỚI: tự inject qua Hilt như viewModel ở trên — không cần sửa nơi gọi
    // NavHost(DashboardScreen(...)) hiện có.
    scheduleViewModel: com.aichatvn.agent.ui.viewmodels.ScheduleViewModel = hiltViewModel()
) {
    val deviceNodes by viewModel.deviceNodes.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val executionMessage by viewModel.executionMessage.collectAsState()
    val floorplanPath by viewModel.floorplanPath.collectAsState()
    val floorplanScale by viewModel.floorplanScale.collectAsState()
    
    val aiRecommendations by viewModel.aiRecommendations.collectAsState()
    // ✅ MỚI: trạng thái nút "Mời bạn dùng thử"
    val inviteUiState by viewModel.inviteUiState.collectAsState()

    var selectedNode by remember { mutableStateOf<DeviceNode?>(null) }
    // ✅ MỚI: node + action được chọn để đặt lịch nhanh (khác selectedNode — vẫn giữ bottom
    // sheet đang mở phía sau, dialog lịch nổi lên trên).
    var scheduleQuickAddTarget by remember { mutableStateOf<Pair<DeviceNode, DeviceAction>?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // ✅ MỚI: vòng poll 5s (SmartSwitchSkill.pollLightweightStates() qua ViewModel) chỉ nên
    // chạy khi Dashboard THỰC SỰ đang hiển thị. NavHost chỉ compose đúng 1 destination active
    // tại 1 thời điểm, nên DisposableEffect này tự vào composition khi mở tab Dashboard và tự
    // onDispose khi chuyển sang tab khác/back — dù ViewModel bên dưới vẫn sống nguyên (nhờ
    // saveState/restoreState ở AppNavigator.kt), không cần biết gì thêm về navigation.
    DisposableEffect(Unit) {
        viewModel.resumePolling()
        onDispose { viewModel.pausePolling() }
    }

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
    // ✅ SỬA: khởi tạo từ giá trị đã lưu trong ViewModel/DB thay vì luôn về mặc định 1f/Offset.Zero
    // — trước đây zoomScale/panOffset chỉ là remember cục bộ nên rời màn hình là mất.
    val savedViewportZoom by viewModel.viewportZoom.collectAsState()
    val savedViewportPanX by viewModel.viewportPanX.collectAsState()
    val savedViewportPanY by viewModel.viewportPanY.collectAsState()
    val viewportLoaded by viewModel.viewportLoaded.collectAsState()

    var zoomScale by remember { mutableStateOf(savedViewportZoom) }
    var panOffset by remember { mutableStateOf(Offset(savedViewportPanX, savedViewportPanY)) }

    // ✅ SỬA: dùng viewModel.viewportLoaded (Boolean) làm key thay vì chính 3 giá trị
    // zoom/panX/panY — trước đây key theo giá trị khiến effect này chạy NGAY LẬP TỨC với bộ
    // giá trị mặc định 1f/0f/0f (lúc Room chưa kịp trả DB), đặt viewportRestored = true quá
    // sớm; khi dữ liệu thật về sau đó, effect tuy chạy lại nhưng bị chính guard này chặn —
    // giá trị thật KHÔNG BAO GIỜ được áp dụng lên UI nữa. viewportLoaded chỉ chuyển
    // false → true đúng 1 lần, ngay khi loadViewportState() trong ViewModel đọc DB xong, nên
    // là tín hiệu đáng tin cậy duy nhất để biết "giá trị hiện tại là thật", bất kể giá trị đó
    // tình cờ có trùng với mặc định hay không.
    var viewportRestored by remember { mutableStateOf(false) }
    LaunchedEffect(viewportLoaded) {
        if (viewportLoaded && !viewportRestored) {
            zoomScale = savedViewportZoom
            panOffset = Offset(savedViewportPanX, savedViewportPanY)
            viewportRestored = true
        }
    }

    // Lưu lại zoom/pan ~500ms sau lần chỉnh cuối cùng (debounce) thay vì ghi DB liên tục
    // trong lúc đang kéo, để lần sau mở Dashboard sẽ về đúng góc nhìn đã chỉnh.
    // ✅ SỬA: chỉ bắt đầu lắng nghe sau khi viewportRestored == true — tránh việc effect này
    // chạy ngay từ lúc compose (khi zoomScale/panOffset vẫn còn là giá trị mặc định 1f/0f/0f
    // vì Room/Hilt chưa kịp trả kết quả), rồi debounce(500) tự bắn ghi đè giá trị mặc định
    // đó xuống app_config trước khi giá trị thật từ DB kịp về.
    LaunchedEffect(viewportRestored) {
        if (!viewportRestored) return@LaunchedEffect
        snapshotFlow { zoomScale to panOffset }
            .debounce(500)
            .collect { (zoom, pan) ->
                viewModel.saveViewportState(zoom, pan.x, pan.y)
            }
    }

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

    // ✅ MỚI: khi tạo mã mời thành công, mở Share Sheet ngay; khi lỗi, hiện Snackbar.
    // Gọi clearInviteUiState() sau khi xử lý để tránh mở lại Share Sheet oan lúc recompose.
    LaunchedEffect(inviteUiState) {
        when (val state = inviteUiState) {
            is InviteUiState.Success -> {
                val shareIntent = InviteShareHelper.buildInviteShareIntent(
                    context = context,
                    code = state.code,
                    baseUrl = state.baseUrl
                )
                context.startActivity(shareIntent)
                viewModel.clearInviteUiState()
            }
            is InviteUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearInviteUiState()
            }
            else -> {}
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
            // ✅ MỚI (UX v2): gradient rất nhẹ #FFFFFF → #F6F0FF thay vì nền trắng phẳng, cho
            // cảm giác hiện đại hơn mà không đổi bất kỳ hành vi/icon nào của AppBar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF6F0FF))))
            ) {
            TopAppBar(
                title = { Text("Sơ đồ thiết bị") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                    // ✅ MỚI: Icon "Mời bạn dùng thử" — gọi ViewModel.createInvite(), kết quả xử
                    // lý ở LaunchedEffect(inviteUiState) phía trên (mở Share Sheet khi thành công).
                    IconButton(
                        onClick = { viewModel.createInvite() },
                        enabled = inviteUiState !is InviteUiState.Loading
                    ) {
                        if (inviteUiState is InviteUiState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = "Mời bạn dùng thử"
                            )
                        }
                    }
                    // ✅ MỚI: Icon "Gọi" — mở màn nhập mã máy (DialScreen) để bắt đầu cuộc gọi
                    // thoại/video P2P tới 1 thiết bị AIChatVN2 khác.
                    IconButton(onClick = {
                        navController.navigate(Screen.DIAL_ROUTE)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Gọi thoại/video"
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
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ✅ NÂNG CẤP (UX v2): thêm gradient nhẹ, màu theo trạng thái THẬT (dựa trên số
            // thiết bị offline có sẵn trong deviceNodes — không bịa mức "nguy hiểm" vì không có
            // nguồn dữ liệu đáng tin cho việc đó ở tầng Dashboard), câu chào đầu ngày, và
            // bo góc/shadow đậm hơn cho cảm giác Material 3 hiện đại hơn.
            if (deviceNodes.isNotEmpty()) {
                val onlineCount = deviceNodes.count { it.online }
                val offlineCount = deviceNodes.size - onlineCount
                val cameraCount = deviceNodes.count { it.type == DeviceType.CAMERA && it.online }
                val isAllOnline = offlineCount == 0

                val statusColor = if (isAllOnline) Color(0xFF2E7D32) else Color(0xFFEF6C00) // 🟢 / 🟠
                val gradientTop = if (isAllOnline) Color(0xFFF6F0FF) else Color(0xFFFFF3E0)
                val gradientBottom = MaterialTheme.colorScheme.surface

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(20.dp), clip = false),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(listOf(gradientTop, gradientBottom)),
                                shape = RoundedCornerShape(20.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAllOnline) "Nhà đang an toàn" else "Có $offlineCount thiết bị mất kết nối",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isAllOnline) "😊 Chào mừng trở về. Mọi thứ đang hoạt động bình thường."
                                       else "AI đang theo dõi và sẽ báo khi thiết bị kết nối lại.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "• $onlineCount thiết bị online\n• $cameraCount camera đang giám sát",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // ✅ MỚI: Card "Sức khoẻ hệ thống" (SHAS) — đặt đầu danh sách nội dung, trước đề
            // xuất AI, theo đúng thứ tự đã duyệt trong kế hoạch UX overhaul.
            Column(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                com.aichatvn.agent.ui.system.SystemHealthCard(
                    onOpenDetail = {
                        navController.navigate(Screen.SYSTEM_HEALTH_ROUTE)
                    }
                )
            }

            if (aiRecommendations.isNotEmpty()) {
                // ✅ SỬA: trước đây chỉ lấy aiRecommendations.first() — nếu có ≥2 thói quen
                // đang chờ duyệt cùng lúc, các cái còn lại bị "giấu" hoàn toàn khỏi người dùng
                // cho tới khi cái đầu tiên được xử lý xong. Giờ hiển thị TẤT CẢ dưới dạng danh
                // sách cuộn ngang, mỗi thẻ tự Đồng ý/Bỏ qua độc lập.
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Đề xuất tự động hóa thông minh",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (aiRecommendations.size > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "${aiRecommendations.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(aiRecommendations, key = { it.id }) { recommendation ->
                            AiRecommendationCard(
                                answer = recommendation.answer,
                                onApprove = { viewModel.approvePattern(recommendation) },
                                onIgnore = { viewModel.ignorePattern(recommendation) }
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val viewportWidth = maxWidth.value
                val viewportHeight = maxHeight.value

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
                        // ✅ SỬA (fix đè text): graphicsLayer(scaleX/scaleY) bên trong không tự
                        // clip theo bounds — khi zoomScale lớn, nội dung được vẽ tràn ra ngoài
                        // vùng BoxWithConstraints này (đè lên thẻ trạng thái "Nhà đang an toàn"
                        // / "Có N thiết bị mất kết nối" nằm phía trên trong Column). clipToBounds()
                        // giữ mọi thứ được vẽ bên trong đúng khung của sơ đồ thiết bị.
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3.0f)
                                panOffset += pan
                            }
                        }
                ) {
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

                    // ✅ MỚI (Dimmer): Slider đầy đủ (khác 2 nút +/-10% ở DeviceNodeWidget thẻ
                    // nhỏ) — bottom sheet có đủ chỗ để kéo mượt như TuyaScreen. Cùng khuôn
                    // remember cục bộ + onValueChangeFinished mới gọi API thật, tránh gọi dồn
                    // dập theo pixel kéo.
                    if (node.isDimmable) {
                        var sliderPercent by remember(node.id) {
                            mutableStateOf((node.dimmerLevel ?: 50).toFloat())
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "🔆 Mức độ: ${sliderPercent.toInt()}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = sliderPercent,
                                onValueChange = { sliderPercent = it },
                                onValueChangeFinished = {
                                    viewModel.sendDeviceAction(node, "set", mapOf("level" to sliderPercent.toInt()))
                                },
                                valueRange = 0f..100f,
                                steps = 99
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

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

                                        Column(modifier = Modifier.weight(1f)) {
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
                                                modifier = Modifier.fillMaxWidth()
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
                                            // ✅ MỚI: nút "Đặt lịch" cho ĐÚNG action này — pluginId/
                                            // action.id/defaultParams đã có sẵn từ context (thiết bị
                                            // + hành động người dùng vừa xem), KHÔNG cần hỏi lại như
                                            // AddScheduleDialog đầy đủ. Chỉ mở dialog hỏi giờ/lặp lại.
                                            TextButton(
                                                onClick = {
                                                    scheduleQuickAddTarget = node to action
                                                },
                                                contentPadding = PaddingValues(vertical = 0.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Đặt lịch", fontSize = 11.sp)
                                            }
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

    // ✅ MỚI: dialog rút gọn đặt lịch nhanh — pluginId/action.id/defaultParams đã có sẵn từ
    // context (bottom sheet vừa mở), chỉ hỏi thêm giờ + lặp lại ngày nào. Với action "set" có
    // "level" trong defaultParams (dimmer), value đã đúng % người dùng vừa xem, không hỏi lại.
    scheduleQuickAddTarget?.let { (node, action) ->
        QuickScheduleDialog(
            node = node,
            action = action,
            onDismiss = { scheduleQuickAddTarget = null },
            onSave = { schedule ->
                scheduleViewModel.addSchedule(schedule)
                scheduleQuickAddTarget = null
            }
        )
    }
}

// ✅ MỚI: dialog rút gọn cho "Đặt lịch" từ bottom sheet Dashboard — KHÁC AddScheduleDialog đầy
// đủ ở ScheduleScreen.kt (vốn phải hỏi cả Plugin/Action/tham số từ đầu). Ở đây pluginId/action/
// params đã có sẵn từ ngữ cảnh thiết bị đang xem, chỉ còn hỏi giờ + lặp lại — đúng tinh thần
// "chọn thiết bị trước, hỏi ít nhất có thể" mà Smart Life làm.
@Composable
private fun QuickScheduleDialog(
    node: DeviceNode,
    action: DeviceAction,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    val timePickerState = rememberTimePickerState(is24Hour = true)
    var repeatMode by remember { mutableStateOf("daily") } // "daily" | "weekly"
    var selectedWeekdays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) } // 1=CN theo cron Quartz đã dùng ở AddScheduleDialog

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đặt lịch: ${action.title} — ${node.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimePicker(state = timePickerState)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = repeatMode == "daily",
                        onClick = { repeatMode = "daily" },
                        label = { Text("Hàng ngày") }
                    )
                    FilterChip(
                        selected = repeatMode == "weekly",
                        onClick = { repeatMode = "weekly" },
                        label = { Text("Chọn ngày") }
                    )
                }

                if (repeatMode == "weekly") {
                    // 1=CN,2=T2...7=T7 — cùng quy ước với AddScheduleDialog (ScheduleScreen.kt)
                    val weekdayLabels = listOf(1 to "CN", 2 to "T2", 3 to "T3", 4 to "T4", 5 to "T5", 6 to "T6", 7 to "T7")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        weekdayLabels.forEach { (value, label) ->
                            val isSelected = value in selectedWeekdays
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable {
                                        selectedWeekdays = if (isSelected) {
                                            selectedWeekdays - value
                                        } else {
                                            selectedWeekdays + value
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = repeatMode == "daily" || selectedWeekdays.isNotEmpty(),
                onClick = {
                    val finalCron = when (repeatMode) {
                        "weekly" -> "${timePickerState.minute} ${timePickerState.hour} * * ${selectedWeekdays.sorted().joinToString(",")}"
                        else -> "${timePickerState.minute} ${timePickerState.hour} * * *"
                    }
                    val paramsJson = JSONObject(action.defaultParams).toString()
                    val schedule = ScheduleEntity(
                        id = UUID.randomUUID().toString(),
                        pluginId = node.pluginId,
                        action = action.id,
                        params = paramsJson,
                        cron = finalCron,
                        intervalMinutes = 0,
                        enabled = 1,
                        lastRunAt = 0,
                        createdAt = System.currentTimeMillis(),
                        label = "${action.title} — ${node.name}"
                    )
                    onSave(schedule)
                }
            ) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
private fun AiRecommendationCard(
    answer: String,
    onApprove: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier.width(260.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp).weight(1f)
                ) {
                    Text("Đồng ý", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onIgnore,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp).weight(1f)
                ) {
                    Text("Bỏ qua", fontSize = 11.sp, maxLines = 1)
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
                                // Sử dụng hàm mở rộng toArgbInt() thay cho toArgb() hệ thống để vượt lỗi biên dịch
                                color = activeThemeColor.copy(alpha = glowAlpha).toArgbInt()
                                setShadowLayer(
                                    14.dp.toPx(),
                                    0f,
                                    0f,
                                    activeThemeColor.copy(alpha = glowAlpha).toArgbInt()
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