package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceCommandGatewayClient
 *
 * ✅ MỚI: tách ra từ WebhookGatewayService.sendDeviceCommandToHome() (trước đây định nghĩa sẵn
 * trong Service nhưng CHƯA nơi nào gọi tới — dead code). Lý do tách thành class riêng thay vì để
 * nguyên trong Service: nơi cần gọi hàm này là SmartSwitchSkill (khi Local thất bại và đang bật
 * Chế độ Local-only) — SmartSwitchSkill là 1 Plugin/Singleton thường, không nên phụ thuộc vào
 * vòng đời của 1 Android Service (bind/unbind, Service có thể chưa chạy...) chỉ để gọi 1 lệnh
 * POST đơn giản. Cùng mẫu với GatewaySignalingManager.kt (OkHttpClient riêng, không share với
 * Service, không giữ kết nối dài hạn).
 *
 * Đây CHỈ là phía GỬI (Client, ở ngoài LAN nhà). Phía NHẬN (Camera Node mở SSE
 * /device/command/stream/{deviceCode}, thực thi lệnh, báo kết quả qua /device/command_result)
 * vẫn giữ nguyên trong WebhookGatewayService — đó là trách nhiệm gắn liền vòng đời Service
 * (chạy nền liên tục), không tách ra được.
 *
 * ✅ MỚI: xác nhận THẬT (Camera Node đã thực thi thành công/thất bại, không chỉ "Gateway đã nhận
 * lệnh") giờ có đường về đầy đủ: sendDeviceCommandToHomeAndAwaitResult() tự sinh requestId ở phía
 * Client (server chấp nhận requestId do client cung cấp — xem app.py: `requestId or uuid.uuid4()`)
 * TRƯỚC khi gửi, đăng ký sẵn 1 CompletableDeferred trong pendingRequests theo đúng requestId đó,
 * rồi mới POST — tránh race nếu Camera Node phản hồi cực nhanh. Khi WebhookGatewayService nhận
 * được sự kiện type="device_command_result" qua /stream/{token} (xem handleIncomingWebhookEvent),
 * nó gọi resolvePendingResult() ở đây để hoàn tất đúng Deferred đang chờ.
 *
 * ⚠️ Vẫn còn 1 khoảng hở nhỏ, chấp nhận được: nếu app bị kill hoàn toàn giữa lúc đang chờ (Deferred
 * mất theo tiến trình), kết quả trả về sau đó rơi vào "không tìm thấy pending request" và bị bỏ
 * qua ở resolvePendingResult() — không crash, chỉ đơn giản là không ai nhận kết quả đó nữa.
 */
@Singleton
class DeviceCommandGatewayClient @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) {
    data class GatewayCommandResult(
        val success: Boolean,
        val message: String,
        val requestId: String? = null,
        // ✅ MỚI: true = kết quả THẬT (Gateway từ chối ngay, hoặc Camera Node đã báo về qua
        // device_command_result). false = CHỈ xảy ra khi sendDeviceCommandToHomeAndAwaitResult()
        // hết thời gian chờ mà chưa có xác nhận — success=true trong trường hợp đó chỉ là "đã gửi,
        // không rõ kết quả", không phải "chắc chắn đã bật/tắt".
        val confirmed: Boolean = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ✅ MỚI: requestId -> Deferred đang chờ kết quả thật từ Camera Node. Được đăng ký bởi
    // sendDeviceCommandToHomeAndAwaitResult() TRƯỚC khi gửi lệnh, và hoàn tất bởi
    // resolvePendingResult() khi WebhookGatewayService nhận được device_command_result qua SSE.
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<GatewayCommandResult>>()

    /**
     * Gửi lệnh thiết bị (vd Tuya) tới Camera Node ở nhà qua Gateway. Trả về ngay sau khi Gateway
     * XÁC NHẬN ĐÃ NHẬN lệnh (hoặc lỗi cấu hình/mạng/Camera Node offline) — KHÔNG đợi Camera Node
     * thực thi xong. Dùng trực tiếp hàm này khi không cần chờ xác nhận thật (fire-and-forget);
     * dùng sendDeviceCommandToHomeAndAwaitResult() bên dưới khi cần biết chắc kết quả thực thi.
     *
     * @param deviceType khớp với nhánh "when" trong WebhookGatewayService.handleIncomingDeviceCommand()
     *   phía Camera Node — hiện chỉ hỗ trợ "tuya", nhưng cố tình để dạng chuỗi tự do (không phải
     *   enum) vì đây là phần được xây sẵn để dần chuyển thiết bị Tuya sang MQTT sau này — thêm
     *   loại thiết bị mới chỉ cần thêm 1 nhánh "when" ở Camera Node, không đổi gì ở đây.
     * @param command JSON payload thô cho loại thiết bị đó — với "tuya":
     *   {"deviceName": "...", "action": "on" | "off"}, khớp với handleTuyaCommand().
     * @param requestId nếu truyền vào, dùng đúng giá trị này khi POST (để khớp với Deferred đã
     *   đăng ký trước trong pendingRequests) — nếu để null, server tự sinh UUID riêng (chỉ phù hợp
     *   khi gọi fire-and-forget, không định chờ kết quả).
     */
    suspend fun sendDeviceCommandToHome(
        deviceType: String,
        command: JSONObject,
        requestId: String? = null
    ): GatewayCommandResult =
        withContext(Dispatchers.IO) {
            try {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                // deviceCode của Camera Node ở nhà — KHÁC với deviceCode của chính máy đang chạy
                // hàm này. Người dùng tự nhập trong Settings (giống lưu số điện thoại để gọi).
                val homeDeviceCode = configProvider.getString(AppConfigDefaults.HOME_CAMERA_NODE_DEVICE_CODE).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank() || homeDeviceCode.isBlank()) {
                    return@withContext GatewayCommandResult(
                        false, "Chưa cấu hình đầy đủ Gateway hoặc Mã Camera Node ở nhà."
                    )
                }

                val bodyJson = JSONObject().apply {
                    put("token", gatewayToken)
                    put("targetDeviceCode", homeDeviceCode)
                    put("deviceType", deviceType)
                    put("command", command)
                    if (requestId != null) put("requestId", requestId)
                }
                val request = Request.Builder()
                    .url("$gatewayUrl/device/command")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val status = json.optString("status", "error")
                    when (status) {
                        "success" -> {
                            val effectiveRequestId = json.optString("requestId").ifBlank { requestId }
                            logger.i("DeviceCommandGateway", "🌐 Đã gửi lệnh ($deviceType) tới Camera Node '$homeDeviceCode', requestId=$effectiveRequestId")
                            GatewayCommandResult(true, "Đã gửi lệnh, đang chờ Camera Node xử lý...", effectiveRequestId)
                        }
                        "not_registered" -> GatewayCommandResult(false, "Camera Node ở nhà hiện không online.")
                        else -> GatewayCommandResult(false, json.optString("message", "Gửi lệnh thất bại"))
                    }
                }
            } catch (e: Exception) {
                logger.e("DeviceCommandGateway", "❌ Lỗi gửi lệnh thiết bị: ${e.message}", e)
                GatewayCommandResult(false, "Lỗi kết nối: ${e.message}")
            }
        }

    /**
     * Bản có CHỜ kết quả thực thi THẬT từ Camera Node, dùng timeout để không treo vô hạn nếu
     * Camera Node offline sau khi đã nhận lệnh (mất mạng giữa chừng, app bị kill...). Đây là hàm
     * SmartSwitchSkill nên dùng thay vì sendDeviceCommandToHome() thô ở trên.
     *
     * Luồng: sinh requestId tại Client -> đăng ký Deferred TRƯỚC khi gửi (tránh race nếu Camera
     * Node phản hồi cực nhanh) -> POST /device/command -> nếu Gateway từ chối ngay (lỗi cấu hình/
     * not_registered/lỗi mạng), trả lỗi ngay, không cần chờ. Nếu Gateway xác nhận đã nhận, chờ
     * Deferred tối đa [resultTimeoutMs] — hoàn tất bởi resolvePendingResult() khi kết quả thật về
     * qua device_command_result. Hết thời gian chờ mà chưa có kết quả: trả về thành công dè dặt
     * ("đã gửi nhưng chưa xác nhận") thay vì coi là lỗi, vì lệnh CÓ THỂ vẫn đang được xử lý.
     */
    suspend fun sendDeviceCommandToHomeAndAwaitResult(
        deviceType: String,
        command: JSONObject,
        resultTimeoutMs: Long = 20_000L
    ): GatewayCommandResult {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<GatewayCommandResult>()
        pendingRequests[requestId] = deferred

        try {
            val sendResult = sendDeviceCommandToHome(deviceType, command, requestId)
            if (!sendResult.success) {
                // Gateway từ chối ngay lập tức — không có gì để chờ, Camera Node sẽ không bao giờ
                // nhận được lệnh này nên cũng sẽ không bao giờ báo kết quả.
                return sendResult
            }

            val confirmed = withTimeoutOrNull(resultTimeoutMs) { deferred.await() }
            return confirmed ?: GatewayCommandResult(
                success = true,
                message = "Đã gửi lệnh tới Camera Node nhưng chưa nhận được xác nhận thực thi sau ${resultTimeoutMs / 1000}s.",
                requestId = requestId,
                confirmed = false
            )
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * Được WebhookGatewayService gọi khi nhận sự kiện type="device_command_result" qua SSE
     * (/stream/{token}) — hoàn tất đúng Deferred đang chờ theo requestId. Không tìm thấy pending
     * request tương ứng (đã timeout/bị dọn/app từng bị kill giữa chừng) chỉ log rồi bỏ qua, không
     * phải lỗi nghiêm trọng.
     */
    fun resolvePendingResult(requestId: String, success: Boolean, message: String) {
        val deferred = pendingRequests.remove(requestId)
        if (deferred == null) {
            logger.d("DeviceCommandGateway", "resolvePendingResult: không tìm thấy pending request cho requestId=$requestId (có thể đã timeout).")
            return
        }
        deferred.complete(GatewayCommandResult(success, message, requestId))
    }
}