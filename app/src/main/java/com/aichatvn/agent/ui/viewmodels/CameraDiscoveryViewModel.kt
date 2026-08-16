package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.tools.camera.discovery.CameraDiscoveryEngine
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Trạng thái quét — UI (CameraDiscoveryDialog) chỉ cần collect state này, không tự gọi
 * CameraAutoDiscoveryTool trực tiếp.
 */
sealed class DiscoveryState {
    object Idle : DiscoveryState()
    data class Scanning(val current: Int, val total: Int) : DiscoveryState()
    data class Done(val results: List<DiscoveredCamera>) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}

@HiltViewModel
class CameraDiscoveryViewModel @Inject constructor(
    // ✅ SỬA (multi-protocol discovery): trước dùng thẳng CameraAutoDiscoveryTool (chỉ HTTP
    // snapshot scan) — giờ dùng CameraDiscoveryEngine điều phối nhiều probe (ONVIF WS-Discovery +
    // HTTP snapshot + chỗ trống cho vendor UDP sau). CameraAutoDiscoveryTool.kt vẫn còn trong
    // project (không xoá) nhưng ViewModel không gọi nó trực tiếp nữa.
    private val discoveryEngine: CameraDiscoveryEngine,
    private val logger: Logger
) : ViewModel() {

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Bắt đầu quét — nếu đang quét dở thì bỏ qua (tránh bấm 2 lần chạy song song 2 lượt quét
     * cùng lúc, vừa lãng phí vừa dễ nhiễu log).
     */
    fun startScan() {
        if (_state.value is DiscoveryState.Scanning) return

        scanJob?.cancel()
        _state.value = DiscoveryState.Scanning(0, 0)

        scanJob = viewModelScope.launch {
            try {
                val results = discoveryEngine.discoverCameras { current, total ->
                    _state.value = DiscoveryState.Scanning(current, total)
                }
                _state.value = DiscoveryState.Done(results)
            } catch (e: Exception) {
                logger.e("CameraDiscoveryViewModel", "startScan lỗi: ${e.message}", e)
                _state.value = DiscoveryState.Error(e.message ?: "Lỗi không xác định khi quét")
            }
        }
    }

    /** Hủy quét đang chạy dở — gọi khi người dùng đóng dialog giữa chừng. */
    fun cancelScan() {
        scanJob?.cancel()
        _state.value = DiscoveryState.Idle
    }

    fun reset() {
        scanJob?.cancel()
        _state.value = DiscoveryState.Idle
    }
}
