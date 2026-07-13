package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

// ==================== CHAT MESSAGE ====================

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
    // Nguồn gốc câu trả lời của Assistant (human, learn, camera, v.v.)
    val sourcePlugin: String? = null,
    // ✅ MỚI: Trạng thái đã đọc — CHỈ có ý nghĩa với tin nhắn role="user" của khách ngoại kênh
    // (Facebook/Telegram/Website). Mặc định true để không phá dữ liệu cũ/tin nhắn assistant/tin
    // nhắn của default_user (những tin này không cần badge chưa đọc). Các nơi insert tin nhắn
    // KHÁCH GỬI TỚI từ webhook sẽ set false tường minh — xem ChatSkill.saveExternalUserMessage().
    val isRead: Boolean = true
)

// ==================== Q&A ====================

@Entity(tableName = "qa_data")
data class QAEntity(
    @PrimaryKey
    val id: String,
    val question: String,
    val answer: String,
    val category: String,
    val type: String = "alias",
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
    val aiNegativeKeywords: String = "",
    val enableCooldown: Int = 1,       // ✅ MỚI: 1 = Bật, 0 = Tắt hoãn kiểm tra AI
    val enableNotification: Int = 1,    // ✅ MỚI: 1 = Bật, 0 = Tắt gửi Email/Push
    val alertActions: String = "[]"   // ✅ MỚI: JSON array [{pluginId, action, params}] chạy khi isSuspicious=true
)

@Entity(tableName = "customer_settings")
data class CustomerSettingEntity(
    @PrimaryKey
    val customerId: String,
    val smartMode: Int = 0,
    val isActive: Int = 1,
    val updatedAt: Long,
    val timestamp: Long,
    // ✅ MỚI: Page ID Facebook gần nhất mà khách (customerId = PSID) đã nhắn tới.
    // Dùng để trả lời thủ công đúng Fanpage khi có nhiều Fanpage liên kết cùng lúc,
    // thay vì phụ thuộc extraContext (thường rỗng khi Admin gõ tay từ ChatScreen).
    val lastFacebookPageId: String? = null
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
    val isRead: Int = 0,
    val scheduleId: String? = null
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
    val label: String = "",
    val createdAt: Long
)

// ==================== APP CONFIG ====================

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

// ==================== MULTI FACEBOOK PAGES ====================

@Entity(tableName = "facebook_pages")
data class FacebookPageEntity(
    @PrimaryKey
    val id: String,                 // Page ID từ Facebook
    val name: String,               // Tên Fanpage
    val accessToken: String,        // Access Token dài hạn riêng của trang này
    val updatedAt: Long = System.currentTimeMillis()
)