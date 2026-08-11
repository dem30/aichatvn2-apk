package com.aichatvn.agent.devices

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceRegistry
 *
 * Điểm truy cập DUY NHẤT mà nghiệp vụ (HouseManagerSkill, SmartActionFormSheet,
 * SystemAuditor...) dùng để lấy đúng DeviceController theo protocol của 1 thiết bị.
 * Không ai được inject thẳng TuyaDeviceController/MqttDeviceController... vào nghiệp vụ —
 * luôn đi qua đây, để việc thêm/bớt driver không đòi hỏi sửa nơi gọi.
 *
 * `controllers: Set<@JvmSuppressWildcards DeviceController>` được Hilt tự gom từ mọi class
 * đã đăng ký `@Binds @IntoSet` (xem DeviceModule.kt) — thêm driver mới KHÔNG cần sửa file
 * này, chỉ cần thêm 1 dòng @Binds trong DeviceModule.
 */
@Singleton
class DeviceRegistry @Inject constructor(
    controllers: Set<@JvmSuppressWildcards DeviceController>
) {
    private val byProtocol: Map<DeviceProtocol, DeviceController> =
        controllers.associateBy { it.protocol }

    /**
     * Lấy controller cho 1 protocol cụ thể. Trả về null nếu chưa có driver nào đăng ký cho
     * protocol đó (vd đã thêm giá trị enum DeviceProtocol.MQTT nhưng chưa viết/đăng ký
     * MqttDeviceController) — nghiệp vụ gọi PHẢI xử lý null bằng thông báo rõ ràng cho người
     * dùng ("Loại thiết bị này chưa được hỗ trợ"), không được crash hay giả định luôn có.
     */
    fun controllerFor(protocol: DeviceProtocol): DeviceController? = byProtocol[protocol]

    /** Toàn bộ protocol hiện có driver hoạt động — dùng cho SystemAuditor liệt kê phạm vi quét. */
    fun supportedProtocols(): Set<DeviceProtocol> = byProtocol.keys

    /**
     * Toàn bộ controller đang hoạt động, không phân biệt protocol — dùng cho các nơi cần
     * gộp dữ liệu từ MỌI giao thức cùng lúc (vd DynamicOptionRegistry build dropdown
     * "device" gộp cả Tuya lẫn MQTT, hoặc build danh sách precondition_source động thay vì
     * hardcode ["camera","tuya","chat"]). Thêm driver mới qua DeviceModule.kt tự động xuất
     * hiện ở đây, không cần sửa gì thêm.
     */
    fun all(): Collection<DeviceController> = byProtocol.values
}