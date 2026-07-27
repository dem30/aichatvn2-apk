package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ==================== CALL CONTACTS (DANH BẠ) ====================

/**
 * CallContactEntity
 *
 * Số đã lưu cho tính năng gọi P2P — deviceCode là khóa chính vì nó đã là mã định
 * danh duy nhất tự sinh trong CallSkill.getOrCreateMyDeviceCode() (UUID.take(8)),
 * không cần thêm id riêng.
 */
@Entity(tableName = "call_contacts")
data class CallContactEntity(
    @PrimaryKey
    val deviceCode: String,          // Mã máy 8 ký tự của đối phương
    val displayName: String,         // Tên người dùng tự đặt để nhớ ("Mẹ", "Văn phòng"...)
    val note: String = "",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCalledAt: Long = 0L      // ✅ cập nhật mỗi lần gọi thành công tới số này, dùng để sort "gần đây" trong danh bạ
)

// ==================== CALL LOGS (LỊCH SỬ GỌI) ====================

/**
 * Các giá trị hợp lệ cho CallLogEntity.status — dùng String thay Enum để tránh phải
 * viết TypeConverter cho Room, nhưng vẫn có hằng số dùng chung giữa CallSkill và UI
 * để không gõ nhầm chuỗi.
 */
object CallLogStatus {
    const val ANSWERED = "ANSWERED"
    const val MISSED = "MISSED"
    const val REJECTED = "REJECTED"
    const val FAILED = "FAILED"
}

object CallDirection {
    const val OUTGOING = "OUTGOING"
    const val INCOMING = "INCOMING"
}

/**
 * CallLogEntity
 *
 * 1 bản ghi cho MỖI cuộc gọi đã kết thúc (dù thành công, nhỡ, từ chối, hay lỗi kết
 * nối). callId dùng lại đúng UUID đã sinh trong CallSkill (start_call/call_offer) —
 * ghi đè (REPLACE) nếu vô tình insert 2 lần cho cùng 1 cuộc gọi, không tạo trùng dòng.
 *
 * peerName là bản SAO tên contact tại thời điểm gọi — cố tình không JOIN sống với
 * call_contacts, để lịch sử không tự đổi nếu sau này người dùng sửa/xoá contact đó.
 */
@Entity(
    tableName = "call_logs",
    indices = [Index(value = ["peerDeviceCode"]), Index(value = ["startedAt"])]
)
data class CallLogEntity(
    @PrimaryKey
    val callId: String,
    val peerDeviceCode: String,
    val peerName: String? = null,
    val direction: String,           // CallDirection.OUTGOING | CallDirection.INCOMING
    val status: String,              // CallLogStatus.*
    val isVideo: Boolean,
    val startedAt: Long,
    val durationSec: Long = 0L       // ✅ chỉ tính thời gian từ lúc CONNECTED thật sự, không tính lúc đổ chuông/dialing
)