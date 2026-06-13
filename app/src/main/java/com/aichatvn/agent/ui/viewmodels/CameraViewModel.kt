package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentRequest
import com.aichatvn.agent.core.AgentRouter
import com.aichatvn.agent.core.IntentType
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val agentRouter: AgentRouter,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras: StateFlow<List<CameraConfigEntity>> = _cameras.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAdmin = MutableStateFlow(true)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
    
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    fun loadCameras() {
        viewModelScope.launch {
            _cameras.value = database.cameraDao().getActiveCameras()
        }
    }

    fun saveCamera(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_ADD,
                    payload = mapOf("config" to config),
                    username = "default_user"
                )
            )
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

    // SỬA: toggleActive - true = bật theo dõi, false = tắt theo dõi
    fun toggleCameraActive(cameraId: String, isCurrentlyActive: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            // isCurrentlyActive = true đang bật -> muốn tắt -> manualOff = 1
            // isCurrentlyActive = false đang tắt -> muốn bật -> manualOff = 0
            val newManualOff = if (isCurrentlyActive) 1 else 0
            agentRouter.route(
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
            loadCameras()
            _isLoading.value = false
        }
    }

    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            val response = cameraSkill.scanCamera(cameraId, isDailyReport = true)
            if (response.success) {
                val data = response.data as? Map<*, *>
                val results = data?.get("results") as? List<*>
                val firstResult = results?.firstOrNull() as? Map<*, *>
                val hasChange = firstResult?.get("hasChange") as? Boolean ?: false
                val aiComment = firstResult?.get("aiComment") as? String ?: "Không có phân tích"
                _testResult.value = if (hasChange) {
                    "⚠️ Phát hiện biến động!\n$aiComment"
                } else {
                    "✅ Bình thường\n$aiComment"
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

    fun syncFromCloud() {
        viewModelScope.launch {
            _isLoading.value = true
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.SYNC_CLOUD,
                    payload = emptyMap(),
                    username = "default_user"
                )
            )
            loadCameras()
            _isLoading.value = false
        }
    }
}