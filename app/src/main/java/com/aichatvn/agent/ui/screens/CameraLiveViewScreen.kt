package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                    // ✅ MỚI: hiển thị ảnh JPEG mới nhất từ RtspFrameGrabber (poll liên tục ở
                    // ViewModel) thay vì video thật qua ExoPlayer — xem lý do ở comment đầu file.
                    if (liveFrame != null) {
                        val bitmap = remember(liveFrame) {
                            liveFrame?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Hình ảnh trực tiếp từ camera",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Đang lấy hình từ camera...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // ✅ MỚI: báo lỗi nếu 1 lượt lấy hình gần nhất thất bại — không đè mất ảnh cũ
                    // đang hiển thị (chỉ hiện thêm dòng cảnh báo nhỏ ở dưới, ảnh cũ vẫn giữ
                    // nguyên cho tới khi có ảnh mới), tránh nhấp nháy mất hình mỗi khi có 1 lượt
                    // poll bị trễ/lỗi tạm thời.
                    liveFrameError?.let { err ->
                        Text(
                            err,
                            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
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