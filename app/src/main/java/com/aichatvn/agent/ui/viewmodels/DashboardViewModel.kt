package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.ui.dashboard.DashboardProvider
import com.aichatvn.agent.ui.dashboard.DeviceNode
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
                val allNodes = providers.flatMap { it.getDashboardNodes() }
                _deviceNodes.value = allNodes
            } catch (e: Exception) {
                logger.e("DashboardViewModel", "Lỗi nạp danh sách sơ đồ: ${e.message}", e)
            }
        }
    }

    /**
     * Thực thi hành động điều khiển thiết bị hướng cấu hình.
     * Dashboard hoàn toàn không có logic nghiệp vụ hay quy ước tham số của từng loại thiết bị.
     */
    fun sendDeviceAction(
        node: DeviceNode,
        actionId: String = node.defaultAction,
        customParams: Map<String, Any> = emptyMap()
    ) {
        viewModelScope.launch {
            _isProcessing.value = true
            _executionMessage.value = null

            // Tìm thông tin cấu hình tương ứng của hành động đang được gọi
            val actionConfig = node.supportedActions.find { it.id == actionId }
            val actionDefaultParams = actionConfig?.defaultParams ?: emptyMap()

            // Hợp nhất tham số theo thứ tự ưu tiên tăng dần:
            // 1. Tham số chung của thiết bị (ví dụ: cameraId)
            // 2. Tham số mặc định riêng cho hành động này (ví dụ: active = true)
            // 3. Tham số động nhận được từ tương tác trực tiếp của người dùng trên UI (ví dụ: toạ độ GPS)
            val mergedParams = node.defaultParams + actionDefaultParams + customParams

            logger.d("DashboardViewModel", "Thực thi trực tiếp: ${node.pluginId}.$actionId | Tham số: $mergedParams")
            
            try {
                val result = agentKernel.executePluginAction(
                    pluginId = node.pluginId,
                    action = actionId,
                    params = mergedParams
                )
                
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
                logger.e("DashboardViewModel", "Lỗi khi xử lý lệnh trực tiếp: ${e.message}", e)
            } finally {
                _isProcessing.value = false
                refreshDashboardNodes()
            }
        }
    }

    fun clearExecutionMessage() {
        _executionMessage.value = null
    }
}