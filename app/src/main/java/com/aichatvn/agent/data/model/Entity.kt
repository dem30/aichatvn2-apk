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
    val timestamp: Long,
    // ✅ MỚI: nguồn gốc câu trả lời CỦA ASSISTANT.
    // - null            -> chat thường (Groq trả lời tự do / QA)
    // - "learn"         -> lệnh học Q&A ("Học:"/"Dạy:")
    // - id của 1 plugin (vd "camera", "light", "email"...) -> đây là kết quả THỰC THI
    //   1 lệnh điều khiển thiết bị qua AgentKernel.tryDeviceCommand()
    // Dùng để UI gắn badge "⚡ lệnh" lên góc tin nhắn, giúp người dùng phân biệt được AI
    // đang trả lời tự do hay vừa thực thi 1 lệnh thật sự. Tin nhắn role="user" luôn để null.
    val sourcePlugin: String? = null
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

// ==================== CUSTOMER ====================

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey
    val id: String,          // = customerId, khớp với CameraConfigEntity.customerId
    val name: String,
    val email: String,
    val address: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
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
    val smartMode: Int = 1,        // per-camera AI flag; master = CustomerSettingEntity.smartMode
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
// ==================== APP CONFIG ====================

/**
 * Bảng key-value lưu tất cả cấu hình của các plugin.
 *
 * key         : định danh duy nhất, dạng "pluginId.paramName" (vd "camera.cooldownMs")
 * value       : giá trị dưới dạng String (mọi kiểu đều serialise về String)
 * type        : "string" | "int" | "long" | "boolean" | "float"
 * pluginId    : plugin sở hữu tham số (dùng để nhóm trên UI, vd "camera", "groq")
 * label       : tên hiển thị ngắn gọn (vd "Cooldown (ms)")
 * description : mô tả mục đích, hiện bên dưới label trên UI Settings
 * updatedAt   : timestamp lần sửa gần nhất (millis)
 */
@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val type: String = "string",   // string | int | long | boolean | float
    val pluginId: String = "global",
    val label: String = "",
    val description: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)