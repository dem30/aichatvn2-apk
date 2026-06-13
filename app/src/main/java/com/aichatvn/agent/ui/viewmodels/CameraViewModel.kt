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

    // FIXED: Sửa logic toggle active
    fun toggleCameraActive(cameraId: String, active: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            // active = true nghĩa là bật theo dõi -> manualOff = 0
            // active = false nghĩa là tắt theo dõi -> manualOff = 1
            val manualOff = if (active) 0 else 1
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.CAMERA_UPDATE,
                    payload = mapOf(
                        "config" to mapOf(
                            "id" to cameraId,
                            "manualOff" to manualOff
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
            cameraSkill.scanCamera(cameraId, isDailyReport = true)
            _isLoading.value = false
        }
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