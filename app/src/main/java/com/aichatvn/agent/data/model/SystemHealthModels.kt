package com.aichatvn.agent.data.model

/**
 * SystemHealthModels
 *
 * Các data class thuần cho tính năng "System Health & Auto-Setup" (SHAS).
 * KHÔNG chứa logic đọc/ghi DB — chỉ là hình dạng dữ liệu dùng chung giữa
 * SystemAuditor (đọc) → ViewModel → UI, và giữa SystemAutoConfigurer (ghi) → ViewModel.
 */

/**
 * Trạng thái sức khoẻ của 1 mục cấu hình.
 * - OPTIMIZED: đã ở trạng thái tốt nhất có thể, không cần làm gì.
 * - IMPROVABLE: có thể cải thiện, và việc cải thiện này AN TOÀN để tự động áp dụng
 *   (chỉ bật cờ nội bộ đã được probe xác nhận hỗ trợ — không gọi API bên ngoài có phí,
 *   không đổi thông tin đăng nhập/bảo mật).
 * - CONFLICT: cần người dùng tự quyết định (vd bật "Báo động camera" sinh secret mới,
 *   hoặc chọn giữa 2 lựa chọn loại trừ nhau) — SystemAutoConfigurer TUYỆT ĐỐI không tự áp dụng.
 * - UNSUPPORTED: đã kiểm tra và xác nhận không hỗ trợ / không áp dụng được cho mục này.
 */
enum class HealthStatus {
    OPTIMIZED,
    IMPROVABLE,
    CONFLICT,
    UNSUPPORTED
}

/**
 * Phân loại mục theo miền — dùng để nhóm hiển thị trên UI.
 */
enum class HealthCategory {
    CAMERA,
    DEVICE,
    INFRA
}

/**
 * 1 mục sức khoẻ hệ thống cụ thể — vd "Camera A: hỗ trợ ONVIF nhưng chưa bật".
 *
 * @param id định danh duy nhất, ổn định giữa các lần audit (vd "camera_onvif_$cameraId") —
 *           dùng để UI đánh dấu "đã chọn" và SystemAutoConfigurer biết áp dụng đúng mục nào.
 * @param category nhóm hiển thị (CAMERA / DEVICE / INFRA).
 * @param status trạng thái hiện tại — quyết định UI hiển thị icon/màu gì và có cho phép
 *               chọn "Áp dụng" hay không (chỉ IMPROVABLE mới cho chọn).
 * @param title tiêu đề ngắn, KHÔNG dùng thuật ngữ kỹ thuật (không nói "ONVIF"/"RTSP"/"relay").
 * @param description mô tả 1 câu, giải thích lợi ích bằng ngôn ngữ thường.
 * @param relatedEntityId id của camera/thiết bị liên quan (cameraId hoặc tuya deviceId),
 *                        null nếu là mục ở tầng hạ tầng chung (INFRA) không gắn với 1 entity cụ thể.
 * @param safeToAutoApply true nếu SystemAutoConfigurer được phép tự áp dụng mục này khi người
 *                        dùng bấm "Áp dụng các mục an toàn" hàng loạt — false bắt buộc phải vào
 *                        đúng màn hình chi tiết (CameraDetailScreen...) để người dùng tự bấm.
 */
data class HealthItem(
    val id: String,
    val category: HealthCategory,
    val status: HealthStatus,
    val title: String,
    val description: String,
    val relatedEntityId: String? = null,
    val safeToAutoApply: Boolean = false
)

/**
 * Báo cáo tổng hợp toàn bộ hệ thống tại 1 thời điểm — kết quả của SystemAuditor.audit().
 *
 * @param items toàn bộ mục đã quét, không lọc — UI tự lọc theo status khi hiển thị.
 * @param generatedAt thời điểm quét (epoch millis) — hiển thị "Quét lúc..." trên UI.
 */
data class SystemHealthReport(
    val items: List<HealthItem>,
    val generatedAt: Long
) {
    /** Số mục đã ở trạng thái tốt nhất — dùng cho dòng tóm tắt "x/y mục đã tối ưu". */
    val optimizedCount: Int get() = items.count { it.status == HealthStatus.OPTIMIZED }

    /** Tổng số mục có ý nghĩa (loại UNSUPPORTED ra khỏi mẫu số vì không áp dụng được). */
    val applicableCount: Int get() = items.count { it.status != HealthStatus.UNSUPPORTED }

    /** Các mục an toàn để đề xuất áp dụng hàng loạt ngay trên Dashboard Card. */
    val safeImprovableItems: List<HealthItem>
        get() = items.filter { it.status == HealthStatus.IMPROVABLE && it.safeToAutoApply }

    /** Các mục cần người dùng tự quyết định — hiển thị kèm nút "Xem chi tiết", không có "Áp dụng". */
    val conflictItems: List<HealthItem>
        get() = items.filter { it.status == HealthStatus.CONFLICT }
}
