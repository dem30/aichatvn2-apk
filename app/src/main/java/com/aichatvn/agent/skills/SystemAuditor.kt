package com.aichatvn.agent.skills

import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.HealthCategory
import com.aichatvn.agent.data.model.HealthItem
import com.aichatvn.agent.data.model.HealthStatus
import com.aichatvn.agent.data.model.SystemHealthReport
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.devices.DeviceCapability
import com.aichatvn.agent.devices.DeviceProtocol
import com.aichatvn.agent.devices.DeviceRegistry
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemAuditor
 *
 * CHỈ ĐỌC — không ghi DB, không gọi API nào có tác dụng phụ. An toàn để chạy bất cứ lúc
 * nào (mở màn hình, kéo-để-làm-mới, chạy nền định kỳ...) mà không lo thay đổi trạng thái
 * ngoài ý muốn. Việc ÁP DỤNG thay đổi (nếu người dùng đồng ý) thuộc về SystemAutoConfigurer
 * — 2 lớp tách biệt hoàn toàn theo chủ đích (xem ghi chú trong SystemAutoConfigurer.kt).
 *
 * Cấu trúc: mỗi nguồn audit là 1 hàm private độc lập (auditCameras(), auditTuyaDevices()...),
 * audit() chỉ gộp kết quả. Thêm nguồn audit mới (vd auditMqttDevices() khi MQTT lên) = thêm
 * 1 hàm mới + 1 dòng gọi trong audit() — KHÔNG sửa hàm audit nào đã có, tránh lặp lại kiểu
 * lỗi "sửa 1 chỗ, gãy chỗ khác" đã gặp ở AppModule.kt trước đây.
 */
@Singleton
class SystemAuditor @Inject constructor(
    private val database: AppDatabase,
    private val deviceRegistry: DeviceRegistry,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "SystemAuditor"
    }

    /**
     * Quét toàn bộ hệ thống, trả về báo cáo tổng hợp. Gọi hàm này an toàn ở bất kỳ đâu
     * (ViewModel.viewModelScope, WorkManager định kỳ...) vì không có side-effect.
     */
    suspend fun audit(): SystemHealthReport {
        val items = mutableListOf<HealthItem>()

        items += auditCameras()
        items += auditTuyaDevices()
        items += auditInfra()

        logger.i(TAG, "✅ Audit xong: ${items.size} mục (${items.count { it.status == HealthStatus.IMPROVABLE }} có thể cải thiện)")

        return SystemHealthReport(
            items = items,
            generatedAt = System.currentTimeMillis()
        )
    }

    // ==================== CAMERA ====================

    /**
     * Audit từng camera: ONVIF (báo động tức thời) và RTSP (xem trực tiếp) đã được
     * CameraCapabilityProber xác nhận hỗ trợ nhưng người dùng chưa bật Switch tương ứng.
     * CHỈ đề xuất khi *Supported == 1 (đã probe thật, không đoán) — đúng nguyên tắc
     * "không tự bật tính năng nào" ghi trong CameraConfigEntity.
     */
    private suspend fun auditCameras(): List<HealthItem> {
        val cameras = database.cameraDao().getAllCameras()
        val items = mutableListOf<HealthItem>()

        cameras.forEach { camera ->
            items += auditCameraOnvif(camera)
            items += auditCameraRtsp(camera)
        }

        return items
    }

    private fun auditCameraOnvif(camera: CameraConfigEntity): HealthItem {
        val status = when {
            camera.onvifSupported == 0 -> HealthStatus.UNSUPPORTED
            camera.onvifEnabled == 1 -> HealthStatus.OPTIMIZED
            else -> HealthStatus.IMPROVABLE
        }
        return HealthItem(
            id = "camera_onvif_${camera.id}",
            category = HealthCategory.CAMERA,
            status = status,
            title = "Báo động tức thời cho \"${camera.customername}\"",
            description = "Camera này có thể báo động ngay khi phát hiện chuyển động, " +
                "không cần đợi chụp ảnh định kỳ.",
            relatedEntityId = camera.id,
            // An toàn tự bật: chỉ bật cờ đọc, camera đã tự xác nhận hỗ trợ qua probe thật,
            // không gọi API bên ngoài, không đổi thông tin đăng nhập.
            safeToAutoApply = status == HealthStatus.IMPROVABLE
        )
    }

    private fun auditCameraRtsp(camera: CameraConfigEntity): HealthItem {
        val status = when {
            camera.rtspSupported == 0 -> HealthStatus.UNSUPPORTED
            camera.rtspEnabled == 1 -> HealthStatus.OPTIMIZED
            else -> HealthStatus.IMPROVABLE
        }
        return HealthItem(
            id = "camera_rtsp_${camera.id}",
            category = HealthCategory.CAMERA,
            status = status,
            title = "Xem trực tiếp cho \"${camera.customername}\"",
            description = "Camera này hỗ trợ xem hình ảnh trực tiếp thay vì chỉ ảnh chụp.",
            relatedEntityId = camera.id,
            safeToAutoApply = status == HealthStatus.IMPROVABLE
        )
    }

    // ==================== TUYA / THIẾT BỊ ====================

    /**
     * Audit thiết bị Tuya QUA DeviceRegistry, không gọi thẳng TuyaManager — để khi thêm
     * MQTT/OEM khác, hàm này (hoặc hàm audit tương tự cho protocol mới) không cần sửa lại
     * cách gọi, chỉ cần DeviceRegistry đã có driver tương ứng đăng ký.
     *
     * Đề xuất duy nhất ở đây: thiết bị hỗ trợ LOCAL_CONTROL nhưng chưa có local_key/IP đã
     * lưu (nghĩa là mọi lần bật/tắt đều phải gọi Cloud API, chậm hơn và phụ thuộc mạng).
     * Đây CHỈ là quan sát trạng thái — SystemAutoConfigurer không tự động "sửa" mục này vì
     * việc dò local_key cần gọi TuyaManager.fetchLocalKey() (có side-effect mạng), không
     * đơn thuần bật 1 cờ; xem HealthStatus.CONFLICT bên dưới.
     */
    private suspend fun auditTuyaDevices(): List<HealthItem> {
        val protocol = DeviceProtocol.TUYA_CLOUD
        val controller = deviceRegistry.controllerFor(protocol) ?: return emptyList()
        if (DeviceCapability.LOCAL_CONTROL !in controller.capabilities) return emptyList()

        val devices = database.tuyaDeviceDao().getAllDevices()

        return devices.map { device ->
            val hasLocalInfo = !device.localKey.isNullOrBlank() && !device.lastKnownIp.isNullOrBlank()
            HealthItem(
                id = "tuya_local_${device.id}",
                category = HealthCategory.DEVICE,
                status = if (hasLocalInfo) HealthStatus.OPTIMIZED else HealthStatus.CONFLICT,
                title = "Điều khiển nhanh cho \"${device.name}\"",
                description = "Thiết bị này có thể bật/tắt nhanh hơn qua mạng nhà thay vì " +
                    "luôn phải qua Internet.",
                relatedEntityId = device.id,
                // CONFLICT, không IMPROVABLE: việc dò local_key gọi mạng thật (fetchLocalKey),
                // không phải chỉ bật cờ — để người dùng tự bấm ở màn hình chi tiết thiết bị.
                safeToAutoApply = false,
                // route "tuya" (Screen.Tuya) — màn danh sách thiết bị, nơi người dùng tự bấm
                // "Bật điều khiển nhanh" cho từng thiết bị (xem TuyaScreen.kt).
                manualActionRoute = if (hasLocalInfo) null else "tuya"
            )
        }
    }

    // ==================== HẠ TẦNG CHUNG ====================

    /**
     * Audit các thiết lập tầng hạ tầng chung không gắn với 1 camera/thiết bị cụ thể.
     * Hiện chưa có mục nào — hàm để sẵn cho việc mở rộng sau (vd kiểm tra
     * GLOBAL_GATEWAY_URL đã cấu hình hay chưa nếu LOCAL_ONLY_MODE_ENABLED bật).
     */
    private suspend fun auditInfra(): List<HealthItem> {
        return emptyList()
    }
}
