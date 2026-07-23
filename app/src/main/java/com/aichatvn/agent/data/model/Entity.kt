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
    // Trạng thái đã đọc — CHỈ có ý nghĩa với tin nhắn role="user" của khách ngoại kênh
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
    val id: String,          // = customerId, khớp with CameraConfigEntity.customerId
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
    val enableCooldown: Int = 1,       // 1 = Bật, 0 = Tắt hoãn kiểm tra AI
    val enableNotification: Int = 1,    // 1 = Bật, 0 = Tắt gửi Email/Push
    val alertActions: String = "[]"   // JSON array [{pluginId, action, params}] chạy khi isSuspicious=true
)

@Entity(tableName = "customer_settings")
data class CustomerSettingEntity(
    @PrimaryKey
    val customerId: String,
    val smartMode: Int = 0,
    val isActive: Int = 1,
    val updatedAt: Long,
    val timestamp: Long,
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
    // ✅ MỚI: giá trị delta THẬT đã đo (kotlin.math.abs(currentDiff - lastDiff)) tại thời điểm
    // báo động — khác với deltaTrigger (ngưỡng cấu hình). Cần để markFalsePositiveAndLearn học
    // đúng trên nhiễu quan sát được thay vì học nhầm trên ngưỡng cũ.
    val delta: Int = 0,
    val deltaTrigger: Int,
    val absDiffTrigger: Int,
    val imagePath: String? = null,
    val emailSent: Int = 0,
    val isSuspicious: Int = 1,
    val isRead: Int = 0,
    val scheduleId: String? = null,
    val scheduleLabel: String? = null,
    
    // ✅ MỚI (Tuần 2 & 3 - Phase 3): Thời điểm kết thúc sự kiện nén kéo dài.
    // null nghĩa là sự kiện tức thời hoặc không áp dụng nén.
    val endTime: Long? = null,
    
    // ✅ MỚI (Tuần 1 & 2 - Phase 1): Lưu trữ raw JSON có cấu trúc nhận được từ Groq Vision
    val aiStateJson: String? = null
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

// ==================== EVENT LOG (TRÍ NHỚ SỰ KIỆN) ====================

// ✅ MỚI (Tuần 2 - Phase 2): Nhật ký sự kiện phục vụ bộ nhớ ngữ cảnh và Memory-RAG.
@Entity(
    tableName = "event_logs",
    indices = [Index(value = ["source"]), Index(value = ["timestamp"])]
)
data class EventLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,       // "camera", "tuya", "system", "call", "notification"
    val sourceId: String,     // ID cụ thể của thiết bị/camera/kênh liên lạc
    val eventType: String,    // "state_change", "person_detected", "missed_call", "incoming_message"
    val value: String,        // Giá trị trạng thái thô
    val summary: String       // Tóm tắt ngôn ngữ tự nhiên để AI dễ đọc
)

// ==================== WORLD STATE (BẢN SAO SỐ THỜI GIAN THỰC) ====================

// ✅ MỚI (Tuần 5 - Phase 5): Bản sao trạng thái mới nhất của các đối tượng trong nhà.
@Entity(tableName = "world_state")
data class WorldStateEntity(
    @PrimaryKey
    val id: String,           // Định dạng: "$source:$sourceId"
    val source: String,       // "camera", "tuya", "system", v.v.
    val sourceId: String,
    val attributesJson: String, // Lưu JSON phẳng các thuộc tính hiện tại: {"state":"on","cup_detected":"true"}
    val updatedAt: Long = System.currentTimeMillis()
)