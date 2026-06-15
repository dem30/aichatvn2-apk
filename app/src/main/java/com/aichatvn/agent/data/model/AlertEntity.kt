package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alerts",
    indices = [Index(value = ["cameraId"]), Index(value = ["timestamp"])]
)
data class AlertEntity(
    @PrimaryKey
    val id: String,
    val cameraId: String,
    val customerId: String,
    val cameraName: String,
    val timestamp: Long,
    val aiComment: String,
    val diff: Int,
    val deltaTrigger: Int,
    val absDiffTrigger: Int,
    val imagePath: String? = null,
    val emailSent: Int = 0,
    // true = cảnh báo thật (isSuspicious), false = chỉ ghi nhận biến động bất thường nhưng AI thấy bình thường
    val isSuspicious: Int = 1,
    // Đã đọc/xem chưa, dùng cho badge "chưa đọc"
    val isRead: Int = 0
)
