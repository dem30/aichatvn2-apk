package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.inject.Inject

sealed class LiveViewState {
    object Loading : LiveViewState()
    data class Ready(val rtspUrl: String) : LiveViewState()
    // ✅ MỚI: tách riêng khỏi NotAvailable — trường hợp camera CÓ bật RTSP, cấu hình đúng, nhưng
    // máy hiện không cùng LAN với camera (đang ở ngoài đường). Trước đây rơi chung vào
    // NotAvailable("...") khiến người dùng chỉ thấy 1 câu lỗi mơ hồ, không có hành động nào để
    // làm tiếp — trong khi thực ra CÓ 1 đường thay thế (gọi qua Camera Node/CallSkill) nếu người
    // dùng đã bật Chế độ Local-only. Màn hình dùng field "canCallViaCameraNode" để quyết định có
    // hiện nút "Gọi xem qua Camera Node" hay không.
    data class OutOfHomeLan(val canCallViaCameraNode: Boolean) : LiveViewState()
    data class NotAvailable(val reason: String) : LiveViewState()
}

@HiltViewModel
class CameraLiveViewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) : ViewModel() {

    val cameraId: String = (savedStateHandle.get<String>("cameraId") ?: "").trim()

    private val _camera = MutableStateFlow<CameraConfigEntity?>(null)
    val camera: StateFlow<CameraConfigEntity?> = _camera.asStateFlow()

    private val _state = MutableStateFlow<LiveViewState>(LiveViewState.Loading)
    val state: StateFlow<LiveViewState> = _state.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    // ✅ MỚI: "Xem trực tiếp" kiểu chụp ảnh liên tục qua RtspFrameGrabber (FFmpeg) — thay cho
    // ExoPlayer/RtspMediaSource. Lý do đổi: media3-exoplayer-rtsp vẫn @UnstableApi, đứng hình ở
    // frame đầu KHÔNG lỗi KHÔNG timeout với camera dạng /Streaming/Channels/101 (case thật đã
    // gặp) — trùng khớp 2 issue vẫn đang MỞ trên chính tracker androidx/media (#893 "freezes on
    // first frame when audio is added", #2138 "First Frame Freeze"), tức là giới hạn thật của thư
    // viện, không phải lỗi cấu hình. Trong khi đó RtspFrameGrabber (FFmpeg) đã CHỨNG MINH decode
    // được frame thật từ ĐÚNG stream này (xem log "Bắt frame OK" ở CameraDetailViewModel). Không
    // mượt bằng video thật (~1 ảnh/1-3s tuỳ tốc độ mạng+camera, xem GRAB_INTERVAL_MS), nhưng chắc
    // chắn ra hình thay vì màn đen vô thời hạn.
    private val _liveFrame = MutableStateFlow<ByteArray?>(null)
    val liveFrame: StateFlow<ByteArray?> = _liveFrame.asStateFlow()

    private val _liveFrameError = MutableStateFlow<String?>(null)
    val liveFrameError: StateFlow<String?> = _liveFrameError.asStateFlow()

    private var framePollingJob: Job? = null

    // ✅ MỚI: mã Camera Node ở nhà, đọc sẵn cho màn hình dùng khi bấm "Gọi xem qua Camera Node" —
    // xem ghi chú đầy đủ ở callHomeCameraNode() bên dưới về LÝ DO ViewModel này
    // KHÔNG tự gọi CallSkill.execute("start_call", ...) hay tự navController.navigate(...).
    init { load() }

    private fun load() {
        viewModelScope.launch {
            val cam = withContext(Dispatchers.IO) { database.cameraDao().getCameraById(cameraId) }
            _camera.value = cam

            _state.value = when {
                cam == null -> LiveViewState.NotAvailable("Không tìm thấy camera")
                cam.rtspEnabled != 1 || cam.rtspUrl.isNullOrBlank() ->
                    LiveViewState.NotAvailable("Camera chưa bật Xem trực tiếp hoặc không hỗ trợ RTSP")
                else -> resolveReadyOrOutOfLan(cam.rtspUrl)
            }

            val readyState = _state.value
            if (readyState is LiveViewState.Ready) {
                startFramePolling(readyState.rtspUrl, cam?.snapshotUsername, cam?.snapshotPassword)
            }
        }
    }

    /**
     * ✅ MỚI: vòng lặp gọi RtspFrameGrabber liên tục — CHỜ frame trước xong mới delay rồi gọi
     * tiếp (không dùng fixed-rate timer), tránh chồng lấn nhiều lệnh FFmpeg cùng lúc nếu 1 lần
     * grab chạy chậm (mạng yếu, camera phản hồi trễ). Dừng lại ngay khi coroutine bị huỷ
     * (rời màn hình → onCleared() huỷ viewModelScope → job này tự dừng theo, cùng cơ chế release()
     * cũ của ExoPlayer trước đây).
     */
    private fun startFramePolling(rtspUrl: String, username: String?, password: String?) {
        framePollingJob?.cancel()
        framePollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = cameraSkill.fetchOneSnapshot(rtspUrl, username, password)
                if (bytes != null) {
                    _liveFrame.value = bytes
                    _liveFrameError.value = null
                } else {
                    _liveFrameError.value = "Không lấy được hình từ camera — kiểm tra camera có đang online không."
                }
                delay(GRAB_INTERVAL_MS)
            }
        }
    }

    private fun stopFramePolling() {
        framePollingJob?.cancel()
        framePollingJob = null
        _liveFrame.value = null
        _liveFrameError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopFramePolling()
    }

    /**
     * ⚠️ SỬA (lỗ hổng UX): trước đây hàm load() coi rtspUrl có giá trị là ĐỦ để trả Ready, bất kể
     * máy hiện có thực sự cùng LAN với camera hay không — nếu người dùng đang ở ngoài đường,
     * CameraLiveViewScreen sẽ cố kết nối RTSP tới 1 IP nội bộ không thể route được từ internet,
     * và trước đây (khi còn dùng ExoPlayer) sẽ âm thầm treo/timeout mà không có lời giải thích
     * hay lối thoát nào cho người dùng.
     *
     * Cách xác định "cùng LAN hay không" ĐÁNG TIN CẬY nhất, không cần lưu SSID nhà (tránh thêm 1
     * bước cấu hình cho người dùng): thử mở thẳng 1 TCP socket tới đúng host:port trong rtspUrl,
     * timeout ngắn. Nếu route được (dù bị từ chối do sai auth) nghĩa là chắc chắn đang cùng LAN;
     * nếu timeout/unreachable, gần như chắc chắn đang khác mạng — cùng nguyên tắc TuyaManager.
     * discoverIp() đã dùng để tự phát hiện tình huống tương tự cho Tuya.
     */
    private suspend fun resolveReadyOrOutOfLan(rtspUrl: String): LiveViewState = withContext(Dispatchers.IO) {
        val reachable = try {
            val uri = URI(rtspUrl)
            val port = if (uri.port > 0) uri.port else 554 // cổng RTSP mặc định nếu URL không ghi rõ
            Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, port), LAN_PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }

        if (reachable) {
            LiveViewState.Ready(rtspUrl)
        } else {
            // Không route được tới IP nội bộ — gần như chắc chắn đang ở ngoài LAN nhà. Chỉ đề
            // xuất "Gọi qua Camera Node" nếu người dùng đã CHỦ ĐỘNG bật Chế độ Local-only (đã xác
            // nhận có 1 máy Camera Node luôn online ở nhà) — nếu chưa bật, hiện lý do đơn thuần,
            // không gợi ý 1 tính năng người dùng chưa thiết lập.
            val localOnlyEnabled = configProvider.getBoolean(AppConfigDefaults.LOCAL_ONLY_MODE_ENABLED)
            LiveViewState.OutOfHomeLan(canCallViaCameraNode = localOnlyEnabled)
        }
    }

    /**
     * ⚠️ THIẾT KẾ: ViewModel này KHÔNG tự gọi CallSkill.execute("start_call", ...) hay tự
     * navController.navigate(...) — theo đúng ghi chú trong CallViewModel.kt: CallSkill là
     * @Singleton dùng chung, và CallScreen được AppNavigator TỰ ĐỘNG bật lên (LaunchedEffect quan
     * sát callUiState.state chuyển sang DIALING/RINGING) — không phải do từng màn hình tự điều
     * hướng tới. Nếu ViewModel này tự giữ 1 CallSkill/tự gọi execute() riêng, sẽ tạo đường gọi
     * thứ 2 song song với CallViewModel đã có, dễ lệch trạng thái.
     *
     * Vì vậy hàm này chỉ đọc config trả về deviceCode — màn hình (CameraLiveViewScreen) là nơi
     * gọi callViewModel.startCall(homeDeviceCode, video = true) bằng đúng CallViewModel
     * Activity-scope duy nhất mà AppNavigator đã tạo sẵn, y hệt cách DialScreen làm.
     */
    // ⚠️ SỬA: trước đây hàm này là `fun` thường nhưng gọi configProvider.getString() (suspend)
    // ngay trong thân hàm → lỗi biên dịch "Suspend function should be called only from a
    // coroutine". Đổi sang dạng callback: hàm chỉ khởi chạy viewModelScope.launch, tự lo phần
    // suspend bên trong, rồi trả deviceCode ra ngoài qua onDeviceCode — màn hình
    // (CameraLiveViewScreen) gọi callViewModel.startCall()/navController.navigate() bên trong
    // callback đó, không cần tự tạo CoroutineScope ở Composable.
    fun callHomeCameraNode(onDeviceCode: (String) -> Unit) {
        viewModelScope.launch {
            val code = withContext(Dispatchers.IO) {
                configProvider.getString(AppConfigDefaults.HOME_CAMERA_NODE_DEVICE_CODE).trim()
            }
            if (code.isBlank()) {
                _actionMessage.value = "Chưa cấu hình Mã Camera Node ở nhà trong Cài đặt."
                return@launch
            }
            onDeviceCode(code)
        }
    }

    companion object {
        // Ngắn hơn nhiều so với timeout dò tìm ban đầu (CameraCapabilityProber dùng ~vài giây cho
        // quét lần đầu) — đây là kiểm tra chạy MỖI LẦN mở màn hình xem trực tiếp, cần phản hồi
        // nhanh để không làm người dùng chờ lâu trước khi biết được kết quả.
        private const val LAN_PROBE_TIMEOUT_MS = 1_500

        // ✅ MỚI: khoảng nghỉ giữa 2 lần gọi RtspFrameGrabber liên tiếp — tính TỪ LÚC frame trước
        // xong, không phải fixed-rate. Đặt ngắn (500ms) vì grabFrame() vốn đã mất 1-3s thật sự
        // (RtspFrameGrabber.GRAB_TIMEOUT_MS cho phép tới 10s khi mạng/camera chậm) — khoảng nghỉ
        // chỉ để tránh nã lệnh FFmpeg liên tục sát nhau không cần thiết khi camera phản hồi rất
        // nhanh, không phải cơ chế giới hạn tốc độ chính.
        private const val GRAB_INTERVAL_MS = 500L
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val recording = _isRecording.value
            val result = withContext(Dispatchers.IO) {
                if (recording) cameraSkill.stopRecording(cameraId)
                else cameraSkill.startRecording(cameraId, durationSec = 120)
            }
            when (result) {
                is com.aichatvn.agent.core.AgentKernel.PluginResult.Success -> {
                    _isRecording.value = !recording
                    _actionMessage.value = (result.data as? Map<*, *>)?.get("message") as? String
                }
                is com.aichatvn.agent.core.AgentKernel.PluginResult.Failure -> {
                    _actionMessage.value = result.error
                }
                else -> {}
            }
            logger.i("CameraLiveViewViewModel", "toggleRecording cameraId=$cameraId → ${!recording}")
        }
    }

    fun clearMessage() { _actionMessage.value = null }
}