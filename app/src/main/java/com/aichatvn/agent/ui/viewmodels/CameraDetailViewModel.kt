package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.alertActionsFromJson
import com.aichatvn.agent.data.model.alertActionsToJson
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.CallSkill // ✅ MỚI: cần getOrCreateMyDeviceCode() cho kênh alarm push
import com.aichatvn.agent.config.AppConfigDefaults // ✅ MỚI
import com.aichatvn.agent.config.AppConfigProvider // ✅ MỚI
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit // ✅ MỚI
import okhttp3.MediaType.Companion.toMediaType // ✅ MỚI
import okhttp3.RequestBody.Companion.toRequestBody // ✅ MỚI
import javax.inject.Inject
import com.aichatvn.agent.core.plugin.DynamicOptionRegistry // 🌟 MỚI

// ✅ MỚI: trạng thái xác nhận camera thực sự bắn được alarm push tới gateway, dùng cho
// nút "Kiểm tra kết nối" ở màn hình chi tiết camera — xem verifyAlarmConnection().
sealed class AlarmVerifyState {
    object Idle : AlarmVerifyState()
    object Listening : AlarmVerifyState()
    object Success : AlarmVerifyState()
    object TimedOut : AlarmVerifyState()
}

data class CameraConfigDraft(
    val snapshotUrl: String = "",
    val landInfo: String = "",
    val aiPrompt: String = "",
    val aiPositiveKeywords: String = "",
    val aiNegativeKeywords: String = "",
    val enableCooldown: Boolean = true,     // ✅ MỚI
    val enableNotification: Boolean = true,  // ✅ MỚI
  val alertActions: List<AlertActionConfig> = emptyList(), // ✅ MỚI
    // ✅ MỚI: Basic Auth cho camera LAN yêu cầu đăng nhập, để trống nếu camera không cần.
    val snapshotUsername: String = "",
    val snapshotPassword: String = "",
    // ✅ MỚI: URL dự phòng cloud/public — dùng khi LAN không kết nối được (vd đang ở ngoài mạng nhà).
    val snapshotUrlRemote: String = ""

)

data class ScheduleDraft(
    val id: String = "",
    val action: String = "scan",
    val cron: String = "",
    val intervalMinutes: Int = 0,
    val enabled: Boolean = true,
    val force: Boolean = false,
    val label: String = "",
  // ✅ MỚI — ép buộc AI phân tích, bỏ qua pHash & cooldown
    val alertActions: List<AlertActionConfig> = emptyList()   // ✅ MỚI — hành động cảnh báo riêng cho lịch này, để trống = dùng mặc định của camera
)

@HiltViewModel
class CameraDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val snapshotFetcher: SnapshotFetcher,
    private val agentKernel: com.aichatvn.agent.core.AgentKernel, // ✅ MỚI — lấy danh sách plugin cho dropdown, giống ScheduleViewModel
    val optionRegistry: DynamicOptionRegistry, // 🌟 MỚI: Inject Registry trung tâm
    private val configProvider: AppConfigProvider, // ✅ MỚI: đọc Gateway URL/Token cho toggleAlarmPush()/verifyAlarmConnection()
    private val callSkill: CallSkill, // ✅ MỚI: getOrCreateMyDeviceCode() — cùng deviceCode đang dùng cho kênh /call/stream
    // ✅ MỚI: probe RTSP/ONVIF — @Singleton @Inject constructor sẵn, không cần khai báo @Provides riêng.
    private val capabilityProber: com.aichatvn.agent.tools.camera.CameraCapabilityProber,
    private val logger: Logger
) : ViewModel() {

  // ✅ MỚI: Danh sách plugin routable để dropdown chọn (loại "camera" nếu muốn tránh tự-trigger đệ quy,
    // nhưng vẫn cho phép vì use-case hợp lệ: cam A phát hiện → bật smart_mode cam B)
    val alertActionPlugins: List<com.aichatvn.agent.core.plugin.Plugin> =
        agentKernel.getAvailablePluginsForUI()

    val cameraId: String = (savedStateHandle.get<String>("cameraId") ?: "").trim()

    private val _camera = MutableStateFlow<CameraConfigEntity?>(null)
    val camera: StateFlow<CameraConfigEntity?> = _camera.asStateFlow()

    private val _smartMode = MutableStateFlow(false)
    val smartMode: StateFlow<Boolean> = _smartMode.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, Any>?>(null)
    val diagnostics: StateFlow<Map<String, Any>?> = _diagnostics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _liveSnapshot = MutableStateFlow<ByteArray?>(null)
    val liveSnapshot: StateFlow<ByteArray?> = _liveSnapshot.asStateFlow()

    private val _configDraft = MutableStateFlow<CameraConfigDraft?>(null)
    val configDraft: StateFlow<CameraConfigDraft?> = _configDraft.asStateFlow()

    private val _configSaveResult = MutableStateFlow<String?>(null)
    val configSaveResult: StateFlow<String?> = _configSaveResult.asStateFlow()

    private val _schedules = MutableStateFlow<List<ScheduleEntity>>(emptyList())
    val schedules: StateFlow<List<ScheduleEntity>> = _schedules.asStateFlow()

    private val _scheduleDraft = MutableStateFlow<ScheduleDraft?>(null)
    val scheduleDraft: StateFlow<ScheduleDraft?> = _scheduleDraft.asStateFlow()

    private val _scheduleResult = MutableStateFlow<String?>(null)
    val scheduleResult: StateFlow<String?> = _scheduleResult.asStateFlow()

    val recentAlerts: StateFlow<List<AlertEntity>> = database.alertDao()
        .getAlertsByCameraFlow(cameraId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )



        
    init {
        loadCamera()
        loadSchedules()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val diagMap = cameraSkill.getDiagnostics()
                _diagnostics.value = diagMap[cameraId] as? Map<String, Any>
                delay(5000)
            }
        }
    }



    

    fun loadCamera() {
        viewModelScope.launch {
            val cam = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(cameraId)
            }
            _camera.value = cam
            cam?.let {
                _smartMode.value = it.smartMode == 1
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONFIG INLINE
    // ═════════════════════════════════════════════════════════════════════════

    fun openConfigEditor() {
        val cam = _camera.value ?: return
        _configDraft.value = CameraConfigDraft(
            snapshotUrl = cam.snapshoturl,
            landInfo = cam.landinfo ?: "",
            aiPrompt = cam.aiPrompt,
            aiPositiveKeywords = cam.aiPositiveKeywords,
            aiNegativeKeywords = cam.aiNegativeKeywords,
            enableCooldown = cam.enableCooldown == 1,
            enableNotification = cam.enableNotification == 1,
            snapshotUsername = cam.snapshotUsername ?: "",
            snapshotPassword = cam.snapshotPassword ?: "",
            snapshotUrlRemote = cam.snapshotUrlRemote ?: "",

          alertActions = alertActionsFromJson(cam.alertActions) // ✅ MỚI
        

          
        )
    }

    fun addAlertAction(cfg: AlertActionConfig) {
        updateConfigDraft { copy(alertActions = alertActions + cfg) }
    }

    // ✅ MỚI
    fun removeAlertAction(index: Int) {
        updateConfigDraft { copy(alertActions = alertActions.filterIndexed { i, _ -> i != index }) }
    }
    
    fun closeConfigEditor() {
        _configDraft.value = null
        _configSaveResult.value = null
    }

    fun updateConfigDraft(update: CameraConfigDraft.() -> CameraConfigDraft) {
        _configDraft.value = _configDraft.value?.update()
    }

    fun saveConfig() {
        val draft = _configDraft.value ?: return
        val cam = _camera.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val updated = cam.copy(
                    snapshoturl = draft.snapshotUrl.trim(),
                    landinfo = draft.landInfo.trim().ifEmpty { null },
                    aiPrompt = draft.aiPrompt.trim(),
                    aiPositiveKeywords = draft.aiPositiveKeywords.trim(),
                    aiNegativeKeywords = draft.aiNegativeKeywords.trim(),
                    enableCooldown = if (draft.enableCooldown) 1 else 0,
                    enableNotification = if (draft.enableNotification) 1 else 0,
                    snapshotUsername = draft.snapshotUsername.trim().ifBlank { null },
                    snapshotPassword = draft.snapshotPassword.trim().ifBlank { null },
                    snapshotUrlRemote = draft.snapshotUrlRemote.trim().ifBlank { null },


                  alertActions = alertActionsToJson(draft.alertActions) // ✅ MỚI
                
                  
                )
                withContext(Dispatchers.IO) {
                    database.cameraDao().updateCamera(updated)
                }

                // ✅ SỬA: saveConfig() trước đây chỉ ghi Room, không gọi qua CameraSkill nên
                // không đồng bộ DeviceRegistry (nguồn dữ liệu Dashboard) và không thử kết nối
                // lại ngay — khiến isOnline trong Room (đọc bởi CameraDetailScreen) giữ nguyên
                // giá trị "Mất kết nối" cũ từ lần scan thất bại gần nhất, dù URL/mật khẩu vừa
                // sửa đã đúng, trong khi Dashboard lại hiển thị trạng thái khác do lệch cache.
                // Chủ động quét lại ngay sau khi lưu để cả hai nguồn cùng cập nhật.
                withContext(Dispatchers.IO) {
                    cameraSkill.resetCircuitBreaker(cameraId)
                    cameraSkill.scanCamera(cameraId, isDailyReport = false)
                }

                loadCamera()
                _configDraft.value = null
                _configSaveResult.value = "✅ Đã lưu cấu hình"
                logger.i("CameraDetailViewModel", "saveConfig OK cameraId=$cameraId")
            } catch (e: Exception) {
                _configSaveResult.value = "❌ Lỗi: ${e.message}"
                logger.e("CameraDetailViewModel", "saveConfig error: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearConfigSaveResult() {
        _configSaveResult.value = null
    }

    // ✅ MỚI: đóng dialog "Dán URL này vào camera" sau khi người dùng đã copy.
    fun clearAlarmPushUrl() {
        _alarmPushUrl.value = null
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CRUD SCHEDULE
    // ═════════════════════════════════════════════════════════════════════════

    fun loadSchedules() {
        viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) {
                    database.scheduleDao().getAllSchedules()
                }
                val filtered = withContext(Dispatchers.Default) {
                    all.filter { schedule ->
                        runCatching {
                            JSONObject(schedule.params).optString("cameraId").trim() == cameraId.trim()
                        }.getOrDefault(false)
                    }
                }
                _schedules.value = filtered
                logger.d("CameraDetailViewModel", "loadSchedules: ${_schedules.value.size} lịch cho cameraId=$cameraId")
            } catch (e: Exception) {
                logger.e("CameraDetailViewModel", "loadSchedules error: ${e.message}", e)
            }
        }
    }

    fun openAddSchedule() {
        _scheduleDraft.value = ScheduleDraft()
        _scheduleResult.value = null
    }

    fun openEditSchedule(schedule: ScheduleEntity) {
    val p = try { JSONObject(schedule.params) } catch (e: Exception) { JSONObject() }
    _scheduleDraft.value = ScheduleDraft(
        id = schedule.id,
        action = schedule.action,
        cron = schedule.cron,
        intervalMinutes = schedule.intervalMinutes,
        enabled = schedule.enabled == 1,
        force = p.optBoolean("force", false),
        label = schedule.label ?: "",                        // ✅ MỚI: Đọc nhãn cũ lên Draft
        alertActions = alertActionsFromJson(p.optString("alertActions", "[]"))
    )
    _scheduleResult.value = null
}


    

    fun closeScheduleEditor() {
        _scheduleDraft.value = null
        _scheduleResult.value = null
    }

    fun updateScheduleDraft(update: ScheduleDraft.() -> ScheduleDraft) {
        _scheduleDraft.value = _scheduleDraft.value?.update()
    }

    // ✅ MỚI: thêm/xoá 1 hành động cảnh báo riêng cho lịch đang sửa (song song với
    // addAlertAction/removeAlertAction ở cấp camera bên trên)
    fun addScheduleAlertAction(cfg: AlertActionConfig) {
        updateScheduleDraft { copy(alertActions = alertActions + cfg) }
    }

    fun removeScheduleAlertAction(index: Int) {
        updateScheduleDraft { copy(alertActions = alertActions.filterIndexed { i, _ -> i != index }) }
    }

    fun saveSchedule() {
        val draft = _scheduleDraft.value ?: return
        if (draft.cron.isBlank() && draft.intervalMinutes <= 0) {
            _scheduleResult.value = "❌ Cần nhập cron hoặc interval"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val isNew = draft.id.isBlank()
                // ✅ MỚI: sinh scheduleId TRƯỚC để nhét luôn vào params — nhờ vậy
                // CameraSkill có thể tag scheduleId vào AlertEntity khi lưu lịch sử cảnh báo
                val scheduleId = if (isNew) UUID.randomUUID().toString() else draft.id

                val params = JSONObject().apply {
                    put("cameraId", cameraId.trim())
                    put("scheduleId", scheduleId)                 // ✅ MỚI
                    put("force", draft.force)                     // ✅ MỚI
                    if (draft.alertActions.isNotEmpty()) {
                        put("alertActions", alertActionsToJson(draft.alertActions)) // ✅ MỚI
                    }
                }.toString()



                
                val schedule = ScheduleEntity(
    id = scheduleId,
    pluginId = "camera",
    action = draft.action,
    params = params,
    cron = draft.cron.trim(),
    intervalMinutes = draft.intervalMinutes,
    enabled = if (draft.enabled) 1 else 0,
    label = draft.label.trim(),                             // ✅ MỚI: Giữ lại hoặc cập nhật label mới
    createdAt = if (isNew) System.currentTimeMillis() else {
        _schedules.value.find { it.id == draft.id }?.createdAt
            ?: System.currentTimeMillis()
    }
)


                

                withContext(Dispatchers.IO) {
                    if (isNew) {
                        database.scheduleDao().insertSchedule(schedule)
                    } else {
                        database.scheduleDao().updateSchedule(schedule)
                    }
                }

                _scheduleResult.value = if (isNew) "✅ Đã thêm lịch mới" else "✅ Đã cập nhật lịch"
                logger.i("CameraDetailViewModel", "saveSchedule: hoàn tất id=${schedule.id} cameraId=$cameraId")
                loadSchedules()
                _scheduleDraft.value = null
            } catch (e: Exception) {
                _scheduleResult.value = "❌ Lỗi: ${e.message}"
                logger.e("CameraDetailViewModel", "saveSchedule error: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            try {
                val newEnabled = if (schedule.enabled == 1) 0 else 1
                withContext(Dispatchers.IO) {
                    database.scheduleDao().toggleSchedule(schedule.id, newEnabled)
                }
                loadSchedules()
                logger.d("CameraDetailViewModel", "toggleSchedule id=${schedule.id} enabled=$newEnabled")
            } catch (e: Exception) {
                _scheduleResult.value = "❌ Lỗi toggle: ${e.message}"
                logger.e("CameraDetailViewModel", "toggleSchedule error: ${e.message}", e)
            }
        }
    }

    fun deleteSchedule(scheduleId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.scheduleDao().deleteSchedule(scheduleId)
                }
                loadSchedules()
                _scheduleResult.value = "🗑️ Đã xoá lịch"
                logger.i("CameraDetailViewModel", "deleteSchedule id=$scheduleId cameraId=$cameraId")
            } catch (e: Exception) {
                _scheduleResult.value = "❌ Lỗi xoá: ${e.message}"
                logger.e("CameraDetailViewModel", "deleteSchedule error: ${e.message}", e)
            }
        }
    }

    fun clearScheduleResult() {
        _scheduleResult.value = null
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GIỮ NGUYÊN CÁC HÀM CŨ
    // ═════════════════════════════════════════════════════════════════════════

    fun toggleActive() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newManualOff = if (cam.manualOff == 0) 1 else 0
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCamera(cam.copy(manualOff = newManualOff))
            }
            loadCamera()
        }
    }

    fun toggleSmartMode() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newMode = !_smartMode.value
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCameraSmartMode(cam.id, if (newMode) 1 else 0)
            }
            _smartMode.value = newMode
            logger.i("CameraDetailViewModel", "CameraSmartMode ${cam.id} → $newMode")
        }
    }

    // ✅ MỚI: Chuyển đổi nhanh trạng thái Cooldown hoãn quét từ UI chi tiết (không qua configDraft)
    fun toggleCooldown() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newCooldown = if (cam.enableCooldown == 1) 0 else 1
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCamera(cam.copy(enableCooldown = newCooldown))
            }
            loadCamera()
            // ✅ SỬA: trước đây badge "⏳ Đang cooldown" chỉ được làm mới ở tick polling 5s
            // tiếp theo (init{} block), nên sau khi gạt tắt switch, người dùng vẫn thấy badge
            // cũ trong tối đa 5s dù DB đã cập nhật. Ép refresh diagnostics ngay sau khi ghi DB
            // để UI phản hồi tức thì.
            // ✅ SỬA LẦN 2: gọi cameraSkill.getDiagnostics() ngay sau đây chỉ đọc cache
            // _diagnostics.value cũ (chưa tính lại), vì updateDiagnostics() thật là private và
            // chỉ chạy trong luồng quét ảnh — nên badge vẫn không đổi cho tới lượt quét kế tiếp.
            // Gọi refreshDiagnostics() (public, mới thêm trong CameraSkill) để ép tính lại ngay
            // với enableCooldown vừa ghi DB, thay vì chỉ đọc lại giá trị cũ.
            withContext(Dispatchers.IO) {
                cameraSkill.refreshDiagnostics()
                val diagMap = cameraSkill.getDiagnostics()
                _diagnostics.value = diagMap[cameraId] as? Map<String, Any>
            }
            logger.i("CameraDetailViewModel", "toggleCooldown ${cam.id} → $newCooldown")
        }
    }

    // ✅ MỚI: Chuyển đổi nhanh trạng thái Nhận thông báo từ UI chi tiết (không qua configDraft)
    fun toggleNotification() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newNotification = if (cam.enableNotification == 1) 0 else 1
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCamera(cam.copy(enableNotification = newNotification))
            }
            loadCamera()
            logger.i("CameraDetailViewModel", "toggleNotification ${cam.id} → $newNotification")
        }
    }

    // ✅ MỚI: URL đầy đủ (kèm secret) để người dùng dán vào ô "Alarm Server URL" trong
    // web UI của camera — chỉ có giá trị ngay sau khi bật toggle thành công, dùng để
    // UI hiện dialog "Copy URL này" 1 lần; không lưu lại đâu khác ngoài bộ nhớ này.
    private val _alarmPushUrl = MutableStateFlow<String?>(null)
    val alarmPushUrl: StateFlow<String?> = _alarmPushUrl.asStateFlow()

    private val _alarmVerifyState = MutableStateFlow<AlarmVerifyState>(AlarmVerifyState.Idle)
    val alarmVerifyState: StateFlow<AlarmVerifyState> = _alarmVerifyState.asStateFlow()

    // ✅ MỚI: trạng thái "Kiểm tra khả năng RTSP/ONVIF" — CHỈ chạy được khi điện thoại đang
    // cùng Wi-Fi LAN với camera (probe gọi thẳng IP nội bộ), xem giải thích đầy đủ trong
    // CameraCapabilityProber.kt. Kết quả lưu vào cam.rtspSupported/onvifSupported/rtspUrl/
    // onvifEventUrl — KHÔNG tự bật rtspEnabled, người dùng vẫn phải tự bật Switch riêng.
    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    // ✅ MỚI: kiểm tra camera có hỗ trợ RTSP/ONVIF không — gọi trực tiếp IP LAN của camera
    // (trích từ cam.snapshoturl), nên CHỈ hoạt động khi máy đang ở cùng Wi-Fi nhà. Xa nhà/dùng
    // 4G sẽ luôn báo "không tìm thấy" dù camera thực sự có hỗ trợ — đây là giới hạn đã biết của
    // giao thức RTSP/ONVIF (kết nối trực tiếp theo IP), không phải lỗi probe.
    fun probeCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = _camera.value ?: return@launch
            _isProbing.value = true
            try {
                val uri = try { java.net.URI(cam.snapshoturl) } catch (e: Exception) { null }
                val host = uri?.host
                if (host.isNullOrBlank()) {
                    _probeResult.value = "❌ Không xác định được địa chỉ IP từ URL ảnh chụp — kiểm tra lại cấu hình camera."
                    return@launch
                }
                val port = if (uri.port > 0) uri.port else 80
                // ✅ SỬA: port đọc từ chính snapshotUrl camera đang dùng (vd 8800 với V380) là
                // port THẬT của camera — truyền vào knownPort để RTSP/HTTP-snapshot thử đúng port
                // đó, thay vì bị nhét nhầm vào httpPort (tham số dành riêng cho ONVIF, mặc định 80
                // — trước đây gây lỗi biên dịch/lệch tham số khi CameraCapabilityProber.probe()
                // thêm knownPort xen giữa httpPort và username).
                // ✅ MỚI: nếu camera này được thêm qua dò tìm ONVIF WS-Discovery, ta đã có sẵn
                // XAddr thật (onvifDeviceServiceUrl, vd "http://192.168.0.104:8899/onvif/device_service").
                // Truyền thẳng vào knownOnvifDeviceUrl để CameraCapabilityProber gọi đúng URL đó,
                // thay vì tự suy đoán "http://$host:80/onvif/device_service" — nhiều camera TQ giá
                // rẻ dùng port ONVIF riêng khác 80 nên suy đoán port 80 sẽ probe sai và báo
                // "không hỗ trợ ONVIF" oan, dù XAddr thật đã dò được từ trước.
                val result = capabilityProber.probe(
                    ip = host, knownPort = port,
                    username = cam.snapshotUsername, password = cam.snapshotPassword,
                    knownOnvifDeviceUrl = cam.onvifDeviceServiceUrl
                )

                val updated = cam.copy(
                    onvifSupported = if (result.onvifSupported) 1 else 0,
                    onvifEventUrl = result.onvifEventUrl,
                    rtspSupported = if (result.rtspSupported) 1 else 0,
                    rtspUrl = result.rtspUrl,
                    // ✅ MỚI: nếu chưa có snapshotUrl xác nhận (URL trống/hỏng) nhưng probe lần
                    // này tìm được ảnh JPEG thật qua HTTP trên port của camera, tự điền vào —
                    // KHÔNG ghi đè URL đang hoạt động sẵn, chỉ lấp khi trước đó trống.
                    snapshoturl = if (cam.snapshoturl.isBlank() && result.snapshotUrl != null)
                        result.snapshotUrl else cam.snapshoturl
                    // rtspEnabled/onvifEnabled KHÔNG đổi ở đây — probe chỉ cập nhật "có hỗ trợ",
                    // bật thật vẫn qua toggleRtspEnabled() do người dùng tự bấm.
                )
                database.cameraDao().updateCamera(updated)
                loadCamera()

                _probeResult.value = if (result.rtspSupported || result.onvifSupported || result.snapshotUrl != null) {
                    "✅ Tìm thấy hỗ trợ: " + listOfNotNull(
                        if (result.rtspSupported) "RTSP" else null,
                        if (result.onvifSupported) "ONVIF" else null,
                        // ✅ MỚI: camera vendor riêng (V380...) không hỗ trợ RTSP/ONVIF nhưng vẫn
                        // có thể có ảnh chụp HTTP thật trên port riêng — báo rõ để người dùng biết
                        // camera đã dùng được, không chỉ im lặng lưu snapshotUrl.
                        if (result.snapshotUrl != null) "Ảnh chụp HTTP" else null
                    ).joinToString(", ")
                } else {
                    "Không phát hiện RTSP/ONVIF/ảnh chụp. Kiểm tra máy đang cùng Wi-Fi với camera, hoặc camera đời quá cũ không hỗ trợ."
                }
                logger.i(
                    "CameraDetailViewModel",
                    "probeCapabilities cameraId=$cameraId rtsp=${result.rtspSupported} " +
                        "onvif=${result.onvifSupported} httpSnapshot=${result.snapshotUrl != null}"
                )
            } catch (e: Exception) {
                _probeResult.value = "❌ Lỗi kiểm tra: ${e.message}"
                logger.e("CameraDetailViewModel", "probeCapabilities lỗi: ${e.message}", e)
            } finally {
                _isProbing.value = false
            }
        }
    }

    fun clearProbeResult() {
        _probeResult.value = null
    }

    // ✅ MỚI: chỉ cho bật khi rtspSupported=1 (đã probe xác nhận) — tránh người dùng bật Switch
    // rồi bấm "Xem trực tiếp" ra màn hình lỗi ngay vì chưa hề có rtspUrl.
    fun toggleRtspEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = _camera.value ?: return@launch
            if (cam.rtspSupported != 1) return@launch
            database.cameraDao().updateCamera(cam.copy(rtspEnabled = if (cam.rtspEnabled == 1) 0 else 1))
            loadCamera()
        }
    }

    // ✅ MỚI: giờ có tác dụng thật — WebhookGatewayService.startOnvifMonitoring() soát DB mỗi
    // 30s, tự bắt/thoát long-poll ONVIF Events theo đúng field này, KHÔNG cần gọi gì thêm ở
    // đây ngoài lưu DB. Chỉ cho bật khi onvifSupported=1 (đã probe xác nhận có Events service).
    fun toggleOnvifEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = _camera.value ?: return@launch
            if (cam.onvifSupported != 1) return@launch
            database.cameraDao().updateCamera(cam.copy(onvifEnabled = if (cam.onvifEnabled == 1) 0 else 1))
            loadCamera()
        }
    }

    // ✅ MỚI: Bật/tắt "Báo động camera" (camera tự đẩy push khi có motion, xem thiết kế
    // /camera/alarm/stream/{deviceCode} phía gateway). Bật → sinh secret mới + trả về URL
    // để người dùng dán vào camera. Tắt → thu hồi secret ở gateway, camera cũ (nếu còn
    // giữ URL) sẽ bị từ chối push sau đó.
    fun toggleAlarmPush() {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = _camera.value ?: return@launch
            val turningOn = cam.enableAlarmPush != 1
            val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
            val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
            if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                logger.w("CameraDetailViewModel", "toggleAlarmPush: thiếu Gateway URL/Token, bỏ qua.")
                return@launch
            }
            val deviceCode = callSkill.getOrCreateMyDeviceCode()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            try {
                if (turningOn) {
                    val payload = JSONObject().apply {
                        put("token", gatewayToken)
                        put("deviceCode", deviceCode)
                        put("cameraId", cameraId)
                    }
                    val body = payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url("$gatewayUrl/camera/alarm_secret")
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.isSuccessful && text.isNotBlank()) {
                            val json = JSONObject(text)
                            val secret = json.optString("secret", "").trim()
                            val pushUrl = json.optString("pushUrl", "").trim()
                            if (secret.isNotEmpty() && pushUrl.isNotEmpty()) {
                                database.cameraDao().updateCamera(
                                    cam.copy(enableAlarmPush = 1, alarmSecret = secret)
                                )
                                _alarmPushUrl.value = pushUrl
                                _alarmVerifyState.value = AlarmVerifyState.Idle
                                logger.i("CameraDetailViewModel", "toggleAlarmPush BẬT cameraId=$cameraId")
                            } else {
                                logger.e("CameraDetailViewModel", "toggleAlarmPush: phản hồi gateway thiếu secret/pushUrl")
                            }
                        } else {
                            logger.e("CameraDetailViewModel", "toggleAlarmPush: gateway lỗi HTTP ${response.code}")
                        }
                    }
                } else {
                    val payload = JSONObject().apply {
                        put("token", gatewayToken)
                        put("deviceCode", deviceCode)
                        put("cameraId", cameraId)
                    }
                    val body = payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url("$gatewayUrl/camera/alarm_secret/revoke")
                        .post(body)
                        .build()
                    client.newCall(request).execute().close()
                    database.cameraDao().updateCamera(
                        cam.copy(enableAlarmPush = 0, alarmSecret = null)
                    )
                    _alarmPushUrl.value = null
                    _alarmVerifyState.value = AlarmVerifyState.Idle
                    logger.i("CameraDetailViewModel", "toggleAlarmPush TẮT cameraId=$cameraId")
                }
            } catch (e: Exception) {
                logger.e("CameraDetailViewModel", "toggleAlarmPush lỗi: ${e.message}", e)
            }
            loadCamera()
        }
    }

    // ✅ MỚI: "Kiểm tra kết nối" — mở TẠM 1 kết nối SSE thứ 2 riêng (gateway cho phép
    // nhiều kết nối cùng deviceCode, không đụng tới kênh nền của WebhookGatewayService)
    // và chờ tối đa timeoutMs xem có nhận được đúng 1 alarm của cameraId này không. Nếu
    // camera không hỗ trợ "Alarm Server URL" (chỉ FTP hoặc không có gì), sẽ không bao
    // giờ có push nào tới → hết thời gian chờ → tự tắt toggle + báo người dùng qua
    // alarmVerifyState = TimedOut, đúng như đã chốt: không để toggle bật mà thực ra
    // không hoạt động.
    fun verifyAlarmConnection(timeoutMs: Long = 90_000) {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = _camera.value ?: return@launch
            if (cam.enableAlarmPush != 1) return@launch

            _alarmVerifyState.value = AlarmVerifyState.Listening
            val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
            val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
            if (gatewayUrl.isBlank()) {
                _alarmVerifyState.value = AlarmVerifyState.TimedOut
                return@launch
            }
            val deviceCode = callSkill.getOrCreateMyDeviceCode()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            var confirmed = false
            try {
                val request = okhttp3.Request.Builder()
                    .url("$gatewayUrl/camera/alarm/stream/$deviceCode")
                    .header("Accept", "text/event-stream")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val reader = body.byteStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                val trimmed = line.trim()
                                if (trimmed.startsWith("data:")) {
                                    val raw = trimmed.substring(5).trim()
                                    if (raw.isNotEmpty()) {
                                        val json = JSONObject(raw)
                                        if (json.optString("cameraId", "").trim() == cameraId) {
                                            confirmed = true
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Hết readTimeout (không có alarm nào tới) hoặc lỗi mạng — coi như chưa xác nhận được.
                logger.i("CameraDetailViewModel", "verifyAlarmConnection: hết thời gian chờ hoặc lỗi mạng (${e.message})")
            }

            if (confirmed) {
                _alarmVerifyState.value = AlarmVerifyState.Success
                logger.i("CameraDetailViewModel", "verifyAlarmConnection OK cameraId=$cameraId")
            } else {
                _alarmVerifyState.value = AlarmVerifyState.TimedOut
                logger.w("CameraDetailViewModel", "verifyAlarmConnection TIMEOUT cameraId=$cameraId — camera có thể không hỗ trợ, tự tắt toggle.")
                // Tự tắt toggle + thu hồi secret, đúng quyết định: không để bật mà không hoạt động.
                val current = _camera.value
                if (current != null && current.enableAlarmPush == 1) {
                    if (gatewayToken.isNotBlank()) {
                        try {
                            val payload = JSONObject().apply {
                                put("token", gatewayToken)
                                put("deviceCode", deviceCode)
                                put("cameraId", cameraId)
                            }
                            val revokeBody = payload.toString()
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                            val revokeRequest = okhttp3.Request.Builder()
                                .url("$gatewayUrl/camera/alarm_secret/revoke")
                                .post(revokeBody)
                                .build()
                            okhttp3.OkHttpClient().newCall(revokeRequest).execute().close()
                        } catch (_: Exception) { /* dọn dẹp best-effort, không chặn tắt toggle local */ }
                    }
                    database.cameraDao().updateCamera(current.copy(enableAlarmPush = 0, alarmSecret = null))
                    _alarmPushUrl.value = null
                    loadCamera()
                }
            }
        }
    }

    fun loadLiveSnapshot() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val cam = _camera.value
                val url = cam?.snapshoturl
                if (url.isNullOrBlank()) {
                    logger.w("CameraDetailViewModel", "loadLiveSnapshot: snapshotUrl trống, id=$cameraId")
                } else {
                    // ✅ SỬA: trước đây gọi thẳng snapshotFetcher.fetchSnapshot(url) — bỏ qua hoàn
                    // toàn nhánh RTSP, nên camera chỉ có RTSP (không có endpoint HTTP snapshot, vd
                    // case V380) luôn trả null ở màn hình này dù testCameraUrl()/scanCamera() phía
                    // CameraSkill đã xử lý đúng từ trước. Đổi sang cameraSkill.fetchOneSnapshot() —
                    // cùng hàm dùng chung cho mọi nơi cần lấy 1 tấm ảnh từ URL camera (tự rẽ nhánh
                    // http/https/rtsp), tránh lặp lại lỗi bỏ sót y hệt.
                    val bytes = withContext(Dispatchers.IO) {
                        cameraSkill.fetchOneSnapshot(url, cam.snapshotUsername, cam.snapshotPassword)
                    }
                    if (bytes != null) {
                        logger.d("CameraDetailViewModel", "loadLiveSnapshot: OK (${bytes.size} bytes) | id=$cameraId")
                    } else {
                        logger.w("CameraDetailViewModel", "loadLiveSnapshot: fetchSnapshot trả về null | id=$cameraId")
                    }
                    _liveSnapshot.value = bytes
                }
            } catch (e: Exception) {
                logger.e("CameraDetailViewModel", "loadLiveSnapshot error: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun testCamera() {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            
            val cam = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(cameraId)
            }
            
            if (cam == null) {
                _testResult.value = "❌ Không tìm thấy camera"
                logger.w("CameraDetailViewModel", "testCamera: không tìm thấy camera id=$cameraId")
                _isLoading.value = false
                return@launch
            }

            if (cam.snapshoturl.isBlank()) {
                _testResult.value = "❌ Camera chưa cấu hình URL ảnh chụp"
                logger.w("CameraDetailViewModel", "testCamera: snapshotUrl trống, id=$cameraId")
                _isLoading.value = false
                return@launch
            }

            val setting = withContext(Dispatchers.IO) {
                database.cameraDao().getCustomerSetting(cam.customerId)
            }
            val wasSmartOff = setting?.smartMode != 1

            try {
                if (wasSmartOff) {
                    withContext(Dispatchers.IO) {
                        database.cameraDao().insertCustomerSetting(
                            CustomerSettingEntity(
                                customerId = cam.customerId,
                                smartMode = 1,
                                isActive = setting?.isActive ?: 1,
                                updatedAt = System.currentTimeMillis(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }

                // ✅ SỬA: "Test ngay" là hành động thủ công của người dùng — luôn bỏ qua
                // Circuit Breaker, nếu không camera sẽ bị skip âm thầm (continue trong
                // scanCamera) khi breaker đang OPEN, khiến testResult hiển thị sai
                // "✅ Bình thường" dù thực chất không hề có lượt scan nào xảy ra.
                cameraSkill.resetCircuitBreaker(cameraId)

                logger.d("CameraDetailViewModel", "testCamera: bắt đầu scan id=$cameraId, url=${cam.snapshoturl}")
                val response = cameraSkill.scanCamera(cameraId, isDailyReport = false)

                when (response) {
                    is PluginResult.Success -> {
                        val data = response.data as? Map<*, *>
                        val results = data?.get("results") as? List<*>
                        val first = results?.firstOrNull() as? Map<*, *>

                        val fetchError = first?.get("error") as? String
                        if (first == null) {
                            // ✅ SỬA: results rỗng nghĩa là camera bị bỏ qua (inactive hoặc
                            // vẫn còn bị breaker chặn) — KHÔNG được coi là "Bình thường".
                            val skippedCb = (data?.get("skippedCircuitBreaker") as? Int) ?: 0
                            val skippedInactive = (data?.get("skippedInactive") as? Int) ?: 0
                            _testResult.value = when {
                                skippedCb > 0 -> "⛔ Camera đang bị tạm ngưng do lỗi kết nối liên tiếp trước đó. Đã thử reset, vui lòng bấm Test lại."
                                skippedInactive > 0 -> "⏸️ Camera hoặc khách hàng đang tắt theo dõi (isActive=0), nên không quét."
                                else -> "❌ Không nhận được kết quả quét từ camera"
                            }
                            logger.w("CameraDetailViewModel", "testCamera: results rỗng | id=$cameraId | skippedCb=$skippedCb skippedInactive=$skippedInactive")
                        } else if (fetchError != null) {
                            _testResult.value = "❌ Không thể chụp ảnh: $fetchError\nKiểm tra URL camera hoặc kết nối mạng"
                            logger.e("CameraDetailViewModel", "testCamera: fetchError=$fetchError | id=$cameraId")
                        } else {
                            val hasChange = first?.get("hasChange") as? Boolean ?: false
                            val isSuspicious = first?.get("isSuspicious") as? Boolean ?: false
                            val aiComment = first?.get("aiComment") as? String ?: "Không có phân tích"
                            val diff = first?.get("diff") as? Int ?: 0
                            val deltaTrigger = first?.get("deltaTrigger") as? Int ?: 0
                            val absDiffTrigger = first?.get("absDiffTrigger") as? Int ?: 0

                            _testResult.value = buildString {
                                if (isSuspicious) append("⚠️ CẢNH BÁO! Email đã gửi!\n")
                                else if (hasChange) append("🔄 Có biến động nhưng AI đánh giá bình thường\n")
                                else append("✅ Bình thường\n")
                                append("━━━━━━━━━━━━━━━\n")
                                append("🤖 AI: $aiComment\n")
                                if (diff > 0) append("━━━━━━━━━━━━━━━\n📊 diff=$diff | ngưỡng delta=$deltaTrigger | ngưỡng diff=$absDiffTrigger")
                            }
                            logger.i(
                                "CameraDetailViewModel",
                                "testCamera: OK id=$cameraId hasChange=$hasChange isSuspicious=$isSuspicious diff=$diff"
                            )
                        }
                    }
                    is PluginResult.Failure -> {
                        _testResult.value = "❌ Lỗi: ${response.error}"
                        logger.e("CameraDetailViewModel", "testCamera: response.error=${response.error} | id=$cameraId")
                    }
                    else -> {
                        _testResult.value = "❌ Kết quả không xác định"
                    }
                }
            } catch (e: Exception) {
                _testResult.value = "❌ Exception: ${e.message}"
                logger.e("CameraDetailViewModel", "testCamera error: ${e.message}", e)
            } finally {
                if (wasSmartOff) {
                    try {
                        withContext(Dispatchers.IO) {
                            database.cameraDao().insertCustomerSetting(
                                CustomerSettingEntity(
                                    customerId = cam.customerId,
                                    smartMode = 0,
                                    isActive = setting?.isActive ?: 1,
                                    updatedAt = System.currentTimeMillis(),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        logger.e("CameraDetailViewModel", "Không thể khôi phục thiết lập smartMode gốc", e)
                    }
                }
                _isLoading.value = false
                loadCamera()
            }
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    // ✅ MỚI: Reset RIÊNG học tập thích nghi cho camera này — tách hẳn khỏi "Test ngay"
    // (trước đây "Test ngay" âm thầm xoá học tập mỗi lần bấm do gọi chung resetCircuitBreaker()).
    // Đây là hành động phá huỷ dữ liệu (xoá deltaTrigger/absDiffTrigger/mẫu học/baseline về mặc
    // định), nên UI nên xác nhận với người dùng trước khi gọi hàm này.
    fun resetLearning() {
        viewModelScope.launch {
            cameraSkill.resetLearningState(cameraId)
            _testResult.value = "🧠 Đã reset học tập thích nghi về mặc định cho camera này."
            logger.i("CameraDetailViewModel", "resetLearning: đã reset học tập cho id=$cameraId")
        }
    }



}

// ✅ MỚI: AlertActionConfig + alertActionsToJson/FromJson đã chuyển sang
// com.aichatvn.agent.data.model.AlertActionConfig.kt để tránh CameraSkill (core/skills)
// phải phụ thuộc ngược vào ui.viewmodels. Xem import ở đầu file.