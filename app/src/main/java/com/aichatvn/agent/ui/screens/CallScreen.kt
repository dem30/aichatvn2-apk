package com.aichatvn.agent.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aichatvn.agent.skills.CallState
import com.aichatvn.agent.ui.viewmodels.CallViewModel
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * CallScreen
 *
 * Màn hình cuộc gọi đang diễn ra. Được mở CHỦ ĐỘNG từ DialScreen (sau start_call) hoặc TỰ
 * BẬT từ bất kỳ đâu trong app khi có cuộc gọi đến (RINGING) — xem LaunchedEffect(callState.state)
 * trong AppNavigator.
 *
 * Chỉ xử lý 2 trạng thái UI: RINGING (đổ chuông — Nghe/Từ chối) và mọi trạng thái khác đang
 * "sống" (Dialing/Connecting/Connected — chỉ có nút Cúp máy). IDLE/ENDED/FAILED không hiển thị
 * màn này (onClose() nên được gọi từ nơi observe state để popBackStack ngay khi về IDLE/ENDED
 * nếu người dùng không tự bấm cúp).
 *
 * ✅ SỬA so với bản gốc: nhận CallViewModel thay vì CallSkill trực tiếp — CallSkill.execute()
 * là hàm `suspend`, gọi thẳng trong onClick{} (không phải coroutine scope) sẽ KHÔNG BUILD ĐƯỢC.
 * CallViewModel.answer()/reject()/hangup() đã bọc sẵn viewModelScope.launch{} nên gọi trực
 * tiếp trong onClick được, giống pattern các ViewModel khác trong app (ChatViewModel...).
 */
@Composable
fun CallScreen(
    vm: CallViewModel,
    onClose: () -> Unit
) {
    val uiState by vm.callUiState.collectAsState()
    val localVideoTrack by vm.localVideoTrack.collectAsState()
    val remoteVideoTrack by vm.remoteVideoTrack.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video đối phương (Toàn màn hình)
        remoteVideoTrack?.let { track ->
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        init(vm.callSkill.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        track.addSink(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        // Video bản thân (Góc nhỏ)
        localVideoTrack?.let { track ->
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(vm.callSkill.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setZOrderMediaOverlay(true)
                        track.addSink(this)
                    }
                },
                modifier = Modifier
                    .size(120.dp, 160.dp)
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
        // Các nút điều khiển cuộc gọi
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (uiState.state == CallState.RINGING) {
                Button(
                    onClick = { uiState.callId?.let { vm.answer(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) { Text("Nghe") }
                Button(
                    onClick = {
                        uiState.callId?.let { vm.reject(it) }
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Từ chối") }
            } else {
                Button(
                    onClick = {
                        vm.hangup()
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Cúp máy") }
            }
        }
    }
}