package com.aichatvn.agent.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aichatvn.agent.skills.CallState
import com.aichatvn.agent.ui.viewmodels.CallViewModel
import kotlinx.coroutines.delay
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
 * "sống" (Dialing/Connecting/Connected — mute, loa, Cúp máy). IDLE/ENDED/FAILED không hiển thị
 * màn này (onClose() nên được gọi từ nơi observe state để popBackStack ngay khi về IDLE/ENDED
 * nếu người dùng không tự bấm cúp).
 *
 * ✅ MỚI: hiện tên/avatar đối phương (fallback về mã máy nếu chưa lưu danh bạ), đồng hồ đếm
 * giờ khi CONNECTED, và 2 nút mute/loa cạnh nút Cúp máy.
 */
@Composable
fun CallScreen(
    vm: CallViewModel,
    onClose: () -> Unit
) {
    val uiState by vm.callUiState.collectAsState()
    val localVideoTrack by vm.localVideoTrack.collectAsState()
    val remoteVideoTrack by vm.remoteVideoTrack.collectAsState()

    val context = LocalContext.current

    // ✅ MỚI: chặn Android tự tắt màn hình theo thời gian chờ trong lúc đang ở CallScreen —
    // mặc định hệ thống vẫn áp dụng "Tự khoá màn hình" bình thường dù đang gọi video sống,
    // vì app không hề can thiệp gì vào Window. FLAG_KEEP_SCREEN_ON là đúng cơ chế app gọi
    // điện thật dùng. PHẢI clearFlags() ở onDispose (rời CallScreen dù do CÚP MÁY, back, hay
    // bị điều hướng đi nơi khác) — nếu quên, màn hình sẽ giữ sáng vĩnh viễn cho toàn bộ app
    // sau khi cuộc gọi đã kết thúc từ lâu, tốn pin nghiêm trọng.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ✅ MỚI: bấm "Nghe" cũng chạm camera/mic — cần xin quyền runtime giống DialScreen,
    // không thể giả định người dùng đã cấp quyền từ trước (VD: nhận cuộc gọi lần đầu, chưa
    // từng tự gọi đi bao giờ nên chưa qua bước xin quyền ở DialScreen).
    var pendingAnswerCallId by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingAnswerCallId?.let { vm.answer(it) }
        } else {
            Toast.makeText(context, "Cần cấp quyền Camera & Micro để nghe máy", Toast.LENGTH_LONG).show()
        }
        pendingAnswerCallId = null
    }

    fun answerWithPermissionCheck(callId: String) {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (mic && cam) {
            vm.answer(callId)
        } else {
            pendingAnswerCallId = callId
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // ✅ MỚI: đồng hồ đếm giờ cuộc gọi — chỉ chạy khi state == CONNECTED, tự dừng/reset khi
    // rời trạng thái đó (kết thúc cuộc gọi hoặc rớt kết nối).
    var elapsedSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.state) {
        if (uiState.state == CallState.CONNECTED) {
            val startedAt = System.currentTimeMillis() - (elapsedSec * 1000)
            while (true) {
                elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                delay(1000)
            }
        } else {
            elapsedSec = 0L
        }
    }

    val peerLabel = uiState.peerDisplayName ?: uiState.remoteDeviceCode ?: "..."
    val statusLabel = when (uiState.state) {
        CallState.DIALING -> "Đang gọi..."
        CallState.RINGING -> "Cuộc gọi đến"
        CallState.CONNECTING -> "Đang kết nối..."
        CallState.CONNECTED -> formatCallDuration(elapsedSec)
        else -> ""
    }
    val showLiveControls = uiState.state == CallState.DIALING ||
        uiState.state == CallState.CONNECTING ||
        uiState.state == CallState.CONNECTED

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

        // ✅ MỚI: tên/avatar + trạng thái đối phương, hiện ở giữa phía trên — khi chưa có
        // video (gọi thoại, hoặc video chưa tới) đây là thứ duy nhất người dùng thấy để
        // biết mình đang gọi cho ai và cuộc gọi đang ở giai đoạn nào.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (remoteVideoTrack == null) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        peerLabel.trim().take(1).uppercase().ifBlank { "?" },
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(peerLabel, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(statusLabel, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
        }

        // Các nút điều khiển cuộc gọi
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.state == CallState.RINGING) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Button(
                        onClick = { uiState.callId?.let { answerWithPermissionCheck(it) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                    ) { Text("Nghe") }
                    Button(
                        onClick = {
                            uiState.callId?.let { vm.reject(it) }
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) { Text("Từ chối") }
                }
            } else {
                if (showLiveControls) {
                    // ✅ MỚI: mute + loa — chỉ có ý nghĩa khi đã có track audio local (sau
                    // DIALING/CONNECTING trở đi), không hiện ở RINGING (chưa addLocalMediaTracks).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        CallControlButton(
                            icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (uiState.isMuted) "Đã tắt mic" else "Tắt mic",
                            active = uiState.isMuted,
                            onClick = { vm.toggleMute() }
                        )
                        CallControlButton(
                            icon = if (uiState.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            label = if (uiState.isSpeakerOn) "Loa ngoài" else "Đã tắt loa",
                            active = !uiState.isSpeakerOn,
                            onClick = { vm.toggleSpeaker() }
                        )
                    }
                }
                Button(
                    onClick = {
                        vm.hangup()
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cúp máy")
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (active) Color.White else Color.White.copy(alpha = 0.15f),
                contentColor = if (active) Color.Black else Color.White
            ),
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

private fun formatCallDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}