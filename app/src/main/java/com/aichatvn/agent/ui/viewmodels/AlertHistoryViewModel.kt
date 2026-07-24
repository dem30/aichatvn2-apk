package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.NotificationSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val notificationSkill: NotificationSkill,
    private val cameraSkill: CameraSkill // ✅ MỚI: Inject CameraSkill để nạp mẫu học phản hồi
) : ViewModel() {

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
            notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alertId))
        }
    }

    /**
     * 🧠 MỚI: Người dùng bấm nút "Báo động giả" trên giao diện danh sách Cảnh báo
     * Hệ thống tự động trích xuất thông số diff, delta của cảnh báo này, đẩy vào CameraSkill
     * để nâng ngưỡng lọc nhiễu ngay lập tức.
     */
    fun markAsFalsePositive(alert: AlertEntity) {
        viewModelScope.launch {
            // Kích hoạt tiến trình học từ phản hồi
            cameraSkill.markFalsePositiveAndLearn(
                cameraId = alert.cameraId,
                diff = alert.diff,
                // ✅ SỬA: dùng alert.delta (giá trị nhiễu THẬT đã đo), không phải alert.deltaTrigger
                // (ngưỡng cấu hình) — đúng bug đã sửa ở CameraSkill.handleMarkFalsePositive (đường
                // chat), nhưng nút bấm này gọi thẳng markFalsePositiveAndLearn qua một đường vào
                // thứ hai nên bị lệch lại y hệt.
                delta = alert.delta,
                // ✅ MỚI (day/night split): truyền đúng thời điểm alert THẬT xảy ra để CameraSkill
                // học vào đúng bộ ngưỡng ngày/đêm — không phải giờ hiện tại lúc bấm nút này.
                alertTimestamp = alert.timestamp
            )
            // Đánh dấu cảnh báo này thành không nghi vấn (isSuspicious = 0)
            database.alertDao().insertAlert(
                alert.copy(
                    isSuspicious = 0,
                    aiComment = "[Đã xác nhận Báo giả] ${alert.aiComment}"
                )
            )
            // Hủy thông báo đẩy tương ứng
            notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            alerts.value.forEach { alert ->
                notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
            }
            if (isFiltered) database.alertDao().markAllAsReadForCamera(cameraId)
            else database.alertDao().markAllAsRead()
        }
    }

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            val alert = database.alertDao().getAlertById(alertId)
            alert?.imagePath?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
            database.alertDao().deleteAlert(alertId)
            notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alertId))
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            alerts.value.forEach { alert ->
                alert.imagePath?.let { path -> runCatching { java.io.File(path).delete() } }
                notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
            }
            if (isFiltered) database.alertDao().deleteAlertsByCamera(cameraId)
            else database.alertDao().deleteAllAlerts()
        }
    }
}