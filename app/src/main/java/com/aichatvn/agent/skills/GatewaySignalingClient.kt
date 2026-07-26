package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GatewaySignalingClient
 *
 * Interface tối giản cho tầng signaling cuộc gọi P2P (WebRTC), tách riêng khỏi
 * implementation cụ thể để CallSkill có thể test/mock độc lập nếu cần sau này.
 */
interface GatewaySignalingClient {
    var onCallSignalReceived: ((JSONObject) -> Unit)?
    fun sendCallSignal(targetDeviceCode: String, payload: JSONObject)
}

/**
 * GatewaySignalingManager
 *
 * Module trung gian gửi/nhận tín hiệu báo hiệu (SDP offer/answer, ICE candidate,
 * call_end) giữa 2 thiết bị AIChatVN2, tận dụng lại kênh Webhook Gateway SSE có
 * sẵn (WebhookGatewayService) làm đường truyền — không cần dựng thêm server
 * trung gian riêng cho WebRTC signaling.
 *
 * - sendCallSignal(): POST tín hiệu lên Render Gateway (endpoint /call_signal),
 *   Gateway sẽ đẩy lại cho máy đích qua stream SSE của chính nó.
 * - dispatchIncomingSignal(): được WebhookGatewayService gọi khi nhận được sự
 *   kiện "call_signal" trên SSE, chuyển tiếp cho CallSkill xử lý qua callback
 *   onCallSignalReceived.
 */
@Singleton
class GatewaySignalingManager @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) : GatewaySignalingClient {

    private val httpClient = OkHttpClient()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    override var onCallSignalReceived: ((JSONObject) -> Unit)? = null

    override fun sendCallSignal(targetDeviceCode: String, payload: JSONObject) {
        ioScope.launch {
            try {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    logger.w("Signaling", "⚠️ Không thể gửi tín hiệu cuộc gọi: Thiếu Gateway URL/Token")
                    return@launch
                }

                val bodyJson = JSONObject().apply {
                    put("token", gatewayToken)
                    put("targetDeviceCode", targetDeviceCode)
                    put("signal", payload)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url("$gatewayUrl/call_signal")
                    .post(bodyJson.toString().toRequestBody(mediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("Signaling", "⚠️ Gửi tín hiệu cuộc gọi thất bại, HTTP: ${response.code}")
                    } else {
                        logger.d("Signaling", "✅ Đã gửi tín hiệu (${payload.optString("type")}) tới deviceCode=$targetDeviceCode")
                    }
                }
            } catch (e: Exception) {
                logger.e("Signaling", "❌ Lỗi khi gửi tín hiệu cuộc gọi: ${e.message}", e)
            }
        }
    }

    /**
     * Được gọi từ WebhookGatewayService khi SSE nhận sự kiện call_signal.
     * Chuyển tiếp nguyên payload cho CallSkill (nếu đã đăng ký lắng nghe).
     */
    fun dispatchIncomingSignal(signalJson: JSONObject) {
        logger.i("Signaling", "📥 Nhận tín hiệu cuộc gọi đến: type=${signalJson.optString("type")}")
        onCallSignalReceived?.invoke(signalJson)
    }
}
