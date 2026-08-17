package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.CallViewModel
import com.aichatvn.agent.ui.viewmodels.CameraLiveViewViewModel
import com.aichatvn.agent.ui.viewmodels.LiveViewState

/**
 * ⚠️ SỬA: nhận [callViewModel] làm THAM SỐ thay vì tự hiltViewModel() bên trong composable này —
 * theo đúng cách AppNavigator.kt đã gọi DialScreen(navController, callViewModel)/CallScreen(vm=
 * callViewModel, ...): CallViewModel PHẢI là đúng 1 instance duy nhất được tạo Ở SCOPE
 * AppNavigator (ngoài NavHost, xem dòng "val callViewModel: CallViewModel = hiltViewModel()" tại
 * đó) rồi truyền xuống — Hilt tự hiltViewModel() bên TRONG 1 composable(route) của NavHost sẽ lấy
 * theo scope của route đó, tạo ra MỘT INSTANCE KHÁC, lệch hẳn với instance mà AppNavigator đang
 * quan sát để tự bật CallScreen/Floating Call Bubble.
 *
 * ⚠️ Cần cập nhật khai báo route trong AppNavigator.kt:
 *   composable(route = "camera_live/{cameraId}", ...) { CameraLiveViewScreen(navController, callViewModel) }
 *
 * ⚠️ SỬA (bỏ ExoPlayer/RtspMediaSource — case thật: camera dạng /Streaming/Channels/101 đứng
 * hình ở frame đầu, KHÔNG lỗi KHÔNG timeout, dù đã ép setForceUseRtpTcp(true) + tắt audio track).
 * Đây là giới hạn THẬT của module media3-exoplayer-rtsp (vẫn @UnstableApi) — trùng khớp 2 issue
 * còn đang MỞ trên chính tracker androidx/media (#893 "freezes on first frame when audio is
 * added", #2138 "First Frame Freeze"), không phải lỗi cấu hình có thể vá tiếp bằng cách chỉnh
 * tham số. Trong khi đó RtspFrameGrabber (FFmpeg) đã CHỨNG MINH decode được frame thật từ ĐÚNG
 * stream này (xem log "Bắt frame OK" — cùng URL, cùng camera). Màn hình giờ hiển thị
 * viewModel.liveFrame (ảnh JPEG mới nhất, ViewModel tự polling qua RtspFrameGrabber, xem
 * CameraLiveViewViewModel.startFramePolling()) thay vì video thật — không mượt bằng video, nhưng
 * chắc chắn ra hình.
 */
// ✅ MỚI: ngưỡng coi là "mất kết nối thật sự" (khác lỗi thoáng qua 1 lượt poll) — dùng để quyết
// định hiện banner cảnh báo rõ giữa màn hình hay chỉ dòng chữ nhỏ như cũ, xem "isStale" trong
// CameraLiveViewScreen(). Đặt gấp ~4 lần GRAB_INTERVAL_MS tối đa (CameraLiveViewViewModel dùng
// 500ms nghỉ giữa 2 lần grab, nhưng RtspFrameGrabber có thể mất tới vài giây thật khi mạng/camera
// chậm) — đủ dư dả để không báo động giả với 1-2 lượt lỗi tạm thời liên tiếp.
private const val STALE_THRESHOLD_MS = 8_000L

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun CameraLiveViewScreen(
    navController: NavController,
    callViewModel: CallViewModel,
    viewModel: CameraLiveViewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val camera by viewModel.camera.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val liveFrame by viewModel.liveFrame.collectAsState()
    val liveFrameError by viewModel.liveFrameError.collectAsState()
    // ✅ MỚI: trạng thái tắt/bật âm thanh của kênh audio-only ExoPlayer (song song với video
    // polling ảnh) — xem CameraLiveViewViewModel.startAudioPlayback()/toggleMute().
    val isAudioMuted by viewModel.isAudioMuted.collectAsState()

    // ✅ MỚI (nâng UX màn xem trực tiếp — bù đắp việc chưa dùng được video thật qua Media3, xem
    // KDoc đầu file): state THUẦN UI, không đụng tới ViewModel/logic polling — chỉ tính toán từ
    // dữ liệu đã có (liveFrame đổi giá trị mỗi lần poll thành công) để hiển thị đồng hồ "đã xem
    // bao lâu" và phân biệt lỗi THOÁNG QUA (1 lượt poll lỗi tạm) với MẤT KẾT NỐI THẬT SỰ (nhiều
    // lượt liên tiếp không ra frame mới) — trước đây liveFrameError chỉ hiện 1 dòng chữ nhỏ y hệt
    // nhau cho cả 2 trường hợp, không phân biệt được mức độ nghiêm trọng.
    var viewStartedAtMs by remember(state) { mutableStateOf<Long?>(null) }
    var lastFrameAtMs by remember(state) { mutableStateOf<Long?>(null) }
    var elapsedLabel by remember { mutableStateOf("00:00") }

    LaunchedEffect(liveFrame) {
        if (liveFrame != null) {
            val now = System.currentTimeMillis()
            if (viewStartedAtMs == null) viewStartedAtMs = now
            lastFrameAtMs = now
        }
    }

    // Tick đồng hồ mỗi giây trong lúc đang Ready — dừng tự động khi rời màn hình (coroutine theo
    // vòng đời Composable, không phải Job riêng cần tự huỷ).
    LaunchedEffect(state) {
        if (state is LiveViewState.Ready) {
            while (true) {
                val started = viewStartedAtMs
                if (started != null) {
                    val secs = (System.currentTimeMillis() - started) / 1000
                    elapsedLabel = "%02d:%02d".format(secs / 60, secs % 60)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // Mất kết nối "thật sự" (đáng cảnh báo rõ) nếu đã hơn STALE_THRESHOLD_MS kể từ frame gần nhất
    // MÀ vẫn đang báo lỗi — 1 lượt poll lỗi đơn lẻ (mạng chập chờn thoáng qua) không tính.
    val isStale = liveFrameError != null &&
        lastFrameAtMs?.let { System.currentTimeMillis() - it > STALE_THRESHOLD_MS } != false

    LaunchedEffect(actionMessage) {
        if (actionMessage != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(camera?.customername ?: "Xem trực tiếp") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (state is LiveViewState.Ready) Color.Black else MaterialTheme.colorScheme.surface,
                    titleContentColor = if (state is LiveViewState.Ready) Color.White else MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = if (state is LiveViewState.Ready) Color.White else MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = if (state is LiveViewState.Ready) Color.White else MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    // ✅ MỚI: chỉ hiện khi đã Ready (có kênh audio đang chạy) — Loading/NotAvailable/
                    // OutOfHomeLan không có audioPlayer nào đang phát.
                    if (state is LiveViewState.Ready) {
                        IconButton(onClick = { viewModel.toggleMute() }) {
                            Icon(
                                imageVector = if (isAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (isAudioMuted) "Bật âm thanh" else "Tắt âm thanh"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is LiveViewState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LiveViewState.NotAvailable -> Text(
                    s.reason,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                is LiveViewState.OutOfHomeLan -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Camera này chỉ xem trực tiếp được khi cùng mạng Wi-Fi ở nhà.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (s.canCallViaCameraNode) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Đang bật Chế độ Local-only — bạn có thể gọi xem qua Camera Node ở nhà.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            // ⚠️ SỬA: getHomeCameraNodeDeviceCodeOrNull() cũ là fun thường gọi
                            // suspend fun bên trong → lỗi biên dịch. callHomeCameraNode() giờ tự
                            // launch trong ViewModel (viewModelScope), Screen chỉ cần truyền
                            // callback — không cần tự tạo CoroutineScope ở đây.
                            viewModel.callHomeCameraNode { homeDeviceCode ->
                                callViewModel.startCall(homeDeviceCode, video = true)
                                // ⚠️ SỬA: AppNavigator CHỈ tự động navigate(Screen.CALL_ROUTE) khi
                                // callState.state == RINGING (cuộc gọi ĐẾN) — xem LaunchedEffect
                                // trong AppNavigator.kt. Với cuộc gọi ĐI (trường hợp ở đây), phải
                                // tự navigate sang CALL_ROUTE, đúng như DialScreen chắc chắn cũng
                                // làm sau khi bấm gọi.
                                navController.navigate(
                                    com.aichatvn.agent.ui.navigation.Screen.CALL_ROUTE
                                ) { launchSingleTop = true }
                            }
                        }) {
                            Text("📞 Gọi xem qua Camera Node")
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bật \"Chế độ Local-only\" trong Cài đặt (yêu cầu đã có 1 máy Camera Node ở nhà) để xem được camera khi ở ngoài đường.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is LiveViewState.Ready -> {
                    // ✅ MỚI (nâng UX): nền đen thay vì nền trắng mặc định Scaffold — trải nghiệm
                    // "xem camera" chuẩn hơn (ảnh JPEG không bị lọt thỏm giữa khoảng trắng), và
                    // Crossfade giữa 2 frame liên tiếp thay vì đổi ảnh đột ngột — dịu bớt cảm giác
                    // giật khi khoảng cách giữa 2 lần poll khá xa (500ms-3s tuỳ mạng/camera, xem
                    // GRAB_INTERVAL_MS ở ViewModel). Bản chất vẫn là chuỗi ảnh tĩnh nối tiếp, không
                    // phải video thật — Crossfade chỉ che bớt cảm giác giật, không tăng framerate
                    // thật.
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        if (liveFrame != null) {
                            Crossfade(
                                targetState = liveFrame,
                                animationSpec = tween(durationMillis = 250),
                                modifier = Modifier.fillMaxSize()
                            ) { frame ->
                                val bitmap = remember(frame) {
                                    frame?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                }
                                bitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Hình ảnh trực tiếp từ camera",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Đang lấy hình từ camera...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }

                        // ✅ MỚI: badge "TRỰC TIẾP" + chấm đỏ nhấp nháy, góc trên-trái — cho biết
                        // rõ đây là stream đang chạy (không phải ảnh tĩnh chết), cùng đồng hồ đếm
                        // thời gian đã xem từ lúc nhận frame đầu tiên. Chỉ hiện khi có ít nhất 1
                        // frame (viewStartedAtMs != null) — tránh nhấp nháy "00:00 LIVE" trong lúc
                        // vẫn còn đang chờ frame đầu.
                        if (liveFrame != null && viewStartedAtMs != null) {
                            val infiniteTransition = rememberInfiniteTransition(label = "live-dot")
                            val dotAlpha by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.25f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 900, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "live-dot-alpha"
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = dotAlpha))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "TRỰC TIẾP",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    elapsedLabel,
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // ✅ SỬA (nâng UX): trước đây liveFrameError luôn hiện y hệt (1 dòng chữ
                        // đỏ nhỏ) cho cả lỗi thoáng qua lẫn mất kết nối kéo dài, không phân biệt
                        // mức độ. Giờ tách 2 mức:
                        // - Lỗi thoáng qua (chưa quá STALE_THRESHOLD_MS kể từ frame gần nhất): giữ
                        //   nguyên dòng chữ nhỏ như cũ, không làm phiền quá mức vì có thể tự khỏi
                        //   ở lượt poll kế tiếp.
                        // - Mất kết nối thật sự (isStale = true): banner rõ ràng hơn hẳn, giữa màn
                        //   hình, kèm icon — người dùng cần BIẾT NGAY đây không còn là hình ảnh
                        //   thời gian thực nữa, tránh hiểu lầm nhà vẫn đang yên ổn trong khi thực
                        //   ra mất kết nối đã lâu.
                        AnimatedVisibility(
                            visible = isStale,
                            modifier = Modifier.align(Alignment.Center),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.SignalWifiOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Mất kết nối với camera",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Đang hiển thị hình cũ nhất có được. Kiểm tra camera có đang online không.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        if (!isStale) {
                            liveFrameError?.let { err ->
                                Text(
                                    err,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            actionMessage?.let {
                                Text(
                                    it,
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = Color.White
                                )
                            }
                            Button(
                                onClick = { viewModel.toggleRecording() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isRecording) "Dừng ghi hình" else "Ghi hình (2 phút)")
                            }
                        }
                    }
                }
            }
        }
    }
}