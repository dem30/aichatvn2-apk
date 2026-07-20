package com.aichatvn.agent.data.model

enum class HouseMood {
    NORMAL,    // Trạng thái vận hành bình thường
    BUSY,      // Nhiều hoạt động đồng thời (nhiều tin nhắn chưa đọc, thiết bị hoạt động nhiều)
    QUIET,     // Không gian yên tĩnh, ít biến động
    NIGHT,     // Ban đêm (vận hành bình thường)
    ALERT,     // Cảnh báo an ninh mức độ cao (có camera phát hiện bất thường)
    SLEEPING,  // Mọi người đi ngủ (đèn tắt, ban đêm, không phát hiện chuyển động trong nhà)
    VACATION   // Chủ nhà đi vắng dài ngày (chế độ vắng nhà)
}