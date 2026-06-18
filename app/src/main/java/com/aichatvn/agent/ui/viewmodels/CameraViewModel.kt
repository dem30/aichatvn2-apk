package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity

import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.aichatvn.agent.utils.Logger

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val cameraSkill: CameraSkill,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : ViewModel() {

    // FIX: Load TẤT CẢ camera, không lọc active
    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras: StateFlow<List<CameraConfigEntity>> = _cameras.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAdmin = MutableStateFlow(true)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _smartModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val smartModes: StateFlow<Map<String, Boolean>> = _smartModes.asStateFlow()

    fun loadCameras() {
        viewModelScope.launch {
            _isLoading.value = true
            // FIX: Dùng getAllCameras() thay vì getActiveCameras()
            _cameras.value = database.cameraDao().getAllCameras()
            loadSmartModes()
            _isLoading.value = false
        }
    }

    private suspend fun loadSmartModes() {
        val modes = mutableMapOf<String, Boolean>()
        _cameras.value.forEach { camera ->
            val setting = database.cameraDao().getCustomerSetting(camera.customerId)
            if (setting == null && camera.customerId.isNotEmpty()) {
                database.cameraDao().insertCustomerSetting(
                    CustomerSettingEntity(
                        customerId = camera.customerId,
                        smartMode = 0,
                        isActive = 1,
                        updatedAt = System.currentTimeMillis(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            modes[camera.customerId] = setting?.smartMode == 1
        }
        _smartModes.value = modes
    }

    fun saveCamera(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            cameraSkill.saveCameraConfig(config)
            loadCameras()
            _isLoading.value = false
        }
    }

    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            cameraSkill.deleteCamera(cameraId)
            loadCameras()
        }
    }

    fun toggleCameraActive(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val camera = database.cameraDao().getCameraById(cameraId)
                if (camera != null) {
                    val newManualOff = if (camera.manualOff == 0) 1 else 0
                    database.cameraDao().updateCamera(camera.copy(manualOff = newManualOff))
                    logger.i("CameraViewModel", "Camera $cameraId manualOff → $newManualOff")
                    // FIX: Refresh toàn bộ danh sách
                    loadCameras()
                }
            } catch (e: Exception) {
                logger.e("CameraViewModel", "toggleCameraActive error: ${e.message}", e)
            }
            _isLoading.value = false
        }
    }

    fun toggleSmartMode(customerId: String) {
        viewModelScope.launch {
            val current = _smartModes.value[customerId] ?: false
            val newMode = !current
            cameraPlugin.execute(
                "set_smart_mode",
                mapOf("customerId" to customerId, "enabled" to newMode)
            )
            loadCameras()
            logger.i("CameraViewModel", "SmartMode $customerId → $newMode")
        }
    }

    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            try {
                val camera = database.cameraDao().getCameraById(cameraId)
                val setting = camera?.let { database.cameraDao().getCustomerSetting(it.customerId) }

                val wasSmartOff = setting?.smartMode != 1
                if (wasSmartOff && camera != null) {
                    database.cameraDao().insertCustomerSetting(
                        CustomerSettingEntity(
                            customerId = camera.customerId,
                            smartMode = 1,
                            isActive = 1,
                            updatedAt = System.currentTimeMillis(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                val response = cameraSkill.scanCamera(cameraId, isDailyReport = false)

                if (wasSmartOff && camera != null) {
                    database.cameraDao().insertCustomerSetting(
                        CustomerSettingEntity(
                            customerId = camera.customerId,
                            smartMode = 0,
                            isActive = 1,
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
            } catch (e: Exception) {
                _testResult.value = "❌ Exception: ${e.message}"
                logger.e("CameraViewModel", "testCamera error: ${e.message}", e)
            }
            _isLoading.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun syncFromCloud() {
        viewModelScope.launch {
            _isLoading.value = true
            logger.i("CameraViewModel", "syncFromCloud: stub, chưa có CloudPlugin")
            loadCameras()
            _isLoading.value = false
        }
    }
}