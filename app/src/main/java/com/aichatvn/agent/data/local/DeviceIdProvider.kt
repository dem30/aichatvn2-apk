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

    suspend fun markActivated() {
        configProvider.set(KEY_ACTIVATION_STATUS, "true", affectsConnection = false)
    }
}
