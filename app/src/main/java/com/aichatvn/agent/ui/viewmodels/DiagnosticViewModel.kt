package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val database by lazy { AppDatabase.getDatabase(context) }

    private val _learningStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val learningStats = _learningStats.asStateFlow()

    // Dùng getAllCamerasFlow() reactive thay vì getActiveCameras() one-shot
    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras = _cameras.asStateFlow()

    val combinedStats: StateFlow<Map<String, Any>> = combine(_learningStats, _cameras) { stats, cameraList ->
        mapOf(
            "learningStats" to stats,
            "cameras" to cameraList.map { camera ->
                val status = when {
                    camera.manualOff == 1 -> "Đã tắt"
                    camera.isOnline != 1 -> "Mất kết nối"
                    else -> "Hoạt động"
                }
                mapOf(
                    "id" to camera.id,
                    "name" to camera.customername,
                    "customerId" to camera.customerId,
                    "isOnline" to (camera.isOnline == 1),
                    "manualOff" to (camera.manualOff == 1),
                    "status" to status
                )
            },
            "totalCameras" to cameraList.size,
            "onlineCameras" to cameraList.count { it.isOnline == 1 && it.manualOff == 0 },
            "offlineCameras" to cameraList.count { it.isOnline != 1 && it.manualOff == 0 },
            "disabledCameras" to cameraList.count { it.manualOff == 1 }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    init {
        // Collect camera list reactively từ Flow
        viewModelScope.launch {
            database.cameraDao().getAllCamerasFlow().collect { list ->
                _cameras.value = list
            }
        }
        // Poll learning stats mỗi 5 giây (không có Flow nên dùng polling)
        viewModelScope.launch {
            while (isActive) {
                _learningStats.value = cameraSkill.getDiagnostics()
                delay(5000)
            }
        }
    }
}
