package com.aichatvn.agent.ui.dashboard



data class DeviceNode(
    val id: String,
    val name: String,
    val type: DeviceType,
    val pluginId: String,
    
    val defaultAction: String,
    val defaultParams: Map<String, Any> = emptyMap(),
    val supportedActions: List<DeviceAction> = emptyList(),
    
    val x: Float,
    val y: Float,
    val online: Boolean,
    val icon: String,
    val ip: String = "",
    val battery: Int? = null,
    
    // ─── [MỚI] SIÊU DỮ LIỆU DIGITAL TWIN ───
    val status: String = "",          // Trạng thái hiển thị (ví dụ: "Đang bật", "Công suất 45W")
    val lastSeen: Long = System.currentTimeMillis(), // Lần cuối phản hồi hệ thống
    val rssi: Int? = null,            // Cường độ sóng Wi-Fi (dBm)
    val room: String = "Phòng chung",  // Phân bổ không gian phòng
    val scene: String = "",           // Kịch bản tự động liên kết
    val group: String = "",            // Nhóm thiết bị liên kết vật lý

    // ✅ MỚI (Dimmer): mang thông tin dimmer xuyên suốt tầng Dashboard (DeviceNodeWidget,
    // bottom sheet DashboardScreen) — TÁCH KHỎI supportedActions vì DeviceAction không mang
    // được giá trị số do người dùng chọn (chỉ có defaultParams cố định, xem DeviceAction.kt).
    // isDimmable=false thì dimmerLevel luôn null — nơi đọc PHẢI kiểm tra isDimmable trước,
    // không suy luận ngược từ dimmerLevel != null.
    val isDimmable: Boolean = false,
    // Mức độ % hiện tại để hiển thị Slider đúng vị trí lần đầu vẽ (giống cách TuyaScreen/
    // MqttScreen đã làm) — null nếu isDimmable nhưng chưa biết mức hiện tại (Tuya không cache
    // % ở Entity, xem TuyaScreen.kt), UI tự mặc định 50% khi gặp null.
    val dimmerLevel: Int? = null
)

