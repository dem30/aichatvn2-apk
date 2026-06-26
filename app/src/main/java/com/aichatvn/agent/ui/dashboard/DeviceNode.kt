package com.aichatvn.agent.ui.dashboard

/**
 * Định nghĩa hành động khả thi của một thiết bị trên Dashboard.
 * Giúp giao diện tự động vẽ nút bấm tương ứng mà không cần hardcode.
 */

data class DeviceNode(
    val id: String,
    val name: String,
    val type: DeviceType,
    val pluginId: String,
    
    val defaultAction: String,                    // Hành động mặc định khi bấm trực tiếp vào Node
    val defaultParams: Map<String, Any> = emptyMap(), // Toàn bộ tham số nhận diện thiết bị của Plugin
    val supportedActions: List<DeviceAction> = emptyList(), // Danh sách nút bấm chức năng đi kèm thiết bị
    
    val x: Float,                                 // Tọa độ vẽ X
    val y: Float,                                 // Tọa độ vẽ Y
    val online: Boolean,
    val icon: String,
    val ip: String = "",
    val battery: Int? = null
)