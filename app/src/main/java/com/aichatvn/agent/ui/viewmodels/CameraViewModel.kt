package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentRequest
import com.aichatvn.agent.core.AgentRouter
import com.aichatvn.agent.core.IntentType
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val agentRouter: AgentRouter,
    private val database: AppDatabase
) : ViewModel() {

    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras: StateFlow<List<CameraConfigEntity>> = _cameras.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAdmin = MutableStateFlow(true) // Default admin for standalone app
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

    fun toggleCameraActive(cameraId: String, active: Boolean) {
        viewModelScope.launch {
            val cameras = database.cameraDao().getActiveCameras()
            val camera = cameras.find { it.id == cameraId } ?: return@launch
            database.cameraDao().updateCamera(
                camera.copy(manualOff = if (!active) 1 else 0)
            )
            loadCameras()
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
        // Optional cloud sync — no-op in offline mode
    }
}
