package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnvifEventRelay
 *
 * ✅ MỚI: "vá" khoảng trống Local-only đã biết — ONVIF Events (báo chuyển động tức thời qua
 * PullPoint) trước đây CHỈ hoạt động khi điện thoại đang cùng Wi-Fi nhà với camera, vì bản thân
 * việc subscribe/pull ONVIF là 1 kết nối LAN trực tiếp từ điện thoại tới IP nội bộ của camera —
 * không có gì vượt NAT khi điện thoại ra khỏi nhà (khác hẳn Tuya, luôn có Cloud API để rơi
 * xuống, hay video RTSP đi qua P2P WebRTC).
 *
 * Cách vá: ĐÚNG mẫu đã dùng cho Tuya khi Local-only bật (xem DeviceCommandGatewayClient.kt) — 1
 * máy CỐ ĐỊNH ở nhà, đặt deviceRole = "camera_node" (Cài đặt), tự subscribe ONVIF cục bộ (nó
 * LUÔN ở trong LAN nên gọi bình thường), rồi mỗi khi có sự kiện chuyển động, RELAY qua Gateway
 * (route mới /camera/onvif_event, xem app.py) tới đúng deviceCode cần được báo — đọc từ
 * [AppConfigDefaults.CAMERA_ALARM_NOTIFY_DEVICE_CODE]; để trống thì mặc định relay về CHÍNH
 * thiết bị này (trường hợp máy quản lý camera cũng chính là Camera Node, quay vòng qua Gateway
 * tuy hơi thừa nhưng vô hại — vẫn dùng lại y hệt hạ tầng camera_alarm sẵn có, không cần code
 * riêng cho case này).
 *
 * Server (Gateway) không cần hiểu gì về ONVIF — endpoint mới chỉ forward vào ĐÚNG kênh
 * camera_alarm_queues/dedup đã có sẵn cho báo động camera gốc. Máy nhận ở đầu kia
 * (WebhookGatewayService.startCameraAlarmSSE()) xử lý y hệt, gọi lại scan() bình thường — KHÔNG
 * cần sửa gì thêm ở phía nhận.
 *
 * ⚠️ CHỐNG BẮN ĐÔI (double-trigger) — đúng yêu cầu khi thêm tính năng này, kiểm tra ở 3 lớp:
 *
 * 1) ĐIỀU KIỆN KÍCH HOẠT subscribe (lớp quan trọng nhất — chặn từ gốc): chỉ subscribe ONVIF cho
 *    camera nào đồng thời thoả CẢ:
 *      - onvifEnabled == 1        (người dùng đã bật Switch ONVIF)
 *      - onvifEventUrl không rỗng (đã probe ra Events service — xem CameraCapabilityProber)
 *      - enableAlarmPush == 0     (camera CHƯA dùng đường "Báo động" gốc qua push URL riêng)
 *    2 đường (push gốc vs ONVIF relay) cùng mục đích "nhắc quét sớm" — bật cả 2 cho cùng 1
 *    camera khiến scan() bị gọi 2 lần cho cùng 1 lần chuyển động thật. Nếu người dùng lỡ bật cả
 *    2, ONVIF nhường cho đường push gốc (rẻ hơn: camera tự đẩy, không tốn 1 subscription +
 *    coroutine sống 24/7 trên Camera Node).
 * 2) Mỗi cameraId chỉ giữ ĐÚNG 1 Job đang chạy, quản lý qua [activeJobs] — trước khi tạo Job mới
 *    (cấu hình đổi, hoặc vòng reconcile định kỳ phát hiện camera vẫn hợp lệ) luôn hủy Job cũ
 *    trước, không bao giờ để 2 Job cùng chạy song song cho 1 camera.
 * 3) Server-side (lớp phòng vệ cuối): endpoint /camera/onvif_event dùng LẠI đúng
 *    _last_accepted_camera_alarm (dedup 10s, khoá theo (device_code, camera_id)) — dù lỡ có bug
 *    khiến push gốc VÀ ONVIF relay cùng bắn cho 1 camera trong 10s, gateway cũng chỉ forward 1
 *    lần.
 */
@Singleton
class OnvifEventRelay @Inject constructor(
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider,
    private val callSkill: CallSkill,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "OnvifEventRelay"

        // Chu kỳ soát lại danh sách camera đủ điều kiện — đủ ngắn để phản ứng nhanh khi người
        // dùng vừa bật/tắt Switch ONVIF hay đổi enableAlarmPush, không cần tức thời như SSE.
        private const val RECONCILE_INTERVAL_MS = 20_000L

        // Timeout PullMessages phía server ONVIF — long-poll kiểu chờ tới khi có sự kiện hoặc
        // hết thời gian này. Không đặt quá dài để vòng lặp còn dịp kiểm tra lại điều kiện kích
        // hoạt (camera có thể vừa bị tắt Switch giữa chừng).
        private const val PULL_TIMEOUT_SECONDS = 20
        private const val PULL_MESSAGE_LIMIT = 10

        // Sau chừng này lần Pull liên tiếp, chủ động resubscribe lại từ đầu — phòng trường hợp
        // subscription hết hạn phía camera (TerminationTime) mà ta không parse chính xác được
        // (best-effort, không kéo XML parser đầy đủ chỉ để đọc 1 giá trị — cùng tinh thần với
        // CameraCapabilityProber.tryProbeOnvif()).
        private const val RESUBSCRIBE_AFTER_N_PULLS = 25

        private const val ERROR_RETRY_DELAY_MS = 10_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout((PULL_TIMEOUT_SECONDS + 10).toLong(), TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // cameraId -> Job đang chạy vòng lặp subscribe+pull cho camera đó.
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // cameraId -> "chữ ký" cấu hình (onvifEventUrl + credential) lúc Job hiện tại được tạo — đổi
    // 1 trong các giá trị này (sửa URL/tài khoản camera) sẽ khiến vòng reconcile huỷ Job cũ và
    // tạo lại Job mới với cấu hình đúng, thay vì tiếp tục chạy với subscription trỏ tới URL cũ.
    private val activeJobFingerprints = ConcurrentHashMap<String, String>()

    /**
     * Gọi 1 lần từ WebhookGatewayService.onCreate() — vô hại nếu gọi trên máy "client" (không
     * phải Camera Node), vòng lặp tự đứng chờ, không tốn tài nguyên đáng kể, cùng triết lý với
     * startDeviceCommandSSE() đã có cho Tuya.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    reconcileOnce()
                } catch (e: Exception) {
                    logger.e(TAG, "Lỗi vòng soát camera ONVIF: ${e.message}", e)
                }
                delay(RECONCILE_INTERVAL_MS)
            }
        }
    }

    private suspend fun reconcileOnce() {
        val deviceRole = configProvider.getString(AppConfigDefaults.DEVICE_ROLE, "client").trim()
        if (deviceRole != "camera_node") {
            // Máy "client": không phải Camera Node, không được subscribe ONVIF hộ ai cả — hủy
            // sạch mọi Job đang chạy (trường hợp người dùng vừa đổi vai trò máy từ camera_node
            // sang client) rồi đứng chờ.
            cancelAllJobs()
            return
        }

        val cameras = withContext(Dispatchers.IO) { database.cameraDao().getAllCameras() }
        val eligible = cameras.filter { isEligibleForOnvifRelay(it) }
        val eligibleIds = eligible.map { it.id.trim() }.toSet()

        // Hủy Job của camera không còn đủ điều kiện nữa (bị xoá, tắt Switch ONVIF, hoặc vừa bật
        // enableAlarmPush — xem isEligibleForOnvifRelay()).
        for (cameraId in activeJobs.keys.toList()) {
            if (cameraId !in eligibleIds) {
                cancelJob(cameraId, "không còn đủ điều kiện relay ONVIF")
            }
        }

        for (camera in eligible) {
            val cameraId = camera.id.trim()
            val fingerprint = fingerprintOf(camera)
            val existingFingerprint = activeJobFingerprints[cameraId]

            if (activeJobs.containsKey(cameraId) && existingFingerprint == fingerprint) {
                continue // Job đang chạy đúng cấu hình hiện tại, không cần đổi gì.
            }

            // Cấu hình đổi (hoặc chưa từng chạy) — hủy Job cũ (nếu có) trước khi tạo Job mới,
            // đảm bảo KHÔNG BAO GIỜ 2 Job cùng sống cho 1 cameraId (chống bắn đôi ở lớp #2).
            if (activeJobs.containsKey(cameraId)) {
                cancelJob(cameraId, "cấu hình ONVIF vừa đổi, subscribe lại")
            }

            val job = CoroutineScope(Dispatchers.IO).launch {
                runCameraLoop(camera)
            }
            activeJobs[cameraId] = job
            activeJobFingerprints[cameraId] = fingerprint
        }
    }

    /**
     * ⚠️ ĐIỀU KIỆN KÍCH HOẠT — xem giải thích đầy đủ ở KDoc đầu file. Đây là lớp chặn double-
     * trigger QUAN TRỌNG NHẤT: enableAlarmPush == 1 luôn thắng, ONVIF relay nhường.
     */
    private fun isEligibleForOnvifRelay(camera: CameraConfigEntity): Boolean {
        if (camera.onvifEnabled != 1) return false
        if (camera.onvifEventUrl.isNullOrBlank()) return false
        if (camera.enableAlarmPush == 1) {
            logger.d(
                TAG,
                "Camera ${camera.id.trim()} đang bật cả ONVIF lẫn Báo động gốc (push) — " +
                    "nhường cho đường push gốc để tránh scan() bị gọi 2 lần cho cùng 1 sự kiện. " +
                    "Tắt 1 trong 2 Switch nếu không muốn thấy log này."
            )
            return false
        }
        return true
    }

    private fun fingerprintOf(camera: CameraConfigEntity): String =
        "${camera.onvifEventUrl}|${camera.snapshotUsername}|${camera.snapshotPassword}"

    private fun cancelAllJobs() {
        for (cameraId in activeJobs.keys.toList()) {
            cancelJob(cameraId, "deviceRole không còn là camera_node")
        }
    }

    private fun cancelJob(cameraId: String, reason: String) {
        activeJobs.remove(cameraId)?.cancel()
        activeJobFingerprints.remove(cameraId)
        logger.i(TAG, "🛑 Dừng relay ONVIF cho camera $cameraId ($reason)")
    }

    // ===== Vòng lặp subscribe + pull cho 1 camera =====

    private suspend fun runCameraLoop(camera: CameraConfigEntity) {
        val cameraId = camera.id.trim()
        val eventsUrl = camera.onvifEventUrl?.trim().orEmpty()
        val username = camera.snapshotUsername
        val password = camera.snapshotPassword

        logger.i(TAG, "🔌 Bắt đầu relay ONVIF cho camera $cameraId ($eventsUrl)")

        while (coroutineContext.isActive) {
            var pullPointUrl: String? = null
            try {
                pullPointUrl = subscribe(eventsUrl, username, password)
                if (pullPointUrl == null) {
                    logger.w(TAG, "⚠️ Subscribe ONVIF thất bại cho $cameraId, thử lại sau ${ERROR_RETRY_DELAY_MS / 1000}s")
                    delay(ERROR_RETRY_DELAY_MS)
                    continue
                }

                var pullCount = 0
                while (coroutineContext.isActive && pullCount < RESUBSCRIBE_AFTER_N_PULLS) {
                    val motionDetected = pullOnce(pullPointUrl, username, password)
                    if (motionDetected) {
                        logger.i(TAG, "🚨 ONVIF phát hiện chuyển động: camera $cameraId")
                        relayMotionEvent(cameraId)
                    }
                    pullCount++
                }
                // Chủ động resubscribe theo chu kỳ — an toàn hơn là cố parse chính xác
                // TerminationTime trả về lúc subscribe (xem comment RESUBSCRIBE_AFTER_N_PULLS).
                unsubscribeBestEffort(pullPointUrl, username, password)
            } catch (e: Exception) {
                logger.e(TAG, "❌ Lỗi vòng lặp ONVIF cho $cameraId: ${e.message}")
                if (pullPointUrl != null) {
                    unsubscribeBestEffort(pullPointUrl, username, password)
                }
                delay(ERROR_RETRY_DELAY_MS)
            }
        }
    }

    /** Trả về địa chỉ PullPoint (SubscriptionReference/Address) nếu subscribe thành công. */
    private suspend fun subscribe(eventsUrl: String, username: String?, password: String?): String? =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:tev="http://www.onvif.org/ver10/events/wsdl">
                  <s:Body>
                    <tev:CreatePullPointSubscription/>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val response = soapPost(eventsUrl, body, username, password) ?: return@withContext null
            // Namespace prefix của thẻ Address thay đổi tuỳ hãng (wsa:Address, đôi khi không có
            // prefix) — regex lỏng, đủ dùng cho probe/subscribe, không kéo XML parser đầy đủ,
            // cùng tinh thần với CameraCapabilityProber.tryProbeOnvif().
            Regex("<(?:\\w+:)?Address>(.*?)</(?:\\w+:)?Address>", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.groupValues?.get(1)?.trim()
                ?: eventsUrl // Một số camera dùng chung URL Events cho cả PullMessages.
        }

    /** true nếu phát hiện tín hiệu chuyển động trong đợt Pull này. */
    private suspend fun pullOnce(pullPointUrl: String, username: String?, password: String?): Boolean =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:tev="http://www.onvif.org/ver10/events/wsdl">
                  <s:Body>
                    <tev:PullMessages>
                      <tev:Timeout>PT${PULL_TIMEOUT_SECONDS}S</tev:Timeout>
                      <tev:MessageLimit>$PULL_MESSAGE_LIMIT</tev:MessageLimit>
                    </tev:PullMessages>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val response = soapPost(pullPointUrl, body, username, password) ?: return@withContext false

            // ⚠️ Heuristic có chủ ý (không parse XML đầy đủ, cùng tinh thần best-effort với
            // CameraCapabilityProber): coi là "có chuyển động" nếu response chứa 1 Topic nhắc
            // tới Motion VÀ 1 SimpleItem giá trị true đi kèm — đủ dùng cho đa số camera hỗ trợ
            // ONVIF Profile S/T (Topic dạng .../RuleEngine/CellMotionDetector/Motion hoặc
            // .../VideoSource/MotionAlarm), chấp nhận có thể bỏ sót vài hãng có Topic khác lạ.
            val hasMotionTopic = response.contains("Motion", ignoreCase = true)
            val hasTrueValue = Regex("Value\\s*=\\s*\"(?:true|1)\"", RegexOption.IGNORE_CASE)
                .containsMatchIn(response)
            hasMotionTopic && hasTrueValue
        }

    private suspend fun unsubscribeBestEffort(pullPointUrl: String, username: String?, password: String?) {
        // NonCancellable: hàm này thường được gọi trong nhánh dọn dẹp khi Job sắp bị hủy (đổi
        // cấu hình/deviceRole) — nếu không bọc, coroutine cancel giữa chừng sẽ khiến request
        // Unsubscribe không bao giờ thực sự được gửi đi, camera giữ subscription rác tới khi tự
        // hết hạn. Best-effort thuần túy — lỗi ở đây không quan trọng, không throw ra ngoài.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val body = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
                      <s:Body>
                        <Unsubscribe xmlns="http://docs.oasis-open.org/wsn/b-2"/>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()
                soapPost(pullPointUrl, body, username, password)
            } catch (e: Exception) {
                logger.d(TAG, "Unsubscribe ONVIF thất bại (bỏ qua, không quan trọng): ${e.message}")
            }
        }
    }

    private fun soapPost(url: String, soapBody: String, username: String?, password: String?): String? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .post(soapBody.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            if (!username.isNullOrBlank()) {
                builder.header("Authorization", Credentials.basic(username, password ?: ""))
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (text.isNullOrBlank()) return null
                // SOAP Fault vẫn có thể mang thông tin dùng được (một số camera trả Fault kèm
                // gợi ý auth) — nhưng để đơn giản và nhất quán với style probe hiện có, coi
                // response lỗi HTTP rõ ràng là thất bại, không cố khai thác thêm.
                if (!response.isSuccessful) return null
                text
            }
        } catch (e: Exception) {
            logger.d(TAG, "SOAP request tới $url lỗi: ${e.message}")
            null
        }
    }

    /**
     * Relay sự kiện qua Gateway — POST /camera/onvif_event, gateway forward vào ĐÚNG kênh
     * camera_alarm đã có sẵn (xem KDoc đầu file, mục "Cách vá").
     */
    private suspend fun relayMotionEvent(cameraId: String) = withContext(Dispatchers.IO) {
        try {
            val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
            val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
            if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                logger.w(TAG, "⚠️ Thiếu cấu hình Gateway, không relay được sự kiện ONVIF của $cameraId.")
                return@withContext
            }

            // ⚠️ CẦN THÊM: hằng số CAMERA_ALARM_NOTIFY_DEVICE_CODE trong AppConfigDefaults.kt
            // (đúng mẫu HOME_CAMERA_NODE_DEVICE_CODE đã có cho Tuya) — deviceCode của máy CẦN
            // ĐƯỢC BÁO khi có chuyển động (thường là máy mang theo người, có thể KHÁC với chính
            // Camera Node này nếu bạn dùng 2 thiết bị vật lý riêng biệt). Để trống thì mặc định
            // relay về CHÍNH thiết bị này (getOrCreateMyDeviceCode()) — đúng cho trường hợp máy
            // quản lý camera cũng chính là Camera Node.
            val configuredTarget = configProvider.getString(
                AppConfigDefaults.CAMERA_ALARM_NOTIFY_DEVICE_CODE
            ).trim()
            val targetDeviceCode = configuredTarget.ifBlank { callSkill.getOrCreateMyDeviceCode() }

            val bodyJson = JSONObject().apply {
                put("token", gatewayToken)
                put("deviceCode", targetDeviceCode)
                put("cameraId", cameraId)
            }
            val request = Request.Builder()
                .url("$gatewayUrl/camera/onvif_event")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.w(TAG, "⚠️ Relay sự kiện ONVIF thất bại, HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "❌ Lỗi relay sự kiện ONVIF của $cameraId: ${e.message}")
        }
    }
}