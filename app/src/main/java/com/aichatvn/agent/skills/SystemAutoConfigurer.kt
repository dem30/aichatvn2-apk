package com.aichatvn.agent.skills

import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.HealthItem
import com.aichatvn.agent.data.model.HealthStatus
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemAutoConfigurer
 *
 * DUY NHẤT lớp trong tính năng SHAS được phép ghi DB. SystemAuditor chỉ đọc; tách biệt có
 * chủ đích để audit luôn an toàn chạy bất cứ lúc nào (xem SystemAuditor.kt), còn việc ghi
 * chỉ xảy ra khi người dùng chủ động bấm "Áp dụng".
 *
 * RANH GIỚI CỨNG — không được nới lỏng khi mở rộng tính năng sau này:
 *   1. CHỈ áp dụng item có status == IMPROVABLE VÀ safeToAutoApply == true. Item CONFLICT
 *      (vd "cần dò local_key Tuya") hay bất kỳ item nào safeToAutoApply == false đều bị
 *      từ chối ở applyItem() — không có đường vòng nào bỏ qua kiểm tra này.
 *   2. CHỈ bật cờ cấu hình nội bộ đã được xác nhận an toàn qua probe/capability thật
 *      (onvifEnabled, rtspEnabled — theo đúng field CameraConfigEntity). KHÔNG BAO GIỜ:
 *      gọi API bên ngoài có phí, đổi mật khẩu/secret, sinh secret mới (vd enableAlarmPush
 *      — xem CameraDetailViewModel.toggleAlarmPush() sinh alarmSecret + gọi gateway, nên
 *      mục này cố ý bị SystemAuditor xếp vào nhóm không đề xuất tự động), xoá dữ liệu.
 *   3. Ghi DB đi thẳng qua DAO (giống hệt cách CameraDetailViewModel.toggleOnvifEnabled()/
 *      toggleRtspEnabled() đang làm: đọc entity mới nhất → copy() → updateCamera()), KHÔNG
 *      qua ViewModel — vì ViewModel đó scope theo 1 cameraId cụ thể qua SavedStateHandle,
 *      không dùng được cho việc áp dụng hàng loạt nhiều camera cùng lúc ở đây.
 *
 * Thêm loại item an toàn mới sau này: thêm 1 case trong when của applyItem(), KHÔNG đổi
 * chữ ký hàm hay nới lỏng điều kiện ở đầu hàm.
 */
@Singleton
class SystemAutoConfigurer @Inject constructor(
    private val database: AppDatabase,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "SystemAutoConfigurer"
    }

    /**
     * Kết quả áp dụng 1 item — để UI hiển thị đúng cái nào thành/bại, không phải im lặng
     * coi cả loạt là thành công.
     */
    sealed class ApplyResult {
        data class Applied(val itemId: String) : ApplyResult()
        data class Rejected(val itemId: String, val reason: String) : ApplyResult()
        data class Failed(val itemId: String, val reason: String) : ApplyResult()
    }

    /**
     * Áp dụng 1 danh sách item đã được người dùng đồng ý (thường là toàn bộ
     * SystemHealthReport.safeImprovableItems, hoặc 1 tập con họ tự chọn qua checkbox).
     * Không dừng giữa chừng nếu 1 item lỗi — chạy hết danh sách, trả về kết quả từng cái.
     */
    suspend fun applyItems(items: List<HealthItem>): List<ApplyResult> {
        return items.map { applyItem(it) }
    }

    /**
     * Áp dụng 1 item. PUBLIC nhưng luôn tự kiểm tra lại status/safeToAutoApply ở đây —
     * không tin tưởng mù quáng rằng caller đã lọc đúng, vì đây là lớp duy nhất ghi DB nên
     * chính nó phải là chốt chặn cuối cùng.
     */
    suspend fun applyItem(item: HealthItem): ApplyResult {
        if (item.status != HealthStatus.IMPROVABLE || !item.safeToAutoApply) {
            logger.w(TAG, "⛔ Từ chối áp dụng mục không an toàn: ${item.id} (status=${item.status}, safeToAutoApply=${item.safeToAutoApply})")
            return ApplyResult.Rejected(item.id, "Mục này cần xem chi tiết, không thể tự động áp dụng")
        }

        val cameraId = item.relatedEntityId
        if (cameraId == null) {
            return ApplyResult.Rejected(item.id, "Thiếu thông tin thiết bị liên quan")
        }

        return try {
            when {
                item.id.startsWith("camera_onvif_") -> applyOnvifEnabled(cameraId)
                item.id.startsWith("camera_rtsp_") -> applyRtspEnabled(cameraId)
                else -> {
                    logger.w(TAG, "⚠️ Không nhận diện được loại mục: ${item.id}")
                    ApplyResult.Rejected(item.id, "Loại cải thiện này chưa được hỗ trợ tự động áp dụng")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "❌ Lỗi khi áp dụng ${item.id}: ${e.message}", e)
            ApplyResult.Failed(item.id, e.message ?: "Lỗi không xác định")
        }
    }

    /**
     * Bật onvifEnabled — giống hệt logic CameraDetailViewModel.toggleOnvifEnabled():
     * đọc lại entity mới nhất từ DB (không dùng bản cache cũ) trước khi copy(), tránh ghi
     * đè lên thay đổi khác đã xảy ra ở nơi khác giữa lúc audit() và applyItem().
     */
    private suspend fun applyOnvifEnabled(cameraId: String): ApplyResult {
        val cameraDao = database.cameraDao()
        val camera = cameraDao.getCameraById(cameraId)
            ?: return ApplyResult.Rejected("camera_onvif_$cameraId", "Không tìm thấy camera")

        if (camera.onvifSupported == 0) {
            return ApplyResult.Rejected("camera_onvif_$cameraId", "Camera không hỗ trợ tính năng này")
        }
        if (camera.onvifEnabled == 1) {
            // Đã bật từ trước (vd người dùng tự bật trong lúc report còn hiển thị cũ) — coi
            // như thành công, không phải lỗi.
            return ApplyResult.Applied("camera_onvif_$cameraId")
        }

        cameraDao.updateCamera(camera.copy(onvifEnabled = 1))
        logger.i(TAG, "✅ Đã bật báo động tức thời (ONVIF) cho camera $cameraId")
        return ApplyResult.Applied("camera_onvif_$cameraId")
    }

    /** Bật rtspEnabled — cùng khuôn mẫu với applyOnvifEnabled(). */
    private suspend fun applyRtspEnabled(cameraId: String): ApplyResult {
        val cameraDao = database.cameraDao()
        val camera = cameraDao.getCameraById(cameraId)
            ?: return ApplyResult.Rejected("camera_rtsp_$cameraId", "Không tìm thấy camera")

        if (camera.rtspSupported == 0) {
            return ApplyResult.Rejected("camera_rtsp_$cameraId", "Camera không hỗ trợ tính năng này")
        }
        if (camera.rtspEnabled == 1) {
            return ApplyResult.Applied("camera_rtsp_$cameraId")
        }

        cameraDao.updateCamera(camera.copy(rtspEnabled = 1))
        logger.i(TAG, "✅ Đã bật xem trực tiếp (RTSP) cho camera $cameraId")
        return ApplyResult.Applied("camera_rtsp_$cameraId")
    }
}
