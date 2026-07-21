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

// ✅ MỚI: Tên hiển thị thân thiện tiếng Việt cho từng Mood. Trước đây UI và câu tóm tắt
// (HouseManagerSkillImpl) in thẳng tên enum tiếng Anh (vd "CHẾ ĐỘ: SLEEPING") khiến chủ nhà
// tưởng nhầm là lỗi hiển thị / giá trị không xác định. Mọi nơi cần hiển thị Mood cho người
// dùng PHẢI dùng hàm này thay vì mood.name hoặc mood.toString().
fun HouseMood.displayName(): String = when (this) {
    HouseMood.NORMAL -> "Bình thường"
    HouseMood.BUSY -> "Bận rộn"
    HouseMood.QUIET -> "Yên tĩnh"
    HouseMood.NIGHT -> "Ban đêm"
    HouseMood.ALERT -> "Cảnh báo"
    HouseMood.SLEEPING -> "Đang ngủ"
    HouseMood.VACATION -> "Vắng nhà"
}