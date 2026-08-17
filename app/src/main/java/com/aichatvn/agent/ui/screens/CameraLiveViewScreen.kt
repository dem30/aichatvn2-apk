package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun CameraLiveViewScreen(
    navController: NavController,
    callViewModel: CallViewModel,
    viewModel: CameraLiveViewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val camera by viewModel.camera.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    // ✅ MỚI: player chỉ tồn tại trong lúc màn hình này hiển thị — release() ngay khi rời
    // màn hình (DisposableEffect), KHÔNG giữ nền như service — đúng thiết kế đã chốt.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    // ✅ MỚI: lỗi phát RTSP (SETUP thất bại, timeout, auth sai...) — trước đây ExoPlayer fail
    // HOÀN TOÀN ÂM THẦM: không có Player.Listener nào gắn vào, người dùng chỉ thấy khung đen vĩnh
    // viễn không rõ lý do, tưởng app bị treo. Giờ bắt lỗi và hiện luôn trên UI.
    var playbackError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(state) {
        val s = state
        var listener: Player.Listener? = null
        if (s is LiveViewState.Ready) {
            playbackError = null
            // ⚠️ SỬA (màn hình đen không rõ lý do): ExoPlayer mặc định thử RTP qua UDP trước —
            // nếu NAT/router chặn UDP inbound (rất phổ biến trên mạng nhà, 4G, hoặc khi router
            // không mở port UDP tương ứng), ExoPlayer  ÂM THẦM đợi tới hết setTimeoutMs() (mặc
            // định 8s, có thể lâu hơn) rồi mới tự chuyển sang TCP — trong lúc đó màn hình chỉ có
            // màu đen, không có lỗi hay dấu hiệu gì. Đây là giới hạn CÙNG BẢN CHẤT với vấn đề UDP
            // trên Android mà RtspFrameGrabber.kt (nhánh chụp ảnh AI qua FFmpeg) đã phải xử lý
            // bằng "-rtsp_transport tcp" — ở đây dùng setForceUseRtpTcp(true) làm điều tương
            // đương cho ExoPlayer, ép TCP ngay từ đầu thay vì đợi timeout rồi mới fallback.
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8_000)
                // ✅ MỚI: bật log chi tiết SETUP/DESCRIBE/track parsing ra logcat (tag
                // RtspClient/ExoPlayer) — không hiện trong màn "System Logs" của app (đó là
                // Logger riêng của app, ExoPlayer log thẳng ra android.util.Log/logcat) nên cần
                // xem qua `adb logcat` khi cần soi kỹ nếu bước tắt audio bên dưới không đủ.
                .setDebugLoggingEnabled(true)
                .createMediaSource(MediaItem.fromUri(s.rtspUrl))
            val exo = ExoPlayer.Builder(context).build().apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
            // ⚠️ SỬA (đứng hình ở frame đầu, không lỗi, không timeout): sau khi ép TCP mà vẫn
            // đứng ở 00:00 không decode được, đây là 1 bug KHÁC — đã có ghi nhận công khai ở
            // chính tracker androidx/media (issue #893 "freezes on first frame when audio is
            // added", issue #2138 "First Frame Freeze" — cả 2 đều VẪN ĐANG MỞ, chưa có fix chính
            // thức). module media3-exoplayer-rtsp còn @UnstableApi, hay xung khắc với audio track
            // (thường G711) của các đầu ghi/camera giá rẻ kiểu Hikvision-clone (path
            // /Streaming/Channels/101 rất đặc trưng dạng này) — dù FFmpeg/VLC decode bình thường
            // (RtspFrameGrabber của chính app này đã CHỨNG MINH decode được ảnh thật từ đúng
            // stream này). Tắt hẳn audio track — chỉ cần xem hình, không cần nghe — để loại khả
            // năng phổ biến nhất gây đứng hình này trước khi nghi ngờ tới codec video.
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = "Không phát được RTSP: ${error.errorCodeName} — ${error.message}"
                }
            }
            exo.addListener(listener)
            player = exo
        }
        onDispose {
            listener?.let { player?.removeListener(it) }
            player?.release()
            player = null
        }
    }

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
                    player?.let { exo ->
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center),
                            factory = { PlayerView(context).apply { this.player = exo } }
                        )
                    }
                    // ✅ MỚI: hiện lỗi phát RTSP ngay giữa khung hình (đè lên chỗ trước đây luôn
                    // đen im lặng) — xem playbackError ở DisposableEffect phía trên.
                    playbackError?.let { err ->
                        Text(
                            err,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        actionMessage?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
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