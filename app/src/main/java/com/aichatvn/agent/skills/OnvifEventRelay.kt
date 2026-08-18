package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.corouti1nes.NonCancellable
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
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
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
    // ✅ MỚI: máy chạy vòng lặp này CHẮC CHẮN đang cùng LAN với camera (điều kiện
    // isEligibleForOnvifRelay() ở dưới), nên tận dụng luôn để tự fetch 1 tấm ảnh LAN thật
    // ngay lúc phát hiện chuyển động, gửi kèm báo động cho client ở xa (không tự lặp lại logic
    // fetch/rẽ nhánh RTSP — gọi thẳng CameraSkill.fetchOneSnapshot() dùng chung MỘT chỗ duy
    // nhất, xem ghi chú "Public hoá" tại khai báo hàm đó).
    private val cameraSkill: CameraSkill,
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

        // ✅ MỚI: log "Pull(...)" ở pullOnce() vốn bắn mỗi vòng để xác nhận loop còn sống — với
        // camera không tuân thủ long-poll (trả lời gần tức thì, vòng chạy ~1s/lần do
        // MIN_PULL_INTERVAL_MS), tần suất mọi lần khiến System Logs bị spam. Giãn ra: chỉ log 1
        // lần mỗi 10 lượt Pull (~10s ở tốc độ hiện tại) — đủ để xác nhận loop không treo mà không
        // tràn log. Log lỗi/cảnh báo/near-miss/motion match KHÔNG bị ảnh hưởng, vẫn log mọi lần.
        private const val LOG_EVERY_N_PULLS = 10

        // ✅ MỚI: Topic ONVIF biết rõ là nhiễu định kỳ/trạng thái nội bộ máy — KHÔNG đáng trigger
        // chụp ảnh dù giá trị true (khác các Topic "sự kiện thật" như Motion, ImageTooDark,
        // ImageTooBlurry, Tamper... — những cái này giờ ĐỀU trigger vì hasTrueValue là đủ, xem
        // pullOnce()). Chỉ thêm vào đây khi ĐÃ XÁC NHẬN qua log thật là Topic đó gây chụp ảnh vô
        // ích liên tục (ví dụ heartbeat/keep-alive tự thân camera phát ra đều đặn bất kể có sự
        // kiện gì hay không) — không suy đoán trước, để tránh lại rơi vào lỗi cũ (bỏ sót sự kiện
        // thật vì đoán nhầm tên Topic).
        private val NOISE_TOPICS = setOf(
            "tns1:Monitoring/ProcessorUsage",
            "tns1:Monitoring/OperatingTime",
            "tns1:Device/HardwareFailure"
        )
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

    // ✅ MỚI (theo đúng ONVIF Core Spec, không vá riêng theo hãng): key "$cameraId|$topic" ->
    // giá trị true/false CUỐI CÙNG đã thấy cho topic đó. Dùng làm fallback rising-edge khi
    // NotificationMessage không mang PropertyOperation (thuộc tính BẮT BUỘC theo spec nhưng
    // một số OEM giá rẻ bỏ qua hoặc gắn sai) — chỉ coi là match khi giá trị vừa chuyển từ
    // false -> true so với lần Pull trước, thay vì cứ true là bắn liên tục mỗi lần camera
    // resend (một số hãng resend định kỳ dù chẳng có gì đổi, đặc biệt khi thiếu
    // PropertyOperation="Initialized" để tự phân biệt). Sống theo vòng đời Job (resubscribe
    // không xoá map — rising-edge vẫn đúng nghĩa xuyên suốt các subscription liên tiếp của
    // cùng 1 camera; camera bị xoá/Job bị huỷ hẳn thì entry cũ trở thành rác vô hại, kích
    // thước tối đa bị chặn bởi số camera thật x số topic thật, không tăng vô hạn).
    private val lastKnownTopicValue = ConcurrentHashMap<String, Boolean>()

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
                    // im lặng — chỉ giữ log báo phát hiện chuyển động
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
            return false
        }

        val cameraHost = runCatching { java.net.URI(eventsUrl).host }.getOrNull()
        if (cameraHost.isNullOrBlank()) {
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
            return false
        }
        return true
    }

    private fun fingerprintOf(camera: CameraConfigEntity): String =
        "${camera.onvifEventUrl}|${camera.snapshotUsername}|${camera.snapshotPassword}"

    private fun cancelJob(cameraId: String, reason: String) {
        activeJobs.remove(cameraId)?.cancel()
        activeJobFingerprints.remove(cameraId)
    }

    // ===== Vòng lặp subscribe + pull cho 1 camera =====

    private suspend fun runCameraLoop(camera: CameraConfigEntity) {
        val cameraId = camera.id.trim()
        val eventsUrl = camera.onvifEventUrl?.trim().orEmpty()
        val username = camera.snapshotUsername
        val password = camera.snapshotPassword

        while (coroutineContext.isActive) {
            var pullPointUrl: String? = null
            try {
                pullPointUrl = subscribe(eventsUrl, username, password)
                if (pullPointUrl == null) {
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
                    val motionDetected = pullOnce(cameraId, pullPointUrl, username, password, pullIndex = pullCount)
                    if (motionDetected == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_PULL_FAILURES) {
                            break
                        }
                    } else {
                        consecutiveFailures = 0
                        if (motionDetected) {
                            logger.i(TAG, "🚨 ONVIF phát hiện chuyển động: camera $cameraId")
                            relayMotionEvent(camera)
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

    /**
     * Trả về địa chỉ PullPoint (SubscriptionReference/Address) nếu subscribe thành công.
     *
     * ⚠️ CẢNH BÁO REGRESSION (đã xảy ra ít nhất 2 lần thật, tháng 08/2026): đoạn giải thích cho
     * InitialTerminationTime=PT5M BẮT BUỘC phải nằm ở KDoc bên ngoài `val body = """..."""`,
     * KHÔNG được để dạng comment XML `<!-- -->` lồng vào BÊN TRONG chuỗi triple-quote body — vì
     * nếu lồng vào trong, toàn bộ đoạn text tiếng Việt dài (có dấu, emoji) bị gửi NGUYÊN VĂN lên
     * camera như một phần thật của SOAP Body, khiến Content-Length phình to bất thường (2078-2080
     * byte thay vì 742 byte đúng chuẩn) và camera hsoap/2.8 đóng socket giữa chừng — gây đúng
     * `EOFException: unexpected end of stream`, đã xác nhận qua HttpLoggingInterceptor + đối
     * chiếu curl -v thủ công. Nếu bạn đang đọc dòng này SAU KHI copy/paste lại comment vào trong
     * body — dừng lại, đó chính là bug đã tái diễn.
     */
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
                    <tev:CreatePullPointSubscription>
                      <tev:InitialTerminationTime>PT5M</tev:InitialTerminationTime>
                    </tev:CreatePullPointSubscription>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val response = soapPost(
                eventsUrl, body, username, password,
                action = "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest"
            ) ?: return@withContext null
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
    // ✅ SỬA (case thật: log DEBUG "Pull(...)" bắn mỗi ~1 giây/lần — do MIN_PULL_INTERVAL_MS +
    // camera không tuân thủ long-poll — làm System Logs bị spam, khó đọc log khác lẫn vào giữa).
    // Giữ nguyên MỤC ĐÍCH log gốc (xác nhận vòng Pull không treo/chết âm thầm), chỉ giảm TẦN SUẤT:
    // log định kỳ mỗi LOG_EVERY_N_PULLS lần thay vì mọi lần. Log near-miss (hasTrueValue nhưng
    // không match Motion) và log lỗi/warning khác trong file này giữ nguyên tần suất — chỉ dòng
    // "vẫn đang chạy bình thường" lặp lại vô ích này mới cần giãn ra.
    private suspend fun pullOnce(
        cameraId: String,
        pullPointUrl: String,
        username: String?,
        password: String?,
        pullIndex: Int
    ): Boolean? =
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

            val response = soapPost(
                pullPointUrl, body, username, password,
                action = "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest"
            )
            if (response == null) {
                return@withContext null
            }

            val events = parseNotificationEvents(response)
            var matched = false

            for (event in events) {
                val trueItems = event.items.filter { isTrueValue(it.value) }
                val falseItems = event.items.filter { isFalseValue(it.value) }
                val stateKey = "$cameraId|${event.identityKey}"

                if (falseItems.isNotEmpty()) lastKnownTopicValue[stateKey] = false
                if (trueItems.isEmpty()) {
                    if (event.propertyOperation.equals("Deleted", ignoreCase = true)) {
                        lastKnownTopicValue[stateKey] = false
                    }
                    continue
                }

                val isMotion = looksLikeMotionEvent(event, trueItems)
                val isKnownNoise = NOISE_TOPICS.any {
                    normalizeTopic(event.topic).equals(normalizeTopic(it), ignoreCase = true)
                }
                if (!isMotion && isKnownNoise) {
                    logger.d(TAG, "ℹ️ ONVIF bỏ qua noise: topic=${event.topic}")
                    lastKnownTopicValue[stateKey] = true
                    continue
                }

                val previousTrue = lastKnownTopicValue[stateKey] == true
                lastKnownTopicValue[stateKey] = true
                val shouldTrigger = when {
                    event.propertyOperation.equals("Changed", ignoreCase = true) -> true
                    event.propertyOperation.equals("Deleted", ignoreCase = true) -> false
                    else -> !previousTrue
                }

                logger.d(
                    TAG,
                    "📥 ONVIF event: topic=${event.topic}, op=${event.propertyOperation ?: "none"}, " +
                        "motion=$isMotion, items=${event.items.joinToString { "${it.name}=${it.value}" }}"
                )

                if (isMotion && shouldTrigger) matched = true
            }
            matched
        }

    private data class OnvifItem(val name: String, val value: String)

    private data class OnvifNotificationEvent(
        val topic: String,
        val propertyOperation: String?,
        val sourceItems: List<OnvifItem>,
        val keyItems: List<OnvifItem>,
        val dataItems: List<OnvifItem>,
    ) {
        val items: List<OnvifItem> get() = sourceItems + keyItems + dataItems
        val identityKey: String
            get() = buildString {
                append(normalizeTopic(topic))
                for (item in sourceItems + keyItems) {
                    append('|').append(item.name.lowercase()).append('=').append(item.value)
                }
            }
    }

    /** Namespace-agnostic ONVIF parser: Topic + Message + Source/Key/Data + SimpleItem. */
    private fun parseNotificationEvents(response: String): List<OnvifNotificationEvent> = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(response)))
        val nodes = document.getElementsByTagNameNS("*", "NotificationMessage")
        buildList {
            for (i in 0 until nodes.length) {
                val notification = nodes.item(i) as? Element ?: continue
                val topic = firstDescendantText(notification, "Topic").orEmpty().ifBlank { "unknown-topic" }
                val messageContainer = firstDescendantElement(notification, "Message") ?: continue
                val message = if (messageContainer.hasAttribute("PropertyOperation")) messageContainer
                else firstDescendantElement(messageContainer, "Message") ?: messageContainer
                val op = message.getAttribute("PropertyOperation").trim().takeIf { it.isNotEmpty() }
                add(OnvifNotificationEvent(topic, op,
                    itemsInGroup(message, "Source"),
                    itemsInGroup(message, "Key"),
                    itemsInGroup(message, "Data")))
            }
        }
    } catch (e: Exception) {
        logger.w(TAG, "⚠️ Không parse được XML ONVIF: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    private fun itemsInGroup(message: Element, groupName: String): List<OnvifItem> {
        val group = firstDescendantElement(message, groupName) ?: return emptyList()
        val nodes = group.getElementsByTagNameNS("*", "SimpleItem")
        return buildList {
            for (i in 0 until nodes.length) {
                val item = nodes.item(i) as? Element ?: continue
                val name = item.getAttribute("Name").trim()
                val value = item.getAttribute("Value").trim()
                if (name.isNotBlank()) add(OnvifItem(name, value))
            }
        }
    }

    private fun firstDescendantElement(parent: Element, localName: String): Element? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).asSequence()
            .mapNotNull { nodes.item(it) as? Element }.firstOrNull()
    }

    private fun firstDescendantText(parent: Element, localName: String): String? =
        firstDescendantElement(parent, localName)?.textContent?.trim()

    /** Generic motion recognition; does not require one vendor-specific Topic. */
    private fun looksLikeMotionEvent(event: OnvifNotificationEvent, trueItems: List<OnvifItem>): Boolean {
        val topic = normalizeTopic(event.topic).lowercase()
        val motionTopic = topic.contains("motion") ||
            topic.contains("cellmotion") ||
            topic.contains("motionalarm") ||
            topic.contains("motiondetector")

        val motionItem = trueItems.any {
            val n = normalizeToken(it.name)
            n == "ismotion" || n == "motion" || n == "motionalarm" ||
                n == "motiondetected" || n == "cellmotion" || n.contains("motion")
        }

        val cellMotionRule = topic.contains("cellmotiondetector")
        val genericAlarmState = trueItems.any {
            normalizeToken(it.name) in setOf("state", "active", "alarm")
        }
        return motionTopic || motionItem || (cellMotionRule && genericAlarmState)
    }

    private fun isTrueValue(value: String): Boolean =
        value.trim().equals("true", ignoreCase = true) || value.trim() == "1"

    private fun isFalseValue(value: String): Boolean =
        value.trim().equals("false", ignoreCase = true) || value.trim() == "0"

    private fun normalizeTopic(topic: String): String =
        topic.trim().replace("\\s+".toRegex(), "").removePrefix("{").removeSuffix("}")

    private fun normalizeToken(value: String): String =
        value.trim().lowercase().replace("[^a-z0-9]".toRegex(), "")

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
                soapPost(
                    pullPointUrl, body, username, password,
                    action = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest"
                )
            } catch (e: Exception) {
                // im lặng — chỉ giữ log báo phát hiện chuyển động
            }
        }
    }

    /**
     * ✅ SỬA (cùng nguyên nhân với pullOnce ở trên): trước đây 2 nhánh thất bại "im lặng" — body
     * rỗng và HTTP không thành công — chỉ trả null, KHÔNG log gì, khác nhánh catch (có log). Với
     * người debug từ Logcat, cả 2 tình huống trông giống hệt "không có gì xảy ra" dù thực ra
     * server đã trả lời (chỉ là lỗi/rỗng) — không phân biệt được với timeout/mất kết nối thật.
     */
    /**
     * SOAP compatibility strategy: SOAP 1.2 first, then SOAP 1.1 when the HTTP response
     * indicates a SOAP-version/content-type compatibility problem.
     *
     * `action` is required because SOAP 1.1 carries the action in the HTTP SOAPAction header;
     * SOAP 1.2 continues to use the WS-Addressing wsa:Action already present in the envelope.
     */
    private suspend fun soapPost(
        url: String,
        soapBody: String,
        username: String?,
        password: String?,
        action: String
    ): String? {
        val retryDelaysMs = longArrayOf(1_000L, 3_000L, 6_000L)

        suspend fun postWithVersion(
            version: SoapVersion,
            fallbackOnImmediateTransport: Boolean
        ): SoapPostResult {
            var lastFailure: SoapPostResult? = null

            repeat(retryDelaysMs.size + 1) { attempt ->
                val result = soapPostOnce(url, soapBody, username, password, action, version)

                if (result.outcome != SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR) {
                    return result
                }

                lastFailure = result

                // EOF / unexpected end of stream / connection reset / closed stream
                // xảy ra rất nhanh thường là camera từ chối SOAP version. Với SOAP 1.2,
                // fallback ngay để thử SOAP 1.1 thay vì lặp lại cùng request.
                if (fallbackOnImmediateTransport && result.isSoapVersionTransportFailure) {
                    return result
                }

                if (attempt < retryDelaysMs.size) {
                    val waitMs = retryDelaysMs[attempt]
                    delay(waitMs)
                }
            }

            return lastFailure ?: SoapPostResult(
                SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR,
                "unknown transport error"
            )
        }

        // SOAP 1.2 trước. Nếu camera đóng socket ngay (EOF/reset/closed stream),
        // coi đó là dấu hiệu không tương thích SOAP 1.2 và chuyển NGAY sang 1.1.
        // Không retry SOAP 1.2 thêm 3 lần trong trường hợp này.
        val soap12Result = postWithVersion(
            version = SoapVersion.SOAP_12,
            fallbackOnImmediateTransport = true
        )
        if (soap12Result.outcome == SoapPostOutcome.OK) return soap12Result.text

        // 400/415/500: phản hồi HTTP cho thấy khả năng không tương thích protocol.
        // EOF/reset/closed stream: camera có thể đã đóng socket vì không hiểu SOAP 1.2.
        if (!soap12Result.shouldFallbackToSoap11 &&
            !soap12Result.isSoapVersionTransportFailure) {
            return null
        }

        val soap11Result = postWithVersion(
            version = SoapVersion.SOAP_11,
            fallbackOnImmediateTransport = false
        )
        if (soap11Result.outcome == SoapPostOutcome.OK) {
            return soap11Result.text
        }
        return null
    }

    private enum class SoapVersion(val label: String) {
        SOAP_12("1.2"),
        SOAP_11("1.1")
    }

    private enum class SoapPostOutcome {
        OK,
        FAIL,
        RETRYABLE_TRANSPORT_ERROR
    }

    private data class SoapPostResult(
        val outcome: SoapPostOutcome,
        val text: String?,
        val httpCode: Int? = null
    ) {
        val shouldFallbackToSoap11: Boolean
            get() = outcome == SoapPostOutcome.FAIL &&
                httpCode != null &&
                httpCode in setOf(400, 415, 500)

        /**
         * Camera OEM thường đóng socket ngay khi không hiểu SOAP 1.2.
         * OkHttp biểu diễn trường hợp này bằng EOFException / unexpected EOF /
         * connection reset / closed stream. Đây là transport symptom nhưng trong
         * ngữ cảnh SOAP-version fallback nó phải được thử bằng SOAP 1.1 ngay.
         */
        val isSoapVersionTransportFailure: Boolean
            get() {
                if (outcome != SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR) return false
                val d = text.orEmpty().lowercase()
                return d.contains("eofexception") ||
                    d.contains("unexpected end of stream") ||
                    d.contains("connection reset") ||
                    d.contains("connection closed") ||
                    d.contains("stream was reset") ||
                    d.contains("stream closed") ||
                    d.contains("broken pipe")
            }
    }

    private fun toSoap11Envelope(soapBody: String): String =
        soapBody.replace(
            "http://www.w3.org/2003/05/soap-envelope",
            "http://schemas.xmlsoap.org/soap/envelope/"
        )

    private fun soapPostOnce(
        url: String,
        soapBody: String,
        username: String?,
        password: String?,
        action: String,
        version: SoapVersion
    ): SoapPostResult {
        return try {
            val requestBody = when (version) {
                SoapVersion.SOAP_12 -> soapBody
                SoapVersion.SOAP_11 -> toSoap11Envelope(soapBody)
            }
            val contentType = when (version) {
                SoapVersion.SOAP_12 -> "application/soap+xml; charset=utf-8"
                SoapVersion.SOAP_11 -> "text/xml; charset=utf-8"
            }
            val builder = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody(contentType.toMediaType()))
                // Camera hsoap/2.8 đã xác nhận ổn định với Connection: close.
                .header("Connection", "close")
                .header("Accept", "application/soap+xml, text/xml, */*")

            if (version == SoapVersion.SOAP_11) {
                builder.header("SOAPAction", "\"$action\"")
            }
            if (!username.isNullOrBlank()) {
                builder.header("Authorization", Credentials.basic(username, password ?: ""))
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    return SoapPostResult(SoapPostOutcome.FAIL, text, response.code)
                }
                if (text.isNullOrBlank()) {
                    return SoapPostResult(SoapPostOutcome.FAIL, null, response.code)
                }
                SoapPostResult(SoapPostOutcome.OK, text, response.code)
            }
        } catch (e: java.net.SocketTimeoutException) {
            SoapPostResult(SoapPostOutcome.FAIL, null)
        } catch (e: java.io.IOException) {
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
            SoapPostResult(SoapPostOutcome.RETRYABLE_TRANSPORT_ERROR, detail)
        } catch (e: Exception) {
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
     *
     * ✅ MỚI: gọi ĐÚNG MỘT chỗ CameraSkill.fetchOneSnapshot() để lấy ảnh LAN thật (máy này chắc
     * chắn cùng LAN với camera — điều kiện bắt buộc để vòng lặp ONVIF chạy tới đây, xem
     * isEligibleForOnvifRelay()) TRƯỚC khi publish — cho client ở xa nhận được ảnh thật thay vì
     * chỉ 1 tín hiệu "có báo động" suông. fetch lỗi (camera vừa mất kết nối LAN giữa lúc vừa
     * pull được sự kiện) KHÔNG chặn việc publish báo động — vẫn gửi, chỉ thiếu ảnh, client ở xa
     * fallback về hành vi cũ (tự scan cục bộ).
     */
    private suspend fun relayMotionEvent(camera: CameraConfigEntity) {
        val cameraId = camera.id.trim()
        val imageBytes = try {
            cameraSkill.fetchOneSnapshot(camera.snapshoturl, camera.snapshotUsername, camera.snapshotPassword)
        } catch (e: Exception) {
            null
        }

        // ✅ MỚI: máy này đang cùng LAN với camera (điều kiện bắt buộc để chạy tới đây, xem
        // isEligibleForOnvifRelay()) — tự quét luôn tại chỗ, không chờ vòng broadcast quay lại.
        // Server (/household/event) chủ động loại trừ chính máy phát khỏi danh sách nhận
        // (xem app.py: "if dc == origin_device_code: continue"), nên với hộ chỉ có 1 máy,
        // không có ai khác xử lý báo động nếu thiếu bước này.
        cameraSkill.scanCamera(
            cameraId = cameraId,
            isDailyReport = false,
            forceAi = false,
            preloadedImageBytes = imageBytes
        )

        householdEventPublisher.publishCameraAlarm(cameraId, imageBytes)
    }
}
