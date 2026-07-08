package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
    private val agentKernel: AgentKernel,
    private val logger: Logger
) : ViewModel() {

    val deviceNodes: StateFlow<List<DeviceNode>> = deviceRegistry.deviceNodes

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _executionMessage = MutableStateFlow<String?>(null)
    val executionMessage: StateFlow<String?> = _executionMessage.asStateFlow()

    fun updateNodePosition(id: String, x: Float, y: Float) {
        deviceRegistry.updateNodeAndPersist(id) { current ->
            current.copy(x = x, y = y)
        }
    }

    fun refreshDashboardNodes() {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val activePlugins = agentKernel.getAvailablePluginsForUI()
                withContext(Dispatchers.IO) {
                    activePlugins.forEach { plugin ->
                        if (plugin.manifest.capabilities.dashboard) {
                            val nodes = plugin.getDashboardNodes()
                            deviceRegistry.registerNodes(nodes)
                        }
                    }
                }
                _executionMessage.value = "✅ Sơ đồ thiết bị đã được làm mới"
            } catch (e: Exception) {
                _executionMessage.value = "❌ Lỗi làm mới: ${e.message}"
                logger.e("DashboardViewModel", "Làm mới sơ đồ thiết bị thất bại", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // Nhận DeviceAction thay vì actionId (String) để merge được action.defaultParams
    fun sendDeviceAction(node: DeviceNode, action: DeviceAction, extraParams: Map<String, Any>) {
        viewModelScope.launch {
            _isProcessing.value = true
            _executionMessage.value = null
            try {
                // Merge đủ 3 lớp params: node defaults + action defaults + caller extras
                val finalParams = node.defaultParams + action.defaultParams + extraParams
                val result = withContext(Dispatchers.IO) {
                    agentKernel.executePluginAction(node.pluginId, action.id, finalParams)
                }

                when (result) {
                    is PluginResult.Success -> {
                        val msg = (result.data as? Map<*, *>)?.get("message") as? String
                            ?: "✅ Đã thực hiện thành công"
                        _executionMessage.value = msg

                        val stateValue = extraParams["state"] as? Boolean
                        if (stateValue != null) {
                            deviceRegistry.updateNode(node.id) { current ->
                                current.copy(
                                    online = true,
                                    status = if (stateValue) "Đang bật" else "Đang tắt",
                                    lastSeen = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                    is PluginResult.Failure -> {
                        _executionMessage.value = "❌ Thất bại: ${result.error}"
                    }
                    is PluginResult.NeedMoreInfo -> {
                        _executionMessage.value = "⚠️ Trợ lý: ${result.question}"
                    }
                }
            } catch (e: Exception) {
                _executionMessage.value = "❌ Lỗi thực thi: ${e.message}"
                logger.e("DashboardViewModel", "Lỗi gửi lệnh ${action.id} tới node ${node.id}", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun sendDeviceAction(node: DeviceNode, actionId: String, extraParams: Map<String, Any>) {
        val action = node.supportedActions.find { it.id == actionId }
            ?: DeviceAction(id = actionId, title = actionId, icon = "")
        sendDeviceAction(node, action, extraParams)
    }

    fun clearExecutionMessage() {
        _executionMessage.value = null
    }
}