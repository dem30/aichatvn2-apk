package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigProvider
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
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) : ViewModel() {

    val deviceNodes: StateFlow<List<DeviceNode>> = deviceRegistry.deviceNodes

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _executionMessage = MutableStateFlow<String?>(null)
    val executionMessage: StateFlow<String?> = _executionMessage.asStateFlow()

    // ✅ MỚI: Đường dẫn file ảnh sơ đồ nhà do người dùng tự tải lên, làm nền cho Dashboard.
    // null nghĩa là chưa có ảnh -> UI sẽ hiển thị canvas lưới + icon 🏠 mặc định như trước.
    private val _floorplanPath = MutableStateFlow<String?>(null)
    val floorplanPath: StateFlow<String?> = _floorplanPath.asStateFlow()

    // ✅ MỚI: Tỷ lệ co giãn của ảnh sơ đồ nền, chỉnh qua Slider trong Menu (0.5x - 4.0x)
    private val _floorplanScale = MutableStateFlow(1f)
    val floorplanScale: StateFlow<Float> = _floorplanScale.asStateFlow()

    // ✅ ĐÃ THÊM: Khối khởi tạo ViewModel tự động làm mới sơ đồ thiết bị ngầm dưới nền khi mở màn hình
    init {
        refreshDashboardNodes()
        loadFloorplanPath()
        loadFloorplanScale()
    }

    private fun loadFloorplanPath() {
        viewModelScope.launch {
            val savedPath = configProvider.getString(FLOORPLAN_PATH_KEY, "")
            // Kiểm tra file còn tồn tại trên đĩa để tránh hiển thị ảnh vỡ nếu file bị xoá thủ công
            _floorplanPath.value = savedPath.takeIf { it.isNotBlank() && File(it).exists() }
        }
    }

    // ✅ MỚI: Nạp tỷ lệ sơ đồ đã lưu, mặc định 1.0x nếu chưa từng chỉnh
    private fun loadFloorplanScale() {
        viewModelScope.launch {
            val savedScaleStr = configProvider.getString(FLOORPLAN_SCALE_KEY, "1.0")
            _floorplanScale.value = (savedScaleStr.toFloatOrNull() ?: 1f).coerceIn(0.5f, 4.0f)
        }
    }

    /**
     * ✅ MỚI: Cập nhật tỷ lệ kích thước sơ đồ nền và lưu lại cấu hình.
     * Range khớp với valueRange 0.5f..4.0f của Slider trong DashboardScreen.
     */
    fun setFloorplanScale(scale: Float) {
        viewModelScope.launch {
            val clampedScale = scale.coerceIn(0.5f, 4.0f)
            configProvider.set(FLOORPLAN_SCALE_KEY, clampedScale.toString())
            _floorplanScale.value = clampedScale
        }
    }

    /**
     * Lưu đường dẫn ảnh sơ đồ nhà mới (đã được copy vào bộ nhớ nội bộ app từ UI) và persist qua AppConfigProvider.
     */
    fun setFloorplanPath(path: String) {
        viewModelScope.launch {
            val oldPath = _floorplanPath.value
            configProvider.set(FLOORPLAN_PATH_KEY, path)
            _floorplanPath.value = path
            // Dọn file ảnh cũ để tránh tích tụ dung lượng bộ nhớ trong theo thời gian
            if (!oldPath.isNullOrBlank() && oldPath != path) {
                withContext(Dispatchers.IO) {
                    try { File(oldPath).delete() } catch (e: Exception) {
                        logger.e("DashboardViewModel", "Xoá ảnh sơ đồ nhà cũ thất bại", e)
                    }
                }
            }
        }
    }

    /**
     * Xoá ảnh sơ đồ nhà hiện tại, quay lại canvas lưới mặc định.
     */
    fun clearFloorplanPath() {
        viewModelScope.launch {
            val oldPath = _floorplanPath.value
            configProvider.delete(FLOORPLAN_PATH_KEY)
            _floorplanPath.value = null
            if (!oldPath.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    try { File(oldPath).delete() } catch (e: Exception) {
                        logger.e("DashboardViewModel", "Xoá file ảnh sơ đồ nhà thất bại", e)
                    }
                }
            }
        }
    }

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
                            // ✅ ĐÃ SỬA: truyền kèm pluginId để DeviceRegistry tự dọn các node
                            // "mồ côi" (camera/thiết bị đã bị xoá) thuộc đúng plugin này, kể cả
                            // khi danh sách nodes trả về rỗng.
                            deviceRegistry.registerNodes(plugin.manifest.id, nodes)
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

                        // Kiểm tra thuộc tính "state" từ finalParams đã gộp thay vì extraParams rỗng
                        val stateValue = finalParams["state"] as? Boolean
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

    companion object {
        private const val FLOORPLAN_PATH_KEY = "dashboard_floorplan_path"
        private const val FLOORPLAN_SCALE_KEY = "dashboard_floorplan_scale"
    }
}