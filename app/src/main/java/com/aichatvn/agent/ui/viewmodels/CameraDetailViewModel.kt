package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.CameraSkill
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
import javax.inject.Inject

data class CameraConfigDraft(
    val snapshotUrl: String = "",
    val landInfo: String = "",
    val aiPrompt: String = "",
    val aiPositiveKeywords: String = "",
    val aiNegativeKeywords: String = ""
)

data class ScheduleDraft(
    val id: String = "",
    val action: String = "scan",
    val cron: String = "",
    val intervalMinutes: Int = 0,
    val enabled: Boolean = true
)

@HiltViewModel
class CameraDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val snapshotFetcher: SnapshotFetcher,
    private val logger: Logger
) : ViewModel() {

    val cameraId: String = savedStateHandle.get<String>("cameraId") ?: ""

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
            aiNegativeKeywords = cam.aiNegativeKeywords
        )
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
                    aiNegativeKeywords = draft.aiNegativeKeywords.trim()
                )
                withContext(Dispatchers.IO) {
                    database.cameraDao().updateCamera(updated)
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
                            JSONObject(schedule.params).optString("cameraId") == cameraId
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
        _scheduleDraft.value = ScheduleDraft(
            id = schedule.id,
            action = schedule.action,
            cron = schedule.cron,
            intervalMinutes = schedule.intervalMinutes,
            enabled = schedule.enabled == 1
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

    fun saveSchedule() {
        val draft = _scheduleDraft.value ?: return
        if (draft.cron.isBlank() && draft.intervalMinutes <= 0) {
            _scheduleResult.value = "❌ Cần nhập cron hoặc interval"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val params = JSONObject().apply {
                    put("cameraId", cameraId)
                }.toString()

                val isNew = draft.id.isBlank()
                val schedule = ScheduleEntity(
                    id = if (isNew) UUID.randomUUID().toString() else draft.id,
                    pluginId = "camera",
                    action = draft.action,
                    params = params,
                    cron = draft.cron.trim(),
                    intervalMinutes = draft.intervalMinutes,
                    enabled = if (draft.enabled) 1 else 0,
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

    fun loadLiveSnapshot() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val url = _camera.value?.snapshoturl
                if (url.isNullOrBlank()) {
                    logger.w("CameraDetailViewModel", "loadLiveSnapshot: snapshotUrl trống, id=$cameraId")
                } else {
                    val bytes = withContext(Dispatchers.IO) {
                        snapshotFetcher.fetchSnapshot(url)
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
                                updatedAt = System.currentTimeMillis(), // ✅ ĐÃ SỬA: Sửa lỗi chính tả từ createdAt sang updatedAt để build thành công
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }

                logger.d("CameraDetailViewModel", "testCamera: bắt đầu scan id=$cameraId, url=${cam.snapshoturl}")
                val response = cameraSkill.scanCamera(cameraId, isDailyReport = false)

                when (response) {
                    is PluginResult.Success -> {
                        val data = response.data as? Map<*, *>
                        val results = data?.get("results") as? List<*>
                        val first = results?.firstOrNull() as? Map<*, *>

                        val fetchError = first?.get("error") as? String
                        if (fetchError != null) {
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
                                    updatedAt = System.currentTimeMillis(), // ✅ ĐÃ SỬA: Sửa lỗi chính tả từ createdAt sang updatedAt để build thành công
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
}