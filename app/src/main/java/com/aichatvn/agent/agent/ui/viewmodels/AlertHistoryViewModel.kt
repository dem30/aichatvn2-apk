package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
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
    // ✅ MỚI: Cần để huỷ notification hệ thống tương ứng ngay khi Admin xử lý xong alert trong
    // app (đánh dấu đã đọc / xoá) — trước đây 2 thứ này không liên quan gì nhau, notification cứ
    // nằm ì trên thanh trạng thái dù alert đã được xử lý xong trong danh sách.
    private val notificationSkill: NotificationSkill
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
            // ✅ MỚI: Huỷ notification hệ thống ứng với đúng alert này — dùng lại cùng công thức
            // ID (notificationIdForAlert) mà CameraSkill đã dùng lúc gửi, nên chắc chắn khớp.
            notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alertId))
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            // ✅ MỚI: Huỷ notification của TỪNG alert đang hiện trong danh sách trước khi cập
            // nhật DB — lấy danh sách hiện tại (alerts.value) vì sau khi markAllAsRead() DB đã
            // đổi isRead nhưng ta chỉ cần đúng tập alertId để tính lại notification ID.
            alerts.value.forEach { alert ->
                notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
            }
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
            // ✅ MỚI: Alert đã bị xoá khỏi lịch sử — notification tương ứng (nếu còn hiện) cũng
            // không còn ý nghĩa gì để giữ lại trên thanh trạng thái nữa.
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