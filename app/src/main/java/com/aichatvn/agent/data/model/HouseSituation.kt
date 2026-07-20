package com.aichatvn.agent.data.model

data class HouseSituation(
    val securityLevel: Int,          // 0: An toàn, 1: Nghi vấn (Warning), 2: Nguy hiểm (Alarm)
    val ownerPresent: Boolean,       // Chủ nhà đang có mặt ở nhà hay đi vắng
    val guestsCount: Int,            // Số lượng khách phát hiện hiện tại
    val pendingChatsCount: Int,      // Số lượng tin nhắn chưa đọc từ các kênh đa kênh
    val activeDevicesCount: Int,     // Số lượng thiết bị đang bật (Tuya)
    val suspiciousObjectsCount: Int, // Số lượng vật thể khả nghi phát hiện từ camera
    val currentMood: HouseMood,      // Tâm trạng/Chế độ vận hành hiện tại
    val summary: String              // Đoạn tóm tắt tự nhiên mô tả trạng thái ngôi nhà
)