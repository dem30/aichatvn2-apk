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
    // ✅ MỚI: client HTTP dùng chung cho MỌI camera trên LAN (xem CameraLanHttpClient.kt) — thay
    // cho việc tự dựng OkHttpClient.Builder() riêng như trước.
    @com.aichatvn.agent.di.CameraLanHttpClient private val cameraLanHttpClient: OkHttpClient,
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

        // ✅ MỚI (debug case thật: log cho thấy các lần Pull cách nhau ~40-65ms thay vì gần
        // PULL_TIMEOUT_SECONDS=20s như kỳ vọng). PullMessages ĐÚNG CHUẨN ONVIF phải tự block phía
        // camera tới khi có message hoặc hết Timeout — nhưng không phải camera nào cũng tuân thủ
        // đúng (camera này trả lời gần như tức thì khi không có gì để báo). Vòng while ở
        // runCameraLoop() trước đây không có delay() nào, đặt cược hoàn toàn vào việc bản thân
        // PullMessages tự chặn đủ lâu — với camera không tuân thủ, vòng lặp biến thành busy-loop
        // thật sự, dồn dập gửi request tới mức đạt RESUBSCRIBE_AFTER_N_PULLS chỉ trong hơn 1
        // giây, kéo theo subscribe() lại liên tục dồn dập → nhiều camera ONVIF giá rẻ giới hạn
        // tốc độ tạo subscription sẽ trả 400 khi bị dồn kiểu này (đúng log WARNING đã thấy). Ép
        // sàn thời gian tối thiểu giữa 2 lần Pull ở CHÍNH PHÍA APP — không phụ thuộc camera có tự
        // block đủ lâu hay không.
        private const val MIN_PULL_INTERVAL_MS = 1_000L

        // ✅ MỚI: số lần PullMessages thất bại liên tiếp tối đa trước khi bỏ cuộc sớm, resubscribe
        // ngay — xem giải thích đầy đủ ở chỗ dùng (runCameraLoop). 3 lần (~3 giây ở tốc độ hiện
        // tại) đủ để loại trừ 1 lần thất bại đơn lẻ do mạng chập chờn tạm thời (không muốn
        // resubscribe quá nhạy, tốn thêm 1 vòng CreatePullPointSubscription không cần thiết cho
        // 1 lần lỗi thoáng qua), nhưng đủ ngắn để không "mù" quá lâu nếu subscription đã chết hẳn.
        private const val MAX_CONSECUTIVE_PULL_FAILURES = 3

        private const val ERROR_RETRY_DELAY_MS = 10_000L
    }

    // ✅ SỬA (thiết kế lại theo đúng chuẩn — xem KDoc đầy đủ ở CameraLanHttpClient.kt): không còn
    // tự dựng OkHttpClient riêng ở đây nữa. [cameraLanHttpClient] tiêm qua Hilt là 1 client DÙNG
    // CHUNG cho MỌI lời gọi HTTP/SOAP tới camera trên LAN trong toàn app (đã tắt connection pool
    // ở cấp Provider — lý do đầy đủ xem CameraLanHttpClient.kt), không riêng gì ONVIF hay 1 con
    // camera cụ thể nào từng gặp lỗi "unexpected end of stream". Chỉ derive readTimeout dài hơn
    // ở đây (qua newBuilder(), vẫn CHIA SẺ ConnectionPool/Dispatcher với client gốc) vì
    // PullMessages là long-poll, cần chờ lâu hơn timeout mặc định của client dùng chung.
    private val httpClient: OkHttpClient = cameraLanHttpClient
        .newBuilder()
        .readTimeout((PULL_TIMEOUT_SECONDS + 10).toLong(), TimeUnit.SECONDS)
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
                // ✅ MỚI: đếm số lần PullMessages thất bại LIÊN TIẾP (pullOnce trả null) — case
                // thật "ResourceUnknownFault" giữa chừng cho thấy đợi đủ RESUBSCRIBE_AFTER_N_PULLS
                // (~25 lần, ~25s ở tốc độ hiện tại) mới chịu resubscribe là quá lâu khi subscription
                // đã chết hẳn: mọi lần Pull còn lại trong chu kỳ chắc chắn thất bại y hệt, "mù"
                // không nhận được sự kiện thật nào trong lúc đó. Bỏ cuộc sớm sau vài lần thất bại
                // liên tiếp, resubscribe ngay — không cần biết chính xác lý do thất bại (mạng chập
                // chờn, subscription hết hạn, hay lỗi khác), vì hướng xử lý đều giống nhau: subscribe
                // lại từ đầu là an toàn nhất.
                var consecutiveFailures = 0
                while (coroutineContext.isActive && pullCount < RESUBSCRIBE_AFTER_N_PULLS) {
                    // ✅ MỚI: đo thời gian THỰC TẾ của lần Pull này — nếu camera trả lời nhanh hơn
                    // nhiều so với PULL_TIMEOUT_SECONDS (không tuân thủ long-poll đúng chuẩn), bù
                    // thêm delay() cho đủ MIN_PULL_INTERVAL_MS trước khi Pull lần kế — không đổi
                    // gì nếu camera đã tự block đủ lâu (elapsed >= sàn thì delay(0), bỏ qua).
                    val startedAt = System.currentTimeMillis()
                    val motionDetected = pullOnce(pullPointUrl, username, password)
                    if (motionDetected == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_PULL_FAILURES) {
                            logger.w(
                                TAG,
                                "⚠️ PullMessages thất bại $consecutiveFailures lần liên tiếp cho $cameraId — " +
                                    "bỏ cuộc sớm, resubscribe ngay thay vì đợi hết chu kỳ."
                            )
                            break
                        }
                    } else {
                        consecutiveFailures = 0
                        if (motionDetected) {
                            logger.i(TAG, "🚨 ONVIF phát hiện chuyển động: camera $cameraId")
                            relayMotionEvent(cameraId)
                        }
                    }
                    pullCount++
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed < MIN_PULL_INTERVAL_MS) {
                        delay(MIN_PULL_INTERVAL_MS - elapsed)
                    }
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

    /**
     * ✅ MỚI (sửa theo đúng chuẩn ONVIF, không vá riêng theo camera này — phân tích SOAP Fault
     * xác nhận: request thiếu WS-Addressing Header là nguyên nhân, ONVIF spec + trace chính thức
     * đều yêu cầu wsa:Action + wsa:To trên MỌI request tới EventPortType/PullPointSubscription,
     * không riêng gì CreatePullPointSubscription). Trước đây toàn bộ SOAP Body trong file này chỉ
     * có <s:Body>, KHÔNG có <s:Header> nào — nhiều camera (đặc biệt OEM giá rẻ, ONVIF Events
     * implementation không đầy đủ như case /event_service/xx đang gặp) từ chối thẳng bằng 400 nếu
     * thiếu 2 field bắt buộc này. Thêm cả wsa:MessageID (khuyến nghị đi kèm theo WS-Addressing,
     * một số implementation strict đòi luôn cả 3) — UUID ngẫu nhiên mỗi request, không cần theo
     * dõi/đối chiếu lại nên không cần lưu trạng thái gì thêm.
     */
    private fun wsAddressingHeader(action: String, to: String): String =
        """
          <s:Header>
            <wsa:Action s:mustUnderstand="1">$action</wsa:Action>
            <wsa:To s:mustUnderstand="1">$to</wsa:To>
            <wsa:MessageID>urn:uuid:${java.util.UUID.randomUUID()}</wsa:MessageID>
          </s:Header>
        """.trimIndent()

    /** Trả về địa chỉ PullPoint (SubscriptionReference/Address) nếu subscribe thành công. */
    private suspend fun subscribe(eventsUrl: String, username: String?, password: String?): String? =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:wsa="http://www.w3.org/2005/08/addressing"
                            xmlns:tev="http://www.onvif.org/ver10/events/wsdl">
                  ${wsAddressingHeader(
                      action = "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest",
                      to = eventsUrl
                  )}
                  <s:Body>
                    <!-- ✅ SỬA (debug case thật: SOAP Fault "wsrf-rw:ResourceUnknownFault" ngay
                    giữa chu kỳ Pull, đúng lúc có chuyển động vật lý) — trước đây không khai báo
                    InitialTerminationTime, để camera tự chọn mặc định. Nhiều camera ONVIF giá rẻ
                    đặt mặc định RẤT NGẮN (ngắn hơn cả 1 chu kỳ RESUBSCRIBE_AFTER_N_PULLS ~25s ở
                    tốc độ hiện tại), khiến subscription chết yểu giữa chừng trước khi app kịp chủ
                    động resubscribe theo lịch — mọi PullMessages sau đó bị từ chối vì subscription
                    không còn tồn tại. Xin hẳn PT5M (5 phút, dư dả nhiều lần so với 1 chu kỳ thật)
                    — không có gì đảm bảo camera CHẤP NHẬN đúng giá trị này (một số hãng vẫn tự áp
                    trần thấp hơn), nhưng đây là cách chuẩn ONVIF để XIN thời hạn dài, thay vì im
                    lặng phó mặc hoàn toàn cho mặc định của camera như trước. -->
                    <tev:CreatePullPointSubscription>
                      <tev:InitialTerminationTime>PT5M</tev:InitialTerminationTime>
                    </tev:CreatePullPointSubscription>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            logger.d(TAG, "📤 ONVIF Subscribe → $eventsUrl")
            val response = soapPost(eventsUrl, body, username, password) ?: return@withContext null
            // Namespace prefix của thẻ Address thay đổi tuỳ hãng (wsa:Address, đôi khi không có
            // prefix) — regex lỏng, đủ dùng cho probe/subscribe, không kéo XML parser đầy đủ,
            // cùng tinh thần với CameraCapabilityProber.tryProbeOnvif().
            Regex("<(?:\\w+:)?Address>(.*?)</(?:\\w+:)?Address>", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.groupValues?.get(1)?.trim()
                ?: eventsUrl // Một số camera dùng chung URL Events cho cả PullMessages.
        }

    /**
     * true = phát hiện chuyển động, false = PullMessages thành công nhưng không match, null =
     * PullMessages thất bại (lỗi HTTP/mạng/subscription chết — xem giải thích tách biệt các
     * trạng thái ở comment "✅ SỬA: Boolean -> Boolean?" bên dưới).
     *
     * ✅ SỬA (debug case thật: subscribe thành công — thấy log "🔌 Bắt đầu relay" — nhưng sau đó
     * HOÀN TOÀN im lặng dù camera báo động vật lý thật, không có bất kỳ log nào khác xuất hiện).
     * Nguyên nhân: soapPost() trả null (lỗi HTTP/timeout/exception khi gọi PullMessages) khiến
     * pullOnce() return null NGAY LẬP TỨC — vòng while bên ngoài (runCameraLoop) trước đây cứ thế
     * lặp lại vô thời hạn, gọi lại pullOnce(), lại thất bại y hệt, KHÔNG log gì cả — im lặng tuyệt
     * đối dù có lỗi thật đang xảy ra liên tục. Thêm log ở đây để phân biệt 3 trạng thái: (1)
     * PullMessages lỗi hẳn (null), (2) PullMessages OK nhưng không có Motion match, (3) Motion
     * match thành công — trước đây chỉ trạng thái (3) có log.
     */
    // ✅ SỬA: Boolean -> Boolean? — trước đây "thất bại (không có response)" và "thành công
    // nhưng không match" đều gộp chung thành `false`, khiến vòng ngoài (runCameraLoop) không thể
    // phân biệt để phản ứng khác nhau. Giờ: null = thất bại (lỗi HTTP/mạng/subscription chết —
    // vòng ngoài cần biết để bỏ cuộc SỚM, không đợi hết nguyên RESUBSCRIBE_AFTER_N_PULLS lần thất
    // bại mới chịu resubscribe, xem case thật "ResourceUnknownFault" giữa chừng), false = thành
    // công nhưng không có chuyển động (bình thường), true = có chuyển động.
    private suspend fun pullOnce(pullPointUrl: String, username: String?, password: String?): Boolean? =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:wsa="http://www.w3.org/2005/08/addressing"
                            xmlns:tev="http://www.onvif.org/ver10/events/wsdl">
                  ${wsAddressingHeader(
                      action = "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest",
                      to = pullPointUrl
                  )}
                  <s:Body>
                    <tev:PullMessages>
                      <tev:Timeout>PT${PULL_TIMEOUT_SECONDS}S</tev:Timeout>
                      <tev:MessageLimit>$PULL_MESSAGE_LIMIT</tev:MessageLimit>
                    </tev:PullMessages>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            logger.d(TAG, "📤 ONVIF PullMessages → $pullPointUrl")
            val response = soapPost(pullPointUrl, body, username, password)
            if (response == null) {
                logger.w(TAG, "⚠️ PullMessages($pullPointUrl) không có response (lỗi HTTP/timeout/exception — xem log SOAP request phía trên).")
                return@withContext null
            }

            // ⚠️ Heuristic có chủ ý (không parse XML đầy đủ, cùng tinh thần best-effort với
            // CameraCapabilityProber): coi là "có chuyển động" nếu response chứa 1 Topic nhắc
            // tới Motion VÀ 1 SimpleItem giá trị true đi kèm — đủ dùng cho đa số camera hỗ trợ
            // ONVIF Profile S/T (Topic dạng .../RuleEngine/CellMotionDetector/Motion hoặc
            // .../VideoSource/MotionAlarm), chấp nhận có thể bỏ sót vài hãng có Topic khác lạ.
            val hasMotionTopic = response.contains("Motion", ignoreCase = true)
            val hasTrueValue = Regex("Value\\s*=\\s*\"(?:true|1)\"", RegexOption.IGNORE_CASE)
                .containsMatchIn(response)
            val matched = hasMotionTopic && hasTrueValue

            // Log DEBUG mỗi vòng (kể cả không match) — để xác nhận vòng Pull THỰC SỰ đang chạy
            // đều đặn, không bị treo/chết âm thầm. Cắt bớt response nếu quá dài (PullMessages có
            // thể trả nhiều message cùng lúc, MessageLimit=10) để không tràn log 1 dòng quá dài.
            logger.d(
                TAG,
                "Pull($pullPointUrl): hasMotionTopic=$hasMotionTopic, hasTrueValue=$hasTrueValue, " +
                    "matched=$matched | response(${response.length} ký tự, cắt 500 đầu)=" +
                    response.take(500)
            )

            // ✅ MỚI (debug case thật: hasTrueValue=true nhưng hasMotionTopic=false — camera CÓ
            // báo 1 giá trị chuyển true, rất có thể đúng lúc chuyển động vật lý xảy ra, nhưng
            // dùng tên Topic không chứa chữ "Motion" nên bị heuristic từ chối oan). Ghi ở mức
            // WARNING (không bị lọc mất nếu người dùng tắt hiển thị DEBUG để đỡ spam — DEBUG thì
            // nổ mỗi giây, còn near-miss này chỉ nổ khi THẬT SỰ có giá trị đổi thành true, không
            // spam) kèm trích riêng (?:wsnt:)?Topic để biết đúng tên Topic thật của camera này,
            // không cần lục lại full response nữa — bổ sung tên đó vào heuristic ở lần sửa sau.
            if (hasTrueValue && !hasMotionTopic) {
                val topics = Regex("<(?:\\w+:)?Topic[^>]*>(.*?)</(?:\\w+:)?Topic>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(response)
                    .map { it.groupValues[1].trim() }
                    .distinct()
                    .joinToString(", ")
                logger.w(
                    TAG,
                    "🔎 Pull($pullPointUrl) có giá trị true nhưng KHÔNG match heuristic Motion — " +
                        "khả năng cao đây là 1 sự kiện thật bị bỏ sót vì tên Topic khác lạ. " +
                        "Topic thực tế trong response: ${topics.ifBlank { "(không trích được, xem full response ở log DEBUG)" }}"
                )
            }

            matched
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
                    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                                xmlns:wsa="http://www.w3.org/2005/08/addressing">
                      ${wsAddressingHeader(
                          action = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest",
                          to = pullPointUrl
                      )}
                      <s:Body>
                        <Unsubscribe xmlns="http://docs.oasis-open.org/wsn/b-2"/>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()
                logger.d(TAG, "📤 ONVIF Unsubscribe → $pullPointUrl")
                soapPost(pullPointUrl, body, username, password)
            } catch (e: Exception) {
                logger.d(TAG, "Unsubscribe ONVIF thất bại (bỏ qua, không quan trọng): ${e.message}")
            }
        }
    }

    /**
     * ✅ SỬA (cùng nguyên nhân với pullOnce ở trên): trước đây 2 nhánh thất bại "im lặng" — body
     * rỗng và HTTP không thành công — chỉ trả null, KHÔNG log gì, khác nhánh catch (có log). Với
     * người debug từ Logcat, cả 2 tình huống trông giống hệt "không có gì xảy ra" dù thực ra
     * server đã trả lời (chỉ là lỗi/rỗng) — không phân biệt được với timeout/mất kết nối thật.
     */
    private suspend fun soapPost(url: String, soapBody: String, username: String?, password: String?): String? {
        // ⚠️ SỬA (thiết kế lại theo đúng chuẩn, không match theo 1 câu message cụ thể như trước —
        // vì app kết nối hàng trăm loại camera khác nhau, mỗi firmware/JVM có thể ném ra một dạng
        // IOException transport khác nhau cho CÙNG 1 nguyên nhân gốc: EOFException,
        // SocketException("Connection reset"/"Broken pipe"), ProtocolException... — string-match
        // đúng 1 câu "unexpected end of stream" chỉ vá đúng con camera đã gặp, bỏ sót mọi firmware
        // khác báo lỗi transport tương tự bằng exception type/message khác).
        //
        // ⚠️ SỬA (case thật xác nhận qua log: 2 lần thử — gốc VÀ retry với connection HOÀN TOÀN
        // MỚI (đã tắt connection pool, không phải connection cũ tái sử dụng) — vẫn cùng dính
        // "unexpected end of stream" cách nhau CHƯA TỚI 100ms). Điều này loại hẳn giả thuyết
        // "connection cũ trong pool bị camera đóng" — nguyên nhân THẬT là phía CAMERA đang bận xử
        // lý việc khác (rất có thể đúng lúc phát hiện chuyển động thật — mã hoá video, ghi thẻ,
        // đẩy cảnh báo cloud riêng của hãng...) nên bỏ dở response giữa chừng, không phân biệt
        // được connection mới hay cũ. Retry NGAY LẬP TỨC (0ms delay như trước) vẫn rơi đúng vào
        // cùng cửa sổ bận đó nếu cửa sổ bận kéo dài hơn vài chục ms — vô ích.
        //
        // Tăng lên 3 lần thử, có khoảng nghỉ TĂNG DẦN (300ms rồi 900ms) giữa các lần — đủ để vượt
        // qua 1 khoảng bận ngắn của camera (thường chỉ kéo dài vài trăm ms tới ~1-2s cho 1 sự kiện
        // chuyển động), mà không làm subscribe() chậm đi đáng kể trong trường hợp phổ biến (lần
        // đầu đã thành công thì không delay gì cả).
        val retryDelaysMs = longArrayOf(300L, 900L)
        var lastFailureDetail: String? = null
        repeat(retryDelaysMs.size + 1) { attempt ->
            val result = soapPostOnce(url, soapBody, username, password)
            if (result.outcome != SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR) return result.text
            lastFailureDetail = result.text
            if (attempt < retryDelaysMs.size) {
                val waitMs = retryDelaysMs[attempt]
                logger.d(
                    TAG,
                    "SOAP $url: lỗi transport (${result.text}) — có thể do camera đang bận xử lý " +
                        "việc khác (vd đúng lúc có chuyển động thật), thử lại sau ${waitMs}ms " +
                        "(lần ${attempt + 2}/${retryDelaysMs.size + 1})."
                )
                delay(waitMs)
            }
        }
        logger.w(
            TAG,
            "SOAP $url: vẫn lỗi transport sau ${retryDelaysMs.size + 1} lần thử (lỗi cuối: " +
                "$lastFailureDetail) — camera có thể đang bận kéo dài hơn dự kiến, bỏ cuộc lần này."
        )
        return null
    }

    private enum class SoapPostOutcome { OK, FAIL, RETRYABLE_TRANSPORT_ERROR }
    private data class SoapPostResult(val outcome: SoapPostOutcome, val text: String?)

    private fun soapPostOnce(url: String, soapBody: String, username: String?, password: String?): SoapPostResult {
        return try {
            val builder = Request.Builder()
                .url(url)
                .post(soapBody.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            if (!username.isNullOrBlank()) {
                builder.header("Authorization", Credentials.basic(username, password ?: ""))
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    // ✅ SỬA (góp ý xác đáng: regex trích Fault Code/Reason trước đây quá lỏng —
                    // ONVIF payload có rất nhiều thẻ <Value>/SimpleItem khác, dễ bắt nhầm node
                    // không phải Fault, khiến log sai lệch còn nguy hiểm hơn không log gì. Bỏ đoán
                    // mò bằng regex, log THẲNG phần thân — tăng ngưỡng lên 5000 ký tự (đủ chứa
                    // trọn <s:Fault>...<s:Reason>...<s:Text> thật trong hầu hết trường hợp, so với
                    // 300 cũ chỉ đủ hết phần khai báo xmlns) để tự đọc được nguyên văn lý do camera
                    // từ chối, thay vì suy luận qua 1-2 từ khoá trích được.
                    logger.w(TAG, "SOAP $url trả HTTP ${response.code}: ${text?.take(5000) ?: "(rỗng)"}")
                    return SoapPostResult(SoapPostOutcome.FAIL, null)
                }
                if (text.isNullOrBlank()) {
                    logger.w(TAG, "SOAP $url trả HTTP ${response.code} thành công nhưng body rỗng.")
                    return SoapPostResult(SoapPostOutcome.FAIL, null)
                }
                SoapPostResult(SoapPostOutcome.OK, text)
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Camera không phản hồi kịp — lỗi mạng/thiết bị thật, KHÔNG phải connection cũ bị tái
            // sử dụng (đó là IOException khác, xem nhánh dưới) — retry ngay vô ích.
            logger.w(TAG, "SOAP request tới $url timeout: ${e.message}")
            SoapPostResult(SoapPostOutcome.FAIL, null)
        } catch (e: java.io.IOException) {
            // Mọi IOException transport khác (EOFException, SocketException reset/broken pipe,
            // ProtocolException...) coi là RETRYABLE — dấu hiệu chung của 1 connection vừa bị phía
            // camera đóng, không phân biệt theo message cụ thể (xem giải thích ở soapPost()).
            val cause = e.cause
            val detail = buildString {
                append(e.javaClass.name)
                append(": ")
                append(e.message ?: "(không có message)")

                if (cause != null) {
                    append(" | cause=")
                    append(cause.javaClass.name)
                    append(": ")
                    append(cause.message ?: "(không có message)")
                }
            }

            logger.w(TAG, "❌ SOAP request tới $url lỗi transport: $detail")
            SoapPostResult(SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR, detail)
        } catch (e: Exception) {
            logger.w(TAG, "SOAP request tới $url lỗi (${e.javaClass.simpleName}): ${e.message}")
            SoapPostResult(SoapPostOutcome.FAIL, null)
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