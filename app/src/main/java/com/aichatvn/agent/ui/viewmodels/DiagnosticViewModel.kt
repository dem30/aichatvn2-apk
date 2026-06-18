package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val database: AppDatabase
) : ViewModel() {

    private val _combinedStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val combinedStats: StateFlow<Map<String, Any>> = _combinedStats.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                cameraSkill.diagnostics,
                database.cameraDao().getAllCamerasFlow()
            ) { diagnostics, cameras ->
                val cameraStats = cameras.map { camera ->
                    mapOf(
                        "id" to camera.id,
                        "name" to camera.customername,
                        "status" to when {
                            camera.manualOff == 1 -> "Đã tắt"
                            camera.isOnline == 1 -> "Hoạt động"
                            else -> "Mất kết nối"
                        },
                        "isOnline" to camera.isOnline,
                        "manualOff" to camera.manualOff
                    )
                }

                mapOf(
                    "learningStats" to diagnostics,
                    "cameras" to cameraStats,
                    "totalCameras" to cameraStats.size,
                    "onlineCameras" to cameraStats.count { it["isOnline"] == 1 && it["manualOff"] == 0 },
                    "offlineCameras" to cameraStats.count { it["isOnline"] != 1 && it["manualOff"] == 0 },
                    "disabledCameras" to cameraStats.count { it["manualOff"] == 1 }
                )
            }.collect { _combinedStats.value = it }
        }
    }

    fun resetCircuitBreaker(cameraId: String) {
        viewModelScope.launch {
            cameraSkill.resetCircuitBreaker(cameraId)
        }
    }

    fun resetAllCircuitBreakers() {
        viewModelScope.launch {
            cameraSkill.resetAllCircuitBreakers()
        }
    }
}