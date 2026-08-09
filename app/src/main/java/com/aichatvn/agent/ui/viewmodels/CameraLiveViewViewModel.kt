package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class LiveViewState {
    object Loading : LiveViewState()
    data class Ready(val rtspUrl: String) : LiveViewState()
    data class NotAvailable(val reason: String) : LiveViewState()
}

@HiltViewModel
class CameraLiveViewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val logger: Logger
) : ViewModel() {

    val cameraId: String = (savedStateHandle.get<String>("cameraId") ?: "").trim()

    private val _camera = MutableStateFlow<CameraConfigEntity?>(null)
    val camera: StateFlow<CameraConfigEntity?> = _camera.asStateFlow()

    private val _state = MutableStateFlow<LiveViewState>(LiveViewState.Loading)
    val state: StateFlow<LiveViewState> = _state.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val cam = withContext(Dispatchers.IO) { database.cameraDao().getCameraById(cameraId) }
            _camera.value = cam
            _state.value = when {
                cam == null -> LiveViewState.NotAvailable("Không tìm thấy camera")
                cam.rtspEnabled != 1 || cam.rtspUrl.isNullOrBlank() ->
                    LiveViewState.NotAvailable("Camera chưa bật Xem trực tiếp hoặc không hỗ trợ RTSP")
                else -> LiveViewState.Ready(cam.rtspUrl)
            }
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val recording = _isRecording.value
            val result = withContext(Dispatchers.IO) {
                if (recording) cameraSkill.stopRecording(cameraId)
                else cameraSkill.startRecording(cameraId, durationSec = 120)
            }
            when (result) {
                is com.aichatvn.agent.core.AgentKernel.PluginResult.Success -> {
                    _isRecording.value = !recording
                    _actionMessage.value = (result.data as? Map<*, *>)?.get("message") as? String
                }
                is com.aichatvn.agent.core.AgentKernel.PluginResult.Failure -> {
                    _actionMessage.value = result.error
                }
                else -> {}
            }
            logger.i("CameraLiveViewViewModel", "toggleRecording cameraId=$cameraId → ${!recording}")
        }
    }

    fun clearMessage() { _actionMessage.value = null }
}