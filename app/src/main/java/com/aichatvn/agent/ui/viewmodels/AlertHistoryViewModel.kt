package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.HouseManagerSkill
import com.aichatvn.agent.skills.NotificationSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val notificationSkill: NotificationSkill,
    private val cameraSkill: CameraSkill, // ✅ MỚI: Inject CameraSkill để nạp mẫu học phản hồi
    // ✅ MỚI: Inject HouseManagerSkill để reset world_state của camera khi cảnh báo đã được
    // xử lý hết — sửa lỗi "Chỉ số Ma" (thẻ "Bất thường" ở HouseManagerScreen không tự trừ
    // về 0 khi xoá/đọc hết cảnh báo trong màn này).
    private val houseManagerSkill: HouseManagerSkill
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

    // ✅ SỬA: Reset world_state chỉ khi camera KHÔNG CÒN alert nào isSuspicious=1 — không
    // còn xét isRead nữa. Trước đây coi "đã đọc" = "đã xử lý", nhưng chủ nhà xác nhận: chỉ
    // xem cảnh báo (markAsRead tự động khi mở alert) hoặc chỉ xoá KHÔNG được coi là đã xử lý
    // xong về mặt an ninh — chỉ có 2 hành động này mới hạ isSuspicious/xoá hẳn record:
    //   1. Bấm "Báo động giả" -> isSuspicious=0
    //   2. Xoá alert (deleteAlert/deleteAll) -> record biến mất khỏi bảng `alert`
    // "Đọc" (markAsRead/markAllAsRead) KHÔNG được gọi hàm này nữa — xem xong không có nghĩa
    // là đã xác nhận an toàn, tránh việc badge "Bất thường" tự tắt chỉ vì chủ nhà mở app xem.
    private suspend fun resetCameraStateIfNoUnresolvedAlerts(cameraId: String) {
        if (cameraId.isBlank()) return
        val remaining = database.alertDao().getAlertsByCameraFlow(cameraId).first()
        val stillUnresolved = remaining.any { it.isSuspicious == 1 }
        if (!stillUnresolved) {
            houseManagerSkill.resetCameraSuspiciousState(cameraId)
        }
    }

    fun markAsRead(alertId: String) {
        viewModelScope.launch {
            database.alertDao().markAsRead(alertId)
            notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alertId))
            // ⚠️ Không gọi resetCameraStateIfNoUnresolvedAlerts ở đây: đọc alert không phải
            // là xử lý xong — chủ nhà chỉ muốn "Báo giả" hoặc xoá mới hạ badge "Bất thường".
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
                alertTimestamp = alert.timestamp,
                // ✅ SỬA (bugfix): thiếu tham số này khiến driftTrigger dùng sentinel mặc định -1
                // của markFalsePositiveAndLearn -> KHÔNG BAO GIỜ học driftTrigger từ nút bấm này,
                // dù diff/delta vẫn học bình thường (2 tham số đó bắt buộc, không có sentinel).
                // Đường chat (CameraSkill.handleMarkFalsePositive) đã truyền đúng alert.drift từ
                // trước; đường nút bấm này bị bỏ sót khi thêm tham số drift vào signature.
                drift = alert.drift
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
            resetCameraStateIfNoUnresolvedAlerts(alert.cameraId)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            alerts.value.forEach { alert ->
                notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
            }
            if (isFiltered) database.alertDao().markAllAsReadForCamera(cameraId)
            else database.alertDao().markAllAsRead()
            // ⚠️ Không reset world_state ở đây — cùng lý do như markAsRead().
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
            alert?.cameraId?.let { resetCameraStateIfNoUnresolvedAlerts(it) }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            val affectedCameraIds = alerts.value.map { it.cameraId }.distinct()
            alerts.value.forEach { alert ->
                alert.imagePath?.let { path -> runCatching { java.io.File(path).delete() } }
                notificationSkill.cancelNotification(NotificationSkill.notificationIdForAlert(alert.id))
            }
            if (isFiltered) database.alertDao().deleteAlertsByCamera(cameraId)
            else database.alertDao().deleteAllAlerts()
            affectedCameraIds.forEach { resetCameraStateIfNoUnresolvedAlerts(it) }
        }
    }
}