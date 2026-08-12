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
import com.aichatvn.agent.utils.NetworkContext
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
    private val logger: Logger,
    // ✅ MỚI (Giai đoạn 3): so subnet Wi-Fi hiện tại với subnet đã lưu lúc probe/dò LAN
    // thành công lần trước — quyết định 1 camera/thiết bị đang OPTIMIZED thật hay chỉ
    // DEGRADED (khả năng có thật nhưng đang không dùng được vì điện thoại ngoài LAN nhà).
    private val networkContext: NetworkContext
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
        items += auditMqttDevices()
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
            // ✅ MỚI (Giai đoạn 3): dùng chính IP đã dùng để probe (host tách từ snapshoturl —
            // cùng quy ước camera.snapshoturl đã xác nhận CameraCapabilityProber.probe() dùng)
            // làm mốc so sánh subnet. Giới hạn đã biết: nếu snapshoturl trống/không parse được
            // (vd chỉ cấu hình snapshotUrlRemote), coi như "không xác định được" → isOnHomeSubnet
            // = false → item sẽ hiện DEGRADED dù có thể vẫn đang ở nhà thật. Chấp nhận được cho
            // mục đích hiển thị, không chặn luồng nào khác.
            val cameraIp = extractHost(camera.snapshoturl)
            val isOnHomeSubnet = networkContext.isOnSavedSubnet(cameraIp)
            items += auditCameraOnvif(camera, isOnHomeSubnet)
            items += auditCameraRtsp(camera, isOnHomeSubnet)
        }

        return items
    }

    /** Tách host từ 1 URL — dùng java.net.URI thay vì regex/split thủ công cho chắc. */
    private fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host
        } catch (e: Exception) {
            null
        }
    }

    private fun auditCameraOnvif(camera: CameraConfigEntity, isOnHomeSubnet: Boolean): HealthItem {
        val status = when {
            camera.onvifSupported == 0 -> HealthStatus.UNSUPPORTED
            camera.onvifEnabled == 0 -> HealthStatus.IMPROVABLE
            // ✅ MỚI (Giai đoạn 3): đã bật ONVIF thật (onvifEnabled==1), nhưng điện thoại hiện
            // không ở cùng LAN nhà — báo động tức thời trực tiếp KHÔNG hoạt động lúc này (mất
            // kết nối LAN tới camera), dù cấu hình vẫn đúng. Khác CONFLICT: đây không cần
            // người dùng làm gì, chỉ là trạng thái runtime tạm thời.
            !isOnHomeSubnet -> HealthStatus.DEGRADED
            else -> HealthStatus.OPTIMIZED
        }
        val description = if (status == HealthStatus.DEGRADED) {
            "Camera này hỗ trợ báo động tức thời qua mạng nhà, nhưng điện thoại hiện không ở " +
                "cùng mạng nhà nên báo động trực tiếp tạm không hoạt động."
        } else {
            "Camera này có thể báo động ngay khi phát hiện chuyển động, " +
                "không cần đợi chụp ảnh định kỳ."
        }
        return HealthItem(
            id = "camera_onvif_${camera.id}",
            category = HealthCategory.CAMERA,
            status = status,
            title = "Báo động tức thời cho \"${camera.customername}\"",
            description = description,
            relatedEntityId = camera.id,
            // An toàn tự bật: chỉ bật cờ đọc, camera đã tự xác nhận hỗ trợ qua probe thật,
            // không gọi API bên ngoài, không đổi thông tin đăng nhập.
            safeToAutoApply = status == HealthStatus.IMPROVABLE
        )
    }

    private fun auditCameraRtsp(camera: CameraConfigEntity, isOnHomeSubnet: Boolean): HealthItem {
        val status = when {
            camera.rtspSupported == 0 -> HealthStatus.UNSUPPORTED
            camera.rtspEnabled == 0 -> HealthStatus.IMPROVABLE
            !isOnHomeSubnet -> HealthStatus.DEGRADED
            else -> HealthStatus.OPTIMIZED
        }
        val description = if (status == HealthStatus.DEGRADED) {
            "Camera này hỗ trợ xem trực tiếp qua mạng nhà, nhưng điện thoại hiện không ở cùng " +
                "mạng nhà nên xem trực tiếp tạm không hoạt động."
        } else {
            "Camera này hỗ trợ xem hình ảnh trực tiếp thay vì chỉ ảnh chụp."
        }
        return HealthItem(
            id = "camera_rtsp_${camera.id}",
            category = HealthCategory.CAMERA,
            status = status,
            title = "Xem trực tiếp cho \"${camera.customername}\"",
            description = description,
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
            // ✅ MỚI (Giai đoạn 3): thiết bị ĐÃ có local_key/IP (từng dò LAN thành công), nhưng
            // điện thoại hiện không ở cùng subnet đã lưu → đang thật sự điều khiển qua
            // Cloud/Gateway dù có khả năng Local — khác hẳn CONFLICT (chưa từng dò được gì).
            val isOnHomeSubnet = hasLocalInfo && networkContext.isOnSavedSubnet(device.lastKnownIp)
            val status = when {
                !hasLocalInfo -> HealthStatus.CONFLICT
                !isOnHomeSubnet -> HealthStatus.DEGRADED
                else -> HealthStatus.OPTIMIZED
            }
            val description = if (status == HealthStatus.DEGRADED) {
                "Thiết bị này hỗ trợ điều khiển nhanh qua mạng nhà, nhưng điện thoại hiện " +
                    "không ở nhà nên đang điều khiển qua Internet."
            } else {
                "Thiết bị này có thể bật/tắt nhanh hơn qua mạng nhà thay vì " +
                    "luôn phải qua Internet."
            }
            HealthItem(
                id = "tuya_local_${device.id}",
                category = HealthCategory.DEVICE,
                status = status,
                title = "Điều khiển nhanh cho \"${device.name}\"",
                description = description,
                relatedEntityId = device.id,
                // CONFLICT, không IMPROVABLE: việc dò local_key gọi mạng thật (fetchLocalKey),
                // không phải chỉ bật cờ — để người dùng tự bấm ở màn hình chi tiết thiết bị.
                // DEGRADED không cần hành động gì (đã cấu hình đúng, chỉ là đang không ở nhà).
                safeToAutoApply = false,
                // route "tuya" (Screen.Tuya) — chỉ có ý nghĩa cho CONFLICT (chưa cấu hình gì);
                // DEGRADED không có gì để "đi cấu hình" nên không gán route.
                manualActionRoute = if (status == HealthStatus.CONFLICT) "tuya" else null
            )
        }
    }

    // ==================== MQTT ====================

    /**
     * Audit thiết bị MQTT — thuần qua DeviceController interface (listDevices()/getStatus()),
     * KHÔNG đọc thẳng MqttDeviceDao. Khác với auditTuyaDevices(): Tuya có khái niệm
     * "local_key/lastKnownIp đã dò được chưa" (field riêng của Tuya, không nằm trong
     * DeviceController), nên không thể gộp chung 1 hàm mà không phá nguyên tắc "không import
     * driver cụ thể" (xem ghi chú đầu file). MQTT (REALTIME_STATUS, không có LOCAL_CONTROL)
     * chỉ cần biết thiết bị có đang online (đã nhận trạng thái mới nhất qua broker) hay
     * không — đủ thông tin lấy được thuần qua interface chung.
     *
     * Lưu ý: MqttDeviceController hiện còn là stub (chưa nối broker MQTT thật — xem TODO
     * trong file đó), nên getStatus() vẫn đọc DB tĩnh. Mục CONFLICT ở đây có ý nghĩa đầy đủ
     * hơn sau khi nối broker thật (Giai đoạn 2); không sai khi thêm ngay từ bây giờ, không
     * cần sửa lại hàm này khi Giai đoạn 2 xong.
     */
    private suspend fun auditMqttDevices(): List<HealthItem> {
        val controller = deviceRegistry.controllerFor(DeviceProtocol.MQTT) ?: return emptyList()
        val devices = controller.listDevices()

        return devices.map { summary ->
            val status = controller.getStatus(summary.id)
            val online = status?.isOnline == true
            HealthItem(
                id = "mqtt_device_${summary.id}",
                category = HealthCategory.DEVICE,
                status = if (online) HealthStatus.OPTIMIZED else HealthStatus.CONFLICT,
                title = "Thiết bị MQTT \"${summary.displayName}\"",
                description = if (online)
                    "Thiết bị đang kết nối và báo trạng thái bình thường."
                else
                    "Chưa nhận được trạng thái từ thiết bị này — kiểm tra kết nối broker.",
                relatedEntityId = summary.id,
                // CONFLICT, không IMPROVABLE: mất kết nối MQTT không phải cờ có thể tự bật
                // được — cần người dùng tự kiểm tra broker/thiết bị vật lý.
                safeToAutoApply = false,
                manualActionRoute = null
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