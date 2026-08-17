package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
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
 * ✅ SỬA (góp ý user, đã xác nhận đúng): trước đây gate bằng deviceRole == "camera_node" (vai trò
 * TĨNH gán tay 1 lần trong Settings) — sai bản chất, vì server (/household/event) và
 * HouseholdEventPublisher.kt đều KHÔNG phân biệt vai trò máy gửi (comment gốc app.py: "Publisher
 * có thể là BẤT KỲ máy nào... endpoint này không phân biệt vai trò máy gửi"). deviceRole vốn
 * được thiết kế cho mục đích KHÁC hẳn (startDeviceCommandSSE() — nhận LỆNH điều khiển thiết bị
 * khi Client ở ngoài LAN, xem AppConfigDefaults.DEVICE_ROLE) — OnvifEventRelay chỉ MƯỢN TẠM biến
 * này làm proxy cho điều kiện thực sự cần: "điện thoại có đang cùng LAN với CAMERA ĐÓ không".
 * Hệ quả sai thực tế: máy Client đang ở nhà, cùng Wi-Fi với camera, ONVIF hoàn toàn subscribe
 * được — nhưng bị khoá hoàn toàn chỉ vì chưa gán deviceRole = camera_node.
 *
 * Sửa: bỏ gate deviceRole, thay bằng kiểm tra ĐỘNG theo TỪNG CAMERA — dùng
 * NetworkContext.isOnSavedSubnet(host trích từ onvifEventUrl) để biết chính điện thoại này có
 * đang cùng subnet Wi-Fi với camera đó không, đúng bản chất kỹ thuật thực sự cần (không phải vai
 * trò gán tay). Máy nào cùng LAN với camera nào thì subscribe camera đó — không cần biết trước
 * ai là "camera_node".
 *
 * ⚠️ ĐÁNH ĐỔI CHẤP NHẬN ĐƯỢC (đã thảo luận với user, chọn phương án đơn giản): nếu NHIỀU máy
 * cùng ở nhà (cùng LAN) đồng thời mở app, TẤT CẢ đều sẽ subscribe cùng 1 camera song song — tốn
 * nhiều ONVIF subscription hơn cần thiết tới cùng 1 camera (một số camera giá rẻ giới hạn số
 * subscription đồng thời thấp). KHÔNG dùng cơ chế Election như track MQTT Embedded Broker
 * (MqttBrokerElectionManager) — chỉ 1 máy làm broker — vì phức tạp không cần thiết cho use case
 * này; server-side dedup (household_event(), lớp 2 flood-window) đã đủ chặn việc noti bị gửi
 * trùng, nhược điểm chỉ là hao phí subscription phía camera, không phải lỗi chức năng.
 *
 * ✅ CẬP NHẬT (Household Event Broadcast): relay giờ đi qua [HouseholdEventPublisher] —
 * POST /household/event chung với mọi loại household event khác (Tuya đổi trạng thái...) —
 * thay vì route riêng /camera/onvif_event + field CAMERA_ALARM_NOTIFY_DEVICE_CODE gõ tay 1
 * chiều như trước. Máy nào cần nhận báo động chỉ cần khai CÙNG [AppConfigDefaults.HOUSEHOLD_ID]
 * với Camera Node này — Gateway tự broadcast tới MỌI deviceCode cùng household (xem
 * register_device_code()/household_event() trong app.py), không giới hạn 1 deviceCode đích cố
 * định như route cũ.
 *
 * Server (Gateway) không cần hiểu gì về ONVIF — /household/event chỉ forward vào ĐÚNG kênh
 * household_event_queues/dedup dùng chung cho mọi loại event. Máy nhận ở đầu kia
 * (WebhookGatewayService.startHouseholdEventSSE() → handleIncomingHouseholdEvent(), nhánh
 * type="camera_alarm") xử lý y hệt cũ, gọi lại scan() bình thường — KHÔNG cần sửa gì thêm ở
 * phía nhận ngoài việc đọc "type".
 *
 * ⚠️ CHỐNG BẮN ĐÔI (double-trigger) — đúng yêu cầu khi thêm tính năng này, kiểm tra ở 3 lớp:
 *
 * 1) ĐIỀU KIỆN KÍCH HOẠT subscribe (lớp quan trọng nhất — chặn từ gốc): chỉ subscribe ONVIF cho
 *    camera nào đồng thời thoả CẢ:
 *      - onvifEnabled == 1        (người dùng đã bật Switch ONVIF)
 *      - onvifEventUrl không rỗng (đã probe ra Events service — xem CameraCapabilityProber)
 *      - enableAlarmPush == 0     (camera CHƯA dùng đường "Báo động" gốc qua push URL riêng)
 *      - networkContext.isOnSavedSubnet(host của camera) == true (chính máy này đang cùng LAN
 *        với camera đó — điều kiện MỚI thay cho deviceRole, xem giải thích ở trên)
 *    2 đường (push gốc vs ONVIF relay) cùng mục đích "nhắc quét sớm" — bật cả 2 cho cùng 1
 *    camera khiến scan() bị gọi 2 lần cho cùng 1 lần chuyển động thật. Nếu người dùng lỡ bật cả
 *    2, ONVIF nhường cho đường push gốc (rẻ hơn: camera tự đẩy, không tốn 1 subscription +
 *    coroutine sống 24/7 trên Camera Node).
 * 2) Mỗi cameraId chỉ giữ ĐÚNG 1 Job đang chạy TRÊN MỖI MÁY, quản lý qua [activeJobs] — trước khi
 *    tạo Job mới (cấu hình đổi, hoặc vòng reconcile định kỳ phát hiện camera vẫn hợp lệ) luôn hủy
 *    Job cũ trước, không bao giờ để 2 Job cùng chạy song song cho 1 camera TRÊN CÙNG 1 MÁY (nhiều
 *    máy khác nhau cùng subscribe 1 camera là đánh đổi đã chấp nhận ở trên, không phải bug).
 * 3) Server-side (lớp phòng vệ cuối): POST /household/event dùng dedup 2 lớp CHUNG cho mọi
 *    event (eventId chính xác + flood window 10s theo (household_id, type, source_device_id),
 *    chỉ áp dụng cho camera_alarm) — dù lỡ có bug khiến push gốc VÀ ONVIF relay cùng bắn cho 1
 *    camera trong 10s (hoặc nhiều máy cùng LAN cùng relay), gateway cũng chỉ forward 1 lần.
 */
@Singleton
class OnvifEventRelay @Inject constructor(
    private val database: AppDatabase,
    private val callSkill: CallSkill,
    // ✅ MỚI (Household Event Broadcast): nguồn DUY NHẤT gửi event ra Gateway giờ là
    // HouseholdEventPublisher — không còn tự build JSON POST /camera/onvif_event nữa (route
    // đó đã bị xoá ở app.py, thay bằng /household/event dùng chung cho mọi loại event).
    private val householdEventPublisher: HouseholdEventPublisher,
    // ✅ MỚI: thay cho gate deviceRole == "camera_node" — kiểm tra ĐỘNG chính máy này có đang
    // cùng LAN Wi-Fi với 1 camera cụ thể không, đúng bản chất kỹ thuật cần cho ONVIF subscribe.
    private val networkContext: com.aichatvn.agent.utils.NetworkContext,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "OnvifEventRelay"

        // Chu kỳ soát lại danh sách camera đủ điều kiện — đủ ngắn để phản ứng nhanh khi người
        // dùng vừa bật/tắt Switch ONVIF hay đổi enableAlarmPush, hoặc điện thoại vừa đổi mạng
        // Wi-Fi (rời/vào LAN nhà) — không cần tức thời như SSE.
        private const val RECONCILE_INTERVAL_MS = 20_000L

        // Timeout PullMessages phía server ONVIF — long-poll kiểu chờ tới khi có sự kiện hoặc
        // hết thời gian này. Không đặt quá dài để vòng lặp còn dịp kiểm tra lại điều kiện kích
        // hoạt (camera có thể vừa bị tắt Switch giữa chừng, hoặc máy vừa rời khỏi Wi-Fi nhà).
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
        // ✅ SỬA: bỏ gate deviceRole == "camera_node" — không còn quan tâm vai trò máy gán tay,
        // chỉ cần biết máy này CÓ đang cùng LAN với TỪNG camera cụ thể không (kiểm tra trong
        // isEligibleForOnvifRelay() dưới, theo IP thật của camera đó, không phải 1 cờ chung).
        val cameras = withContext(Dispatchers.IO) { database.cameraDao().getAllCameras() }
        val eligible = cameras.filter { isEligibleForOnvifRelay(it) }
        val eligibleIds = eligible.map { it.id.trim() }.toSet()

        // Hủy Job của camera không còn đủ điều kiện nữa (bị xoá, tắt Switch ONVIF, vừa bật
        // enableAlarmPush, hoặc máy vừa rời khỏi LAN của camera đó — xem isEligibleForOnvifRelay()).
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
     *
     * ✅ SỬA: thay điều kiện deviceRole == "camera_node" (vai trò gán tay, sai bản chất) bằng
     * kiểm tra ĐỘNG "chính máy này có đang cùng LAN Wi-Fi với camera CỤ THỂ này không" — trích IP
     * camera từ host của onvifEventUrl (URL Events service thật đã probe được, đảm bảo đúng host
     * cần subscribe tới, khác snapshoturl có thể trỏ qua field khác/URL dự phòng cloud).
     */
    private fun isEligibleForOnvifRelay(camera: CameraConfigEntity): Boolean {
        if (camera.onvifEnabled != 1) return false
        val eventsUrl = camera.onvifEventUrl
        if (eventsUrl.isNullOrBlank()) return false
        if (camera.enableAlarmPush == 1) {
            logger.d(
                TAG,
                "Camera ${camera.id.trim()} đang bật cả ONVIF lẫn Báo động gốc (push) — " +
                    "nhường cho đường push gốc để tránh scan() bị gọi 2 lần cho cùng 1 sự kiện. " +
                    "Tắt 1 trong 2 Switch nếu không muốn thấy log này."
            )
            return false
        }

        val cameraHost = runCatching { java.net.URI(eventsUrl).host }.getOrNull()
        if (cameraHost.isNullOrBlank()) {
            logger.w(TAG, "Camera ${camera.id.trim()}: không trích được host từ onvifEventUrl='$eventsUrl', bỏ qua relay.")
            return false
        }
        // ✅ SỬA (debug case thật: người dùng đứng đúng cạnh camera nhưng không thấy log gì cả —
        // trước đây nhánh này im lặng hoàn toàn khi trả false, không có cách nào tự chẩn đoán qua
        // Logcat). Log rõ subnet hiện tại của máy vs subnet suy ra từ camera — nguyên nhân phổ
        // biến nhất: getCurrentWifiSubnet() trả null vì thiếu quyền ACCESS_FINE_LOCATION (Android
        // 10+ yêu cầu quyền Location mới đọc được SSID/IP Wi-Fi thật, WifiManager.connectionInfo
        // trả rỗng/giả nếu thiếu quyền — không throw exception nên NetworkContext không tự biết
        // để báo lỗi, chỉ lặng lẽ trả null → isOnSavedSubnet() luôn false).
        if (!networkContext.isOnSavedSubnet(cameraHost)) {
            val currentSubnet = networkContext.getCurrentWifiSubnet()
            logger.d(
                TAG,
                "Camera ${camera.id.trim()} (host=$cameraHost) tạm không subscribe — " +
                    if (currentSubnet == null)
                        "không đọc được subnet Wi-Fi hiện tại của máy (null). Có thể do: (1) máy " +
                            "không kết nối Wi-Fi/đang dùng data di động, hoặc (2) app CHƯA được cấp " +
                            "quyền Vị trí (Location) — Android 10+ bắt buộc quyền này để đọc SSID/IP " +
                            "Wi-Fi thật. Kiểm tra Cài đặt hệ thống > Ứng dụng > AIChatVN2 > Quyền > Vị trí."
                    else
                        "subnet máy hiện tại '$currentSubnet' khác subnet camera (suy từ IP $cameraHost)."
            )
            return false
        }
        return true
    }

    private fun fingerprintOf(camera: CameraConfigEntity): String =
        "${camera.onvifEventUrl}|${camera.snapshotUsername}|${camera.snapshotPassword}"

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
     * Relay sự kiện qua Gateway — POST /household/event (type="camera_alarm") qua
     * HouseholdEventPublisher, gateway tự forward tới mọi deviceCode cùng household (xem
     * KDoc đầu file, mục "Cách vá"). Không còn phụ thuộc CAMERA_ALARM_NOTIFY_DEVICE_CODE
     * (đã xoá khỏi AppConfigDefaults) — máy nào cần nhận báo động chỉ cần khai cùng
     * [AppConfigDefaults.HOUSEHOLD_ID] với Camera Node này, không cần gõ tay deviceCode
     * đích một chiều như trước.
     */
    private suspend fun relayMotionEvent(cameraId: String) {
        val delivered = householdEventPublisher.publishCameraAlarm(cameraId)
        if (!delivered) {
            logger.w(TAG, "⚠️ Relay sự kiện ONVIF của $cameraId thất bại hoặc chưa cấu hình Household ID/Gateway.")
        }
    }
}