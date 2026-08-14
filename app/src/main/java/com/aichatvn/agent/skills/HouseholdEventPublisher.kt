package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HouseholdEventPublisher
 *
 * ✅ MỚI: nguồn DUY NHẤT build + gửi envelope POST /household/event — dùng chung cho
 * OnvifEventRelay (báo động camera qua ONVIF), WebhookGatewayService (Tuya sync loop +
 * handleTuyaCommand trên Camera Node), và SmartSwitchSkill (Client điều khiển Tuya trực
 * tiếp). Thay thế hoàn toàn cơ chế camera_alarm cũ (deviceCode đích cố định, gõ tay 1
 * chiều) — giờ mọi máy cùng khai [AppConfigDefaults.HOUSEHOLD_ID] sẽ tự động nhận
 * broadcast của nhau, không phân biệt vai trò.
 *
 * KHÔNG kiểm tra DEVICE_ROLE ở đây — publisher này bình đẳng cho mọi máy, việc gate theo
 * role (nếu cần, như trường hợp vòng poll syncTuyaDeviceStates() để tránh N máy cùng tự
 * poll rồi cùng publish trùng nhau) là trách nhiệm của nơi GỌI publish()/publishXxx(), không
 * phải của lớp này.
 */
@Singleton
class HouseholdEventPublisher @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val callSkill: CallSkill,
    private val logger: Logger,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @return true nếu Gateway xác nhận đã nhận (không đảm bảo đã tới tay máy nào trong
     *   household — chỉ nghĩa là request thành công). false khi thiếu cấu hình
     *   (Gateway/HouseholdId rỗng) hoặc lỗi mạng — nơi gọi tự quyết định có log/retry hay
     *   bỏ qua, không throw ra ngoài.
     */
    suspend fun publish(type: String, sourceDeviceId: String, payload: JSONObject): Boolean {
        val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
        val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
        val householdId = configProvider.getString(AppConfigDefaults.HOUSEHOLD_ID, "").trim()

        if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
            logger.w("HouseholdEvent", "⚠️ Thiếu cấu hình Gateway, không phát được sự kiện type=$type.")
            return false
        }
        if (householdId.isBlank()) {
            logger.d("HouseholdEvent", "Household ID chưa cấu hình — bỏ qua phát sự kiện type=$type.")
            return false
        }

        val myDeviceCode = callSkill.getOrCreateMyDeviceCode()

        return withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("token", gatewayToken)
                    put("householdId", householdId)
                    put("eventId", UUID.randomUUID().toString())
                    put("timestamp", System.currentTimeMillis())
                    put("type", type)
                    put("sourceDeviceId", sourceDeviceId)
                    put("originDeviceCode", myDeviceCode)
                    put("payload", payload)
                }
                val request = Request.Builder()
                    .url("$gatewayUrl/household/event")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("HouseholdEvent", "⚠️ Phát sự kiện thất bại (type=$type): ${response.code}")
                    }
                    response.isSuccessful
                }
            } catch (e: Exception) {
                logger.e("HouseholdEvent", "❌ Lỗi phát sự kiện (type=$type): ${e.message}")
                false
            }
        }
    }

    suspend fun publishTuyaStateChange(deviceId: String, deviceName: String, newState: Boolean): Boolean =
        publish("tuya_state_change", deviceId, JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("newState", newState)
        })

    suspend fun publishCameraAlarm(cameraId: String): Boolean =
        publish("camera_alarm", cameraId, JSONObject().apply { put("cameraId", cameraId) })
}
