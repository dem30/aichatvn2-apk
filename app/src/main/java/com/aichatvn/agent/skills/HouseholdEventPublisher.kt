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

    // ✅ MỚI (MQTT Symmetric Broker, PATCH A): song song publishTuyaStateChange() ở trên — cùng
    // hạ tầng /household/event, chỉ khác type để client phân biệt. Gọi từ MqttDeviceController
    // sau khi lệnh chủ động thành công (turnOn/turnOff) VÀ từ handleIncomingStateMessage() khi
    // subscribe realtime từ broker phát hiện đổi trạng thái (source="subscribe") — không cần vòng
    // poll nền riêng vì subscribe MQTT đã là realtime.
    suspend fun publishMqttStateChange(
        deviceId: String,
        deviceName: String,
        isOn: Boolean,
        source: String = "mqtt"
    ): Boolean =
        publish("mqtt_state_change", deviceId, JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("newState", isOn)
            put("source", source)
        })

    // ✅ MỚI (Dimmer): song song publishTuyaStateChange()/publishMqttStateChange() ở trên,
    // KHÔNG tái dùng 2 hàm đó vì payload của chúng cố định "newState: Boolean" — mức độ %
    // là Int, ép kiểu vào đó sẽ sai ngữ nghĩa. Dùng chung "protocol" để phân biệt nguồn
    // (SmartSwitchSkill biết controller.protocol khi gọi), thay vì tách 2 hàm riêng như trên
    // — vì cả Tuya lẫn MQTT dimmer chia sẻ đúng 1 hình dạng payload (deviceId/deviceName/
    // percent), không có khác biệt nào cần tách như "source" của MQTT (subscribe vs lệnh
    // chủ động — dimmer hiện chưa có luồng subscribe realtime riêng để phân biệt).
    suspend fun publishLevelChange(deviceId: String, deviceName: String, percent: Int, protocol: String): Boolean =
        publish("device_level_change", deviceId, JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("level", percent)
            put("protocol", protocol)
        })

    // ✅ MỚI: máy đang publish camera_alarm mà ĐANG CÙNG LAN với camera đó (mới tự fetch được
    // ảnh LAN thật lúc trigger scan sớm — xem OnvifEventRelay.relayMotionEvent() và nhánh nhận
    // camera_alarm_push trong WebhookGatewayService) truyền kèm imageBytes vào đây. Ảnh được
    // upload lên /upload-household-image (endpoint MỚI, xác thực bằng token — khác
    // /upload-image vốn dành cho luồng website widget cần widgetKey) TRƯỚC khi publish, rồi chỉ
    // nhét imageUrl (nhẹ) vào payload — KHÔNG nhét base64 thẳng vào payload/household/event vì
    // kênh đó là SSE broadcast tới mọi máy trong household, ảnh vài trăm KB base64 hoá sẽ làm
    // phình payload không cần thiết cho những máy không quan tâm.
    //
    // imageBytes = null (không cùng LAN với camera, hoặc fetch/upload ảnh thất bại) vẫn publish
    // bình thường, KHÔNG chặn báo động — máy nhận sẽ tự quét cục bộ như hành vi cũ (xem
    // WebhookGatewayService.handleIncomingHouseholdEvent()).
    suspend fun publishCameraAlarm(cameraId: String, imageBytes: ByteArray? = null): Boolean {
        val payload = JSONObject().apply { put("cameraId", cameraId) }

        if (imageBytes != null) {
            val imageUrl = uploadImageAndGetUrl(imageBytes)
            if (imageUrl != null) {
                payload.put("imageUrl", imageUrl)
            } else {
                logger.w("HouseholdEvent", "⚠️ Upload ảnh báo động thất bại cho camera=$cameraId — vẫn phát báo động không kèm ảnh.")
            }
        }

        return publish("camera_alarm", cameraId, payload)
    }

    // ✅ MỚI: Client (có thể không cùng LAN với camera) yêu cầu 1 ảnh mới — thay vì tự đoán
    // máy nào là "Camera Node", broadcast cho CẢ household; mỗi máy nhận tự quyết định có trả
    // lời hay không dựa trên networkContext.isOnSavedSubnet() của CHÍNH nó (xem nhánh
    // "camera_snapshot_request" trong WebhookGatewayService.handleIncomingHouseholdEvent()).
    // requestId do người gọi tự sinh (UUID) — dùng để khớp response, vì có thể có nhiều yêu
    // cầu đang chờ song song (nhiều camera, hoặc user bấm lại).
    suspend fun publishCameraSnapshotRequest(cameraId: String, requestId: String): Boolean =
        publish("camera_snapshot_request", cameraId, JSONObject().apply {
            put("cameraId", cameraId)
            put("requestId", requestId)
        })

    // ✅ MỚI: máy đã xác nhận cùng LAN với camera (xem nhánh nhận ở WebhookGatewayService) gọi
    // hàm này SAU KHI fetch LAN thành công — upload ảnh rồi publish lại đúng requestId để máy
    // đã yêu cầu khớp về đúng request đang chờ. imageBytes luôn khác null ở đây (caller chỉ gọi
    // khi fetch thành công) — không có "response thất bại": máy không fetch được thì đơn giản
    // không publish gì cả, để máy khác (nếu có, cùng LAN) có cơ hội trả lời; người yêu cầu tự
    // timeout nếu không ai trả lời.
    suspend fun publishCameraSnapshotResponse(requestId: String, cameraId: String, imageBytes: ByteArray): Boolean {
        val imageUrl = uploadImageAndGetUrl(imageBytes) ?: run {
            logger.w("HouseholdEvent", "⚠️ Upload ảnh snapshot thất bại cho requestId=$requestId cameraId=$cameraId — không publish response.")
            return false
        }
        return publish("camera_snapshot_response", cameraId, JSONObject().apply {
            put("requestId", requestId)
            put("cameraId", cameraId)
            put("imageUrl", imageUrl)
        })
    }

    // ✅ MỚI: POST /upload-household-image (endpoint mới trên gateway, song song /upload-image
    // của website widget nhưng xác thực bằng token thay vì widgetKey). Trả về imageUrl ngắn hạn
    // (TTL 10 phút phía server — đủ để client đang ở xa tải về ngay sau khi nhận SSE báo động,
    // không cần lưu vĩnh viễn).
    private suspend fun uploadImageAndGetUrl(imageBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) return@withContext null

                val imageBase64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                val bodyJson = JSONObject().apply {
                    put("token", gatewayToken)
                    put("imageBase64", imageBase64)
                }
                val request = Request.Builder()
                    .url("$gatewayUrl/upload-household-image")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("HouseholdEvent", "⚠️ Upload ảnh báo động thất bại, HTTP: ${response.code}")
                        return@withContext null
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optString("status") != "success") return@withContext null
                    json.optString("imageUrl").ifBlank { null }
                }
            } catch (e: Exception) {
                logger.e("HouseholdEvent", "❌ Lỗi upload ảnh báo động: ${e.message}")
                null
            }
        }
}