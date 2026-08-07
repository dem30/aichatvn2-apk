package com.aichatvn.agent.data.local

import com.aichatvn.agent.config.AppConfigProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ✅ MỚI: Sinh device_id 1 lần duy nhất cho toàn app, tái dùng AppConfigProvider (Room) thay vì
// tạo bảng mới. 2 key nội bộ, KHÔNG khai báo trong AppConfigDefaults vì không phải cấu hình
// người dùng chỉnh sửa — đây là state hệ thống tự quản lý, không hiện trên SettingsScreen.
@Singleton
class DeviceIdProvider @Inject constructor(
    private val configProvider: AppConfigProvider
) {
    companion object {
        private const val KEY_DEVICE_ID = "local.device_id"
        private const val KEY_ACTIVATION_STATUS = "local.activation_status"
        // ✅ MỚI: chữ ký HMAC server trả về khi activate thành công (xem app.py:
        // sign_device_id()/verify_activation_token()) — PHẢI gửi kèm mỗi lần tạo invite,
        // vì đây là bằng chứng "đã activate" duy nhất sống sót qua server restart (khác với
        // KEY_ACTIVATION_STATUS chỉ là cờ hiển thị cục bộ, không server nào verify được).
        private const val KEY_ACTIVATION_TOKEN = "local.activation_token"
    }

    // affectsConnection = false: đây là state cục bộ, không phải config Gateway, không cần
    // WebhookGatewayService ngắt/nối lại SSE khi thay đổi.
    suspend fun getOrCreateDeviceId(): String {
        val existing = configProvider.getString(KEY_DEVICE_ID, "")
        if (existing.isNotBlank()) return existing
        val newId = UUID.randomUUID().toString()
        configProvider.set(KEY_DEVICE_ID, newId, affectsConnection = false)
        return newId
    }

    suspend fun isActivated(): Boolean =
        configProvider.getString(KEY_ACTIVATION_STATUS, "false") == "true"

    // ✅ SỬA: giờ bắt buộc truyền activationToken nhận từ InviteApiService.activateInvite()
    // — lưu cả cờ trạng thái (để UI đọc nhanh, không đổi hành vi cũ) lẫn token (để
    // createInvite() sau này gửi lên server verify).
    suspend fun markActivated(activationToken: String) {
        configProvider.set(KEY_ACTIVATION_STATUS, "true", affectsConnection = false)
        configProvider.set(KEY_ACTIVATION_TOKEN, activationToken, affectsConnection = false)
    }

    suspend fun getActivationToken(): String =
        configProvider.getString(KEY_ACTIVATION_TOKEN, "")
}