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
import com.aichatvn.agent.ui.viewmodels.CameraLiveViewViewModel
import com.aichatvn.agent.ui.viewmodels.LiveViewState

@androidx.compose.runtime.Composable
fun CameraLiveViewScreen(navController: NavController, viewModel: CameraLiveViewViewModel = hiltViewModel()) {
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