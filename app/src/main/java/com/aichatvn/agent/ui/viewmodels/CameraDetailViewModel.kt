package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    val recentAlerts: StateFlow<List<AlertEntity>> = database.alertDao()
        .getAlertsByCameraFlow(cameraId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadCamera()
        viewModelScope.launch {
            while (isActive) {
                _diagnostics.value = cameraSkill.getDiagnostics()[cameraId] as? Map<String, Any>
                delay(5000)
            }
        }
    }

    fun loadCamera() {
        viewModelScope.launch {
            val cam = database.cameraDao().getCameraById(cameraId)
            _camera.value = cam
            cam?.let {
                val setting = database.cameraDao().getCustomerSetting(it.customerId)
                _smartMode.value = setting?.smartMode == 1
            }
        }
    }

    fun toggleActive() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newManualOff = if (cam.manualOff == 0) 1 else 0
            database.cameraDao().updateCamera(cam.copy(manualOff = newManualOff))
            loadCamera()
        }
    }

    fun toggleSmartMode() {
        viewModelScope.launch {
            val cam = _camera.value ?: return@launch
            val newMode = !_smartMode.value
            cameraSkill.setSmartMode(cam.customerId, newMode)
            _smartMode.value = newMode
        }
    }

    fun loadLiveSnapshot() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val url = _camera.value?.snapshoturl
                if (!url.isNullOrBlank()) {
                    _liveSnapshot.value = snapshotFetcher.fetchSnapshot(url)
                }
            } catch (e: Exception) {
                logger.e("CameraDetailViewModel", "loadLiveSnapshot error: ${e.message}", e)
            }
            _isLoading.value = false
        }
    }

    fun testCamera() {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            try {
                val cam = database.cameraDao().getCameraById(cameraId)
                if (cam == null) {
                    _testResult.value = "❌ Không tìm thấy camera"
                    return@launch
                }
                
                if (cam.snapshoturl.isBlank()) {
                    _testResult.value = "❌ Camera chưa cấu hình URL ảnh chụp"
                    return@launch
                }
                
                val setting = database.cameraDao().getCustomerSetting(cam.customerId)
                val wasSmartOff = setting?.smartMode != 1

                if (wasSmartOff) {
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

                val response = cameraSkill.scanCamera(cameraId, isDailyReport = false)

                if (wasSmartOff) {
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

                if (response.success) {
                    val data = response.data as? Map<*, *>
                    val results = data?.get("results") as? List<*>
                    val first = results?.firstOrNull() as? Map<*, *>
                    
                    // FIX: Kiểm tra lỗi fetch ảnh từ camera skill
                    val fetchError = first?.get("error") as? String
                    if (fetchError != null) {
                        _testResult.value = "❌ Không thể chụp ảnh: $fetchError\nKiểm tra URL camera hoặc kết nối mạng"
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
                    }
                } else {
                    _testResult.value = "❌ Lỗi: ${response.error}"
                }
                loadCamera()
            } catch (e: Exception) {
                _testResult.value = "❌ Exception: ${e.message}"
                logger.e("CameraDetailViewModel", "testCamera error: ${e.message}", e)
            }
            _isLoading.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }
}