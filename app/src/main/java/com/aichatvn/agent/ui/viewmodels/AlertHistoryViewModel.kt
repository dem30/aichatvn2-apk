package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    // Nếu có cameraId (đến từ CameraDetailScreen) -> chỉ xem cảnh báo của camera đó
    val cameraId: String = savedStateHandle.get<String>("cameraId")?.takeIf { it.isNotBlank() } ?: ""
    val isFiltered: Boolean get() = cameraId.isNotEmpty()

    val alerts: StateFlow<List<AlertEntity>> = (
        if (isFiltered) database.alertDao().getAlertsByCameraFlow(cameraId)
        else database.alertDao().getAllAlertsFlow()
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val unreadCount: StateFlow<Int> = database.alertDao()
        .getUnreadCountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun markAsRead(alertId: String) {
        viewModelScope.launch {
            database.alertDao().markAsRead(alertId)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            if (isFiltered) database.alertDao().markAllAsReadForCamera(cameraId)
            else database.alertDao().markAllAsRead()
        }
    }

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            // Xóa file ảnh đính kèm (nếu có) trước khi xóa record
            val alert = database.alertDao().getAlertById(alertId)
            alert?.imagePath?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
            database.alertDao().deleteAlert(alertId)
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            alerts.value.forEach { alert ->
                alert.imagePath?.let { path -> runCatching { java.io.File(path).delete() } }
            }
            if (isFiltered) database.alertDao().deleteAlertsByCamera(cameraId)
            else database.alertDao().deleteAllAlerts()
        }
    }
}
