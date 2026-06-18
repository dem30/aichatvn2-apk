package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ==================== CHAT MESSAGE ====================
// ==================== TUYA DEVICE ====================

@Entity(tableName = "tuya_devices")
data class TuyaDeviceEntity(
    @PrimaryKey
    val id: String,              // Device ID từ Tuya
    val name: String,            // Tên thiết bị (user đặt trong Tuya App)
    val online: Boolean = false, // Trạng thái online
    val category: String = "",   // Loại thiết bị (socket, light, switch...)
    val productName: String = "",// Tên sản phẩm
    val lastSeen: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionToken: String,
    val username: String,
    val content: String,
    val role: String, // "user" or "assistant"
    val type: String, // "text" or "image" or "file"
    val fileUrl: String? = null,
    val timestamp: Long
)

// ==================== Q&A ====================

@Entity(tableName = "qa_data")
data class QAEntity(
    @PrimaryKey
    val id: String,
    val question: String,
    val answer: String,
    val category: String,
    val createdBy: String,
    val createdAt: Long,
    val timestamp: Long
)

// ==================== CAMERA ====================

@Entity(tableName = "cameras")
data class CameraConfigEntity(
    @PrimaryKey
    val id: String,
    val customerId: String,
    val customername: String,
    val customeremail: String,
    val snapshoturl: String,
    val landinfo: String? = null,
    val snapshotPath: String? = null,
    val timestamp: Long,
    val status: String = "online",
    val isOnline: Int = 1,
    val manualOff: Int = 0,
    val aiPrompt: String = "",
    val aiPositiveKeywords: String = "",
    val aiNegativeKeywords: String = ""
)

@Entity(tableName = "customer_settings")
data class CustomerSettingEntity(
    @PrimaryKey
    val customerId: String,
    val smartMode: Int = 0,
    val isActive: Int = 1,
    val updatedAt: Long,
    val timestamp: Long
)

// ==================== ALERT ====================

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
    val isSuspicious: Int = 1,
    val isRead: Int = 0
)

// ==================== SCHEDULE ====================

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey
    val id: String,
    val pluginId: String,      // "camera", "light", "email", "schedule"
    val action: String,          // "scan", "set", "send", "add"
    val params: String = "{}",   // JSON params
    val cron: String = "",       // "0 7 * * *" hoặc để trống nếu dùng interval
    val intervalMinutes: Int = 0, // 15, 30, 60
    val enabled: Int = 1,        // 0 = tắt, 1 = bật
    val lastRunAt: Long = 0L,
    val createdAt: Long
)