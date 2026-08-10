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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.MediaItem
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

    DisposableEffect(state) {
        val s = state
        if (s is LiveViewState.Ready) {
            val exo = ExoPlayer.Builder(context).build().apply {
                setMediaSource(RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(s.rtspUrl)))
                prepare()
                playWhenReady = true
            }
            player = exo
        }
        onDispose {
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