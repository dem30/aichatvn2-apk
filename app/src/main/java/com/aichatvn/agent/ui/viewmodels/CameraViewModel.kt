package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentRequest
import com.aichatvn.agent.core.AgentRouter
import com.aichatvn.agent.core.IntentType
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val agentRouter: AgentRouter,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Dùng Flow reactive từ DB — UI tự cập nhật khi DB thay đổi
    val cameras: StateFlow<List<CameraConfigEntity>> = database.cameraDao()
        .getAllCamerasFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // _isAdmin: thực tế nên đọc từ DataStore/Auth, hiện giữ true cho admin mode
    private val _isAdmin = MutableStateFlow(true)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    // CustomerSettings cho từng customerId — load khi cần
    private val _customerSettings = MutableStateFlow<Map<String, CustomerSettingEntity>>(emptyMap())
    val customerSettings: StateFlow<Map<String, CustomerSettingEntity>> = _customerSettings.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadCustomerSettings()
    }

    private fun loadCustomerSettings() {
        viewModelScope.launch {
            cameras.collect { cameraList ->
                val settings = mutableMapOf<String, CustomerSettingEntity>()
                cameraList.map { it.customerId }.distinct().forEach { cid ->
                    val setting = database.cameraDao().getCustomerSetting(cid)
                    if (setting != null) settings[cid] = setting
                }
                _customerSettings.value = settings
            }
        }
    }

    fun saveCamera(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_ADD,
                    payload = mapOf("config" to config),
                    username = "default_user"
                )
            )
            if (!response.success) {
                _errorMessage.value = "Lỗi lưu camera: ${response.error}"
            }
            _isLoading.value = false
        }
    }

    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_DELETE,
                    payload = mapOf("cameraId" to cameraId),
                    username = "default_user"
                )
            )
            if (!response.success) {
                _errorMessage.value = "Lỗi xoá camera: ${response.error}"
            }
            _isLoading.value = false
        }
    }

    fun deleteCustomer(customerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_DELETE_CUSTOMER,
                    payload = mapOf("customerId" to customerId),
                    username = "default_user"
                )
            )
            if (!response.success) {
                _errorMessage.value = "Lỗi xoá khách hàng: ${response.error}"
            }
            _isLoading.value = false
        }
    }

    /**
     * manualOff = 1 → tắt theo dõi, manualOff = 0 → bật theo dõi.
     * Chỉ toggle dựa trên manualOff, không liên quan isOnline (trạng thái mạng).
     */
    fun toggleCameraActive(cameraId: String, currentManualOff: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            val newManualOff = if (currentManualOff == 0) 1 else 0
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_UPDATE,
                    payload = mapOf(
                        "config" to mapOf(
                            "id" to cameraId,
                            "manualOff" to newManualOff
                        )
                    ),
                    username = "default_user"
                )
            )
            if (!response.success) {
                _errorMessage.value = "Lỗi cập nhật: ${response.error}"
            }
            _isLoading.value = false
        }
    }

    fun setSmartMode(customerId: String, enabled: Boolean) {
        viewModelScope.launch {
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_SET_SMART_MODE,
                    payload = mapOf("customerId" to customerId, "enabled" to enabled),
                    username = "default_user"
                )
            )
            if (response.success) {
                // Refresh settings
                val updated = database.cameraDao().getCustomerSetting(customerId)
                if (updated != null) {
                    _customerSettings.value = _customerSettings.value.toMutableMap()
                        .also { it[customerId] = updated }
                }
            } else {
                _errorMessage.value = "Lỗi cập nhật Smart Mode: ${response.error}"
            }
        }
    }

    fun setCustomerActive(customerId: String, active: Boolean) {
        viewModelScope.launch {
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_SET_ACTIVE,
                    payload = mapOf("customerId" to customerId, "active" to active),
                    username = "default_user"
                )
            )
            if (response.success) {
                val updated = database.cameraDao().getCustomerSetting(customerId)
                if (updated != null) {
                    _customerSettings.value = _customerSettings.value.toMutableMap()
                        .also { it[customerId] = updated }
                }
            } else {
                _errorMessage.value = "Lỗi cập nhật trạng thái: ${response.error}"
            }
        }
    }

    /**
     * Test camera: dùng isDailyReport=false để lấy đầy đủ kết quả gồm hasChange, isSuspicious, diff.
     */
    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            val response = agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_SCAN,
                    payload = mapOf("cameraId" to cameraId, "isDailyReport" to false),
                    username = "default_user"
                )
            )
            if (response.success) {
                val data = response.data as? Map<*, *>
                val results = data?.get("results") as? List<*>
                val firstResult = results?.firstOrNull() as? Map<*, *>
                val isSuspicious = firstResult?.get("isSuspicious") as? Boolean ?: false
                val hasChange = firstResult?.get("hasChange") as? Boolean ?: false
                val aiComment = firstResult?.get("aiComment") as? String ?: "Không có phân tích"
                val diff = firstResult?.get("diff") as? Int
                val diffText = if (diff != null) "  (diff=$diff)" else ""
                _testResult.value = when {
                    isSuspicious -> "🚨 CẢNH BÁO!$diffText\n$aiComment"
                    hasChange    -> "⚠️ Có biến động$diffText\n$aiComment"
                    else         -> "✅ Bình thường$diffText\n$aiComment"
                }
            } else {
                _testResult.value = "❌ Lỗi: ${response.error}"
            }
            _isLoading.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
