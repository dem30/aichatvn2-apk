package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.local.DeviceIdProvider
import com.aichatvn.agent.data.remote.InviteApiService
import com.aichatvn.agent.data.remote.InviteResult
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

// ✅ MỚI: Trạng thái riêng cho luồng "Mời bạn dùng thử" — tách khỏi executionMessage (dùng
// chung cho lệnh thiết bị) vì UI cần biết chính xác lúc nào để mở Share Sheet (chỉ khi Success),
// khác với executionMessage chỉ cần hiện Snackbar.
sealed class InviteUiState {
    object Idle : InviteUiState()
    object Loading : InviteUiState()
    data class Success(val code: String, val baseUrl: String) : InviteUiState()
    data class Error(val message: String) : InviteUiState()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRegistry: DeviceRegistry,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,               
    private val scheduleSkill: ScheduleSkill,         
    private val logger: Logger,
    // ✅ MỚI: 2 dependency cho tính năng mời/kích hoạt — xem InviteApiService.kt/DeviceIdProvider.kt
    private val deviceIdProvider: DeviceIdProvider,
    private val inviteApiService: InviteApiService
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

    // ✅ MỚI: Lưu lại mức zoom/pan (kéo-thả bằng 2 ngón tay) mà người dùng tự chỉnh trên sơ đồ
    // thiết bị — KHÁC với floorplanScale (tỷ lệ thiết kế ảnh, chỉnh qua dialog "Tỷ lệ hiện tại").
    // Trước đây zoomScale/panOffset chỉ là biến remember cục bộ trong DashboardScreen nên mất
    // ngay khi rời màn hình rồi quay lại — không có nơi nào ghi giá trị này xuống DB cả.
    private val _viewportZoom = MutableStateFlow(1f)
    val viewportZoom: StateFlow<Float> = _viewportZoom.asStateFlow()

    private val _viewportPanX = MutableStateFlow(0f)
    val viewportPanX: StateFlow<Float> = _viewportPanX.asStateFlow()

    private val _viewportPanY = MutableStateFlow(0f)
    val viewportPanY: StateFlow<Float> = _viewportPanY.asStateFlow()

    // ✅ MỚI: tín hiệu riêng báo "đã đọc xong DB", TÁCH BIỆT khỏi bản thân zoom/pan — vì
    // _viewportZoom/_viewportPanX/_viewportPanY khởi tạo sẵn bằng giá trị mặc định (1f/0f/0f)
    // ngay từ dòng khai báo, còn loadViewportState() chỉ cập nhật giá trị thật sau đó BẤT ĐỒNG
    // BỘ (viewModelScope.launch, không chờ ở init). Nếu DashboardScreen chỉ dựa vào việc "giá
    // trị zoom/pan đổi" để biết đã load xong, nó sẽ không phân biệt được giữa "chưa load nên
    // đang là mặc định" và "đã load xong và giá trị thật đúng là mặc định" — dẫn tới việc áp
    // dụng nhầm mặc định vào UI trước khi DB kịp trả lời, rồi bỏ lỡ vĩnh viễn giá trị thật.
    private val _viewportLoaded = MutableStateFlow(false)
    val viewportLoaded: StateFlow<Boolean> = _viewportLoaded.asStateFlow()

    private val _aiRecommendations = MutableStateFlow<List<QAEntity>>(emptyList())
    val aiRecommendations: StateFlow<List<QAEntity>> = _aiRecommendations.asStateFlow()

    // ✅ MỚI: expose cho DashboardScreen theo dõi luồng tạo mã mời
    private val _inviteUiState = MutableStateFlow<InviteUiState>(InviteUiState.Idle)
    val inviteUiState: StateFlow<InviteUiState> = _inviteUiState.asStateFlow()

    init {
        refreshDashboardNodes()
        loadFloorplanPath()
        loadFloorplanScale()
        loadViewportState()
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
            // ✅ SỬA LỖI: key cục bộ thuần UI (tỉ lệ sơ đồ nhà), không liên quan Gateway —
            // affectsConnection = false để không làm WebhookGatewayService ngắt-nối lại SSE
            // oan (xem giải thích đầy đủ ở AppConfigProvider.set()).
            configProvider.set(FLOORPLAN_SCALE_KEY, clampedScale.toString(), affectsConnection = false)
            _floorplanScale.value = clampedScale
        }
    }

    private fun loadViewportState() {
        viewModelScope.launch {
            _viewportZoom.value = (configProvider.getString(VIEWPORT_ZOOM_KEY, "1.0").toFloatOrNull() ?: 1f)
                .coerceIn(0.5f, 3.0f)
            _viewportPanX.value = configProvider.getString(VIEWPORT_PAN_X_KEY, "0.0").toFloatOrNull() ?: 0f
            _viewportPanY.value = configProvider.getString(VIEWPORT_PAN_Y_KEY, "0.0").toFloatOrNull() ?: 0f
            _viewportLoaded.value = true
        }
    }

    // ✅ MỚI: Gọi hàm này (debounce ~500ms) từ DashboardScreen mỗi khi người dùng ngừng
    // pinch/pan sơ đồ, để lần sau mở lại Dashboard sẽ khôi phục đúng góc nhìn đã chỉnh.
    fun saveViewportState(zoom: Float, panX: Float, panY: Float) {
        viewModelScope.launch {
            val clampedZoom = zoom.coerceIn(0.5f, 3.0f)
            // ✅ SỬA LỖI: 3 key cục bộ thuần UI (zoom/pan Dashboard), không liên quan Gateway
            // — affectsConnection = false để không làm ngắt-nối lại SSE oan mỗi lần người
            // dùng pan/zoom sơ đồ nhà (xem giải thích đầy đủ ở AppConfigProvider.set()).
            configProvider.set(VIEWPORT_ZOOM_KEY, clampedZoom.toString(), affectsConnection = false)
            configProvider.set(VIEWPORT_PAN_X_KEY, panX.toString(), affectsConnection = false)
            configProvider.set(VIEWPORT_PAN_Y_KEY, panY.toString(), affectsConnection = false)
            _viewportZoom.value = clampedZoom
            _viewportPanX.value = panX
            _viewportPanY.value = panY
        }
    }

    fun setFloorplanPath(path: String) {
        viewModelScope.launch {
            val oldPath = _floorplanPath.value
            // ✅ SỬA LỖI: key cục bộ thuần UI (đường dẫn ảnh sơ đồ nhà), không liên quan
            // Gateway — affectsConnection = false (xem giải thích đầy đủ ở AppConfigProvider.set()).
            configProvider.set(FLOORPLAN_PATH_KEY, path, affectsConnection = false)
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

    // ✅ MỚI: Gọi khi người dùng bấm nút "Mời bạn dùng thử" trên TopAppBar. Server tự chặn nếu
    // device_id chưa activate hoặc đã quá 5 mã/24h (xem app.py: create_invite()).
    fun createInvite() {
        viewModelScope.launch {
            _inviteUiState.value = InviteUiState.Loading
            val deviceId = deviceIdProvider.getOrCreateDeviceId()
            // ✅ MỚI: server giờ verify activation bằng chữ ký HMAC thay vì tra RAM store
            // (xem app.py: verify_activation_token()) — phải gửi kèm token đã lưu lúc
            // activate thành công (DeviceIdProvider.markActivated()), không chỉ gửi device_id.
            val activationToken = deviceIdProvider.getActivationToken()
            when (val result = inviteApiService.createInvite(deviceId, activationToken)) {
                is InviteResult.Success -> {
                    val baseUrl = configProvider.getString(
                        com.aichatvn.agent.config.AppConfigDefaults.GLOBAL_GATEWAY_URL, ""
                    )
                    _inviteUiState.value = InviteUiState.Success(result.code, baseUrl)
                }
                is InviteResult.Error -> {
                    _inviteUiState.value = InviteUiState.Error(result.message)
                }
            }
        }
    }

    // ✅ MỚI: Reset về Idle sau khi DashboardScreen đã xử lý xong (mở Share Sheet / hiện lỗi),
    // tránh việc mở lại Share Sheet oan khi Compose recompose lại state cũ.
    fun clearInviteUiState() {
        _inviteUiState.value = InviteUiState.Idle
    }

    companion object {
        private const val FLOORPLAN_PATH_KEY = "dashboard_floorplan_path"
        private const val FLOORPLAN_SCALE_KEY = "dashboard_floorplan_scale"
        private const val VIEWPORT_ZOOM_KEY = "dashboard_viewport_zoom"
        private const val VIEWPORT_PAN_X_KEY = "dashboard_viewport_pan_x"
        private const val VIEWPORT_PAN_Y_KEY = "dashboard_viewport_pan_y"
    }
}