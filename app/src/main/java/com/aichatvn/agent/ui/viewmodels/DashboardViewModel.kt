package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.ScheduleSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,               
    private val scheduleSkill: ScheduleSkill,         
    private val logger: Logger
) : ViewModel() {

    val deviceNodes: StateFlow<List<DeviceNode>> = deviceRegistry.deviceNodes

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _executionMessage = MutableStateFlow<String?>(null)
    val executionMessage: StateFlow<String?> = _executionMessage.asStateFlow()

    private val _floorplanPath = MutableStateFlow<String?>(null)
    val floorplanPath: StateFlow<String?> = _floorplanPath.asStateFlow()

    private val _floorplanScale = MutableStateFlow(1f)
    val floorplanScale: StateFlow<Float> = _floorplanScale.asStateFlow()

    private val _aiRecommendations = MutableStateFlow<List<QAEntity>>(emptyList())
    val aiRecommendations: StateFlow<List<QAEntity>> = _aiRecommendations.asStateFlow()

    init {
        refreshDashboardNodes()
        loadFloorplanPath()
        loadFloorplanScale()
        observePendingPatterns() 
    }

    private fun observePendingPatterns() {
        viewModelScope.launch {
            database.qaDao().getAllQAsFlow("default_user")
                .map { list -> list.filter { it.category == "pending_pattern" } }
                .collect { _aiRecommendations.value = it }
        }
    }

    /**
     * ✅ CẬP NHẬT (Bước 1): Cấu trúc hóa sự kiện phê duyệt thói quen thành JSON để AI nắm bắt
     */



     
                                              // ... [Các phần imports và logic giữ nguyên 100%] ...

    fun approvePattern(pattern: QAEntity) {
        viewModelScope.launch {
            _isProcessing.value = true
            _executionMessage.value = null
            try {
                val segments = pattern.question.split(":")
                if (segments.size >= 6 && segments[0] == "pattern") {
                    val source = segments[1]     
                    val sourceId = segments[2]   
                    val eventType = segments[3]  
                    val value = segments[4]      
                    val hourStr = segments[5].removeSuffix("h")
                    val hour = hourStr.toIntOrNull() ?: 12

                    val cronExpr = "0 $hour * * *" 

                    if (source == "tuya") {
                        val paramsMap = mapOf(
                            "device" to sourceId,
                            "state" to value.toBoolean()
                        )

                        val executionParams = mapOf(
                            "pluginId" to "smart_switch",
                            "action" to "set",
                            "cron" to cronExpr,
                            "params" to paramsMap,
                            "label" to "Tự động: $sourceId"
                        )

                        val result = withContext(Dispatchers.IO) {
                            scheduleSkill.execute("add", executionParams)
                        }

                        when (result) {
                            is PluginResult.Success -> {
                                withContext(Dispatchers.IO) {
                                    database.qaDao().insertQA(
                                        pattern.copy(
                                            category = "approved_pattern",
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )

                                    val approvedPayload = org.json.JSONObject().apply {
                                        put("pattern_id", pattern.id)
                                        put("device_name", sourceId)
                                        put("action", "approve")
                                        put("cron_expression", cronExpr)
                                        put("value", value)
                                    }.toString()

                                    database.eventLogDao().insertLog(
                                        com.aichatvn.agent.data.model.EventLogEntity(
                                            id = java.util.UUID.randomUUID().toString(),
                                            timestamp = System.currentTimeMillis(),
                                            source = "system",
                                            sourceId = "brain",
                                            eventType = "pattern_approved",
                                            value = approvedPayload,
                                            summary = "Đã phê duyệt đề xuất tự động hóa: ${if (value.toBoolean()) "Bật" else "Tắt"} $sourceId lúc ${hour}h hằng ngày."
                                        )
                                    )

                                    updatePendingPatternsCountInWorldState()
                                }
                                val isDuplicate = (result.data as? Map<*, *>)?.get("duplicate") as? Boolean ?: false
                                _executionMessage.value = if (isDuplicate) {
                                    "ℹ️ Thói quen này đã có lịch tự động từ trước, không tạo thêm bản trùng."
                                } else {
                                    "✅ Đã thiết lập lịch hằng ngày: ${if (value.toBoolean()) "Bật" else "Tắt"} $sourceId lúc ${hour}h!"
                                }
                            }
                            is PluginResult.Failure -> {
                                _executionMessage.value = "❌ Không thể tạo lịch trình: ${result.error}"
                            }
                            is PluginResult.NeedMoreInfo -> {
                                _executionMessage.value = "⚠️ Yêu cầu thông tin thêm: ${result.question}"
                            }
                        }
                    } else {
                        _executionMessage.value = "❌ Hệ thống hiện chưa hỗ trợ tự tạo lịch cho nguồn: $source"
                    }
                } else {
                    _executionMessage.value = "❌ Định dạng đề xuất thói quen không chính xác"
                }
            } catch (e: Exception) {
                _executionMessage.value = "❌ Lỗi phê duyệt đề xuất: ${e.message}"
                logger.e("DashboardViewModel", "Lỗi duyệt pattern", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun ignorePattern(pattern: QAEntity) {
        viewModelScope.launch {
            _executionMessage.value = null
            try {
                // ✅ ĐÃ SỬA (Bước 1): Thêm guard-clause bảo vệ chặt chẽ tránh lỗi bóc tách sai định dạng câu hỏi
                val segments = pattern.question.split(":")
                if (segments.size >= 6 && segments[0] == "pattern") {
                    val sourceId = segments[2]
                    val value = segments[4]
                    val hourStr = segments[5].removeSuffix("h")

                    withContext(Dispatchers.IO) {
                        database.qaDao().insertQA(
                            pattern.copy(
                                category = "ignored_pattern",
                                timestamp = System.currentTimeMillis()
                            )
                        )

                        val ignoredPayload = org.json.JSONObject().apply {
                            put("pattern_id", pattern.id)
                            put("device_name", sourceId)
                            put("action", "ignore")
                        }.toString()

                        database.eventLogDao().insertLog(
                            com.aichatvn.agent.data.model.EventLogEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                source = "system",
                                sourceId = "brain",
                                eventType = "pattern_ignored",
                                value = ignoredPayload,
                                summary = "Đã bỏ qua đề xuất tự động hóa: ${if (value.toBoolean()) "Bật" else "Tắt"} $sourceId lúc ${hourStr}h hằng ngày."
                            )
                        )

                        updatePendingPatternsCountInWorldState()
                    }
                    _executionMessage.value = "🔕 Đã bỏ qua đề xuất này."
                } else {
                    _executionMessage.value = "❌ Định dạng đề xuất thói quen không chính xác"
                }
            } catch (e: Exception) {
                _executionMessage.value = "❌ Lỗi khi bỏ qua: ${e.message}"
                logger.e("DashboardViewModel", "Lỗi bỏ qua pattern", e)
            }
        }
    }

    // ✅ ĐÃ SỬA (Bước 1): Sử dụng WorldStateHelper.setAttribute để MERGE dữ liệu của system:brain
    private suspend fun updatePendingPatternsCountInWorldState() = withContext(Dispatchers.IO) {
        val pendingCount = database.qaDao().getAllQAs("default_user").count { it.category == "pending_pattern" }
        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
            database.worldStateDao(), "system", "brain", "pending_patterns_count", pendingCount.toString()
        )
        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
            database.worldStateDao(), "system", "brain", "last_interaction_run", System.currentTimeMillis().toString()
        )
    }





    

    private fun loadFloorplanPath() {
        viewModelScope.launch {
            val savedPath = configProvider.getString(FLOORPLAN_PATH_KEY, "")
            _floorplanPath.value = savedPath.takeIf { it.isNotBlank() && File(it).exists() }
        }
    }

    private fun loadFloorplanScale() {
        viewModelScope.launch {
            val savedScaleStr = configProvider.getString(FLOORPLAN_SCALE_KEY, "1.0")
            _floorplanScale.value = (savedScaleStr.toFloatOrNull() ?: 1f).coerceIn(0.5f, 4.0f)
        }
    }

    fun setFloorplanScale(scale: Float) {
        viewModelScope.launch {
            val clampedScale = scale.coerceIn(0.5f, 4.0f)
            configProvider.set(FLOORPLAN_SCALE_KEY, clampedScale.toString())
            _floorplanScale.value = clampedScale
        }
    }

    fun setFloorplanPath(path: String) {
        viewModelScope.launch {
            val oldPath = _floorplanPath.value
            configProvider.set(FLOORPLAN_PATH_KEY, path)
            _floorplanPath.value = path
            if (!oldPath.isNullOrBlank() && oldPath != path) {
                withContext(Dispatchers.IO) {
                    try { File(oldPath).delete() } catch (e: Exception) {
                        logger.e("DashboardViewModel", "Xoá ảnh sơ đồ nhà cũ thất bại", e)
                    }
                }
            }
        }
    }

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

    fun sendDeviceAction(node: DeviceNode, action: DeviceAction, extraParams: Map<String, Any>) {
        viewModelScope.launch {
            _isProcessing.value = true
            _executionMessage.value = null
            try {
                val finalParams = node.defaultParams + action.defaultParams + extraParams
                val result = withContext(Dispatchers.IO) {
                    agentKernel.executePluginAction(node.pluginId, action.id, finalParams)
                }

                when (result) {
                    is PluginResult.Success -> {
                        val msg = (result.data as? Map<*, *>)?.get("message") as? String
                            ?: "✅ Đã thực hiện thành công"
                        _executionMessage.value = msg

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