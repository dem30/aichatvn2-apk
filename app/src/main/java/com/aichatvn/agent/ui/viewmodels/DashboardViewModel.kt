package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.plugins.CameraPlugin
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// ✅ KHÔNG còn dùng AgentRouter (legacy). scanAllNow dùng CameraPlugin trực tiếp.

data class DashboardSummary(
    val totalCameras: Int = 0,
    val onlineCameras: Int = 0,
    val offlineCameras: Int = 0,
    val disabledCameras: Int = 0,
    val cooldownCameras: Int = 0,
    val circuitBreakerOpenCameras: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val cameraPlugin: CameraPlugin,
    private val database: AppDatabase,
    private val logger: Logger
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResultMessage = MutableStateFlow<String?>(null)
    val scanResultMessage: StateFlow<String?> = _scanResultMessage.asStateFlow()

    private val _learningStats = MutableStateFlow<Map<String, Any>>(emptyMap())

    private val cameras: StateFlow<List<CameraConfigEntity>> = database.cameraDao()
        .getAllCamerasFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentAlerts: StateFlow<List<AlertEntity>> = database.alertDao()
        .getAllAlertsFlow()
        .map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadAlertCount: StateFlow<Int> = database.alertDao()
        .getUnreadCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayAlertCount: StateFlow<Int> = database.alertDao()
        .getAlertCountSinceFlow(startOfTodayMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val summary: StateFlow<DashboardSummary> = combine(cameras, _learningStats) { cameraList, stats ->
        DashboardSummary(
            totalCameras = cameraList.size,
            onlineCameras = cameraList.count { it.isOnline == 1 && it.manualOff == 0 },
            offlineCameras = cameraList.count { it.isOnline != 1 && it.manualOff == 0 },
            disabledCameras = cameraList.count { it.manualOff == 1 },
            cooldownCameras = stats.values.count {
                (it as? Map<*, *>)?.get("inCooldown") == true
            },
            circuitBreakerOpenCameras = stats.values.count {
                (it as? Map<*, *>)?.get("circuitBreakerOpen") == true
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    init {
        viewModelScope.launch {
            while (isActive) {
                _learningStats.value = cameraSkill.getDiagnostics()
                delay(5000)
            }
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Quét tất cả camera đang hoạt động ngay lập tức. */
    fun scanAllNow() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanResultMessage.value = null
            try {
                // Dùng CameraPlugin thay vì AgentRouter — nhất quán với pipeline mới.
                // cameraId = null → scan toàn bộ camera.
                val result = cameraPlugin.execute("scan", emptyMap())
                _scanResultMessage.value = when (result) {
                    is com.aichatvn.agent.core.plugin.PluginResult.Success -> {
                        val msg = (result.data as? Map<*, *>)?.get("message") as? String
                        "✅ ${msg ?: "Đã quét xong"}"
                    }
                    is com.aichatvn.agent.core.plugin.PluginResult.Failure -> "❌ Lỗi: ${result.error}"
                    else -> "❌ Không thực hiện được"
                }
            } catch (e: Exception) {
                _scanResultMessage.value = "❌ Exception: ${e.message}"
                logger.e("DashboardViewModel", "scanAllNow error: ${e.message}", e)
            }
            _isScanning.value = false
        }
    }

    fun clearScanResult() {
        _scanResultMessage.value = null
    }
}