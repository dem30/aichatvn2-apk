package com.aichatvn.agent.ui.dashboard

data class DeviceNode(
    val id: String,
    val name: String,
    val type: DeviceType,
    val pluginId: String,
    val deviceId: String,
    val x: Float, // Tọa độ X trên sơ đồ (theo dp hoặc tỉ lệ % màn hình)
    val y: Float, // Tọa độ Y trên sơ đồ
    val online: Boolean,
    val icon: String, // Emoji hoặc tên icon vector
    val ip: String = "192.168.1.1",
    val battery: Int = 100
)