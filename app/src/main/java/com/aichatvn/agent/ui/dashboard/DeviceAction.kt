package com.aichatvn.agent.ui.dashboard

data class DeviceAction(
    val id: String,                  // Tên hành động thực thi, ví dụ: "scan", "status", "set_active"
    val title: String,               // Nhãn hiển thị trên giao diện, ví dụ: "Chụp ảnh", "Kiểm tra", "Kích hoạt"
    val icon: String,                // Biểu tượng đi kèm (Emoji hoặc Vector path)
    val defaultParams: Map<String, Any> = emptyMap() // Tham số mặc định riêng cho hành động này (nếu có)
)
