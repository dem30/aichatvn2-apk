package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.ui.dashboard.DashboardProvider
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards DashboardProvider>,
    private val agentKernel: AgentKernel,
    private val logger: Logger
) : ViewModel() {

    private val _deviceNodes = MutableStateFlow<List<DeviceNode>>(emptyList())
    val deviceNodes: StateFlow<List<DeviceNode>> = _deviceNodes.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _executionMessage = MutableStateFlow<String?>(null)
    val executionMessage: StateFlow<String?> = _executionMessage.asStateFlow()

    init {
        // Chu kỳ cập nhật dữ liệu từ các provider để giữ trạng thái sơ đồ đồng bộ thực thời
        viewModelScope.launch {
            while (isActive) {
                refreshDashboardNodes()
                delay(5000)
            }
        }
    }

    fun refreshDashboardNodes() {
        viewModelScope.launch {
            try {
                // Merge tất cả các node từ các DashboardProvider (PHẦN 6)
                val allNodes = providers.flatMap { it.getDashboardNodes() }
                _deviceNodes.value = allNodes
            } catch (e: Exception) {
                logger.e("DashboardViewModel", "Lỗi nạp danh sách sơ đồ: ${e.message}", e)
            }
        }
    }

    /**
     * Gửi yêu cầu điều khiển về AgentKernel dưới dạng text-command hoặc tham số (PHẦN 8).
     * AgentKernel tiếp tục là trung tâm điều phối duy nhất cho cả chat và GUI.
     */
    fun sendDeviceAction(node: DeviceNode, action: String, params: Map<String, Any> = emptyMap()) {
        viewModelScope.launch {
            _isProcessing.value = true
            _executionMessage.value = null

            // Tạo câu lệnh tương đương từ hành động trên UI của người dùng
            val command = when (node.type) {
                DeviceType.LIGHT, DeviceType.SWITCH, DeviceType.PUMP -> {
                    if (action.uppercase() == "ON") "bật ${node.name}" else "tắt ${node.name}"
                }
                DeviceType.CAMERA -> {
                    if (action.uppercase() == "LIVE") "xem camera ${node.name}" else "chụp ảnh từ ${node.name}"
                }
                DeviceType.LOCK -> {
                    if (action.uppercase() == "UNLOCK") "mở khóa ${node.name}" else "khóa ${node.name}"
                }
                DeviceType.FLYCAM -> {
                    "${action.lowercase()} flycam ${node.name}"
                }
                DeviceType.ROBOT -> {
                    "${action.lowercase()} robot ${node.name}"
                }
                else -> "${action.lowercase()} ${node.name}"
            }

            logger.d("DashboardViewModel", "Gửi lệnh sơ đồ đến AgentKernel: $command")
            
            try {
                val result = agentKernel.process(command)
                _executionMessage.value = when (result) {
                    is PluginResult.Success -> {
                        val msg = (result.data as? Map<*, *>)?.get("message") as? String
                        "✅ ${msg ?: "Đã thực hiện thành công"}"
                    }
                    is PluginResult.Failure -> "❌ Lỗi: ${result.error}"
                    is PluginResult.NeedMoreInfo -> "⚠️ ${result.question}"
                }
            } catch (e: Exception) {
                _executionMessage.value = "❌ Exception: ${e.message}"
                logger.e("DashboardViewModel", "Lỗi khi xử lý lệnh thiết bị: ${e.message}", e)
            } finally {
                _isProcessing.value = false
                refreshDashboardNodes() // Nạp lại sơ đồ ngay sau khi thực thi
            }
        }
    }

    fun clearExecutionMessage() {
        _executionMessage.value = null
    }
}