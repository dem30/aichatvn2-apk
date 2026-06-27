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
    val group: String = ""            // Nhóm thiết bị liên kết vật lý
)

