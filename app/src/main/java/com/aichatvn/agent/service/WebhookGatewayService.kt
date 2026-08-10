package com.aichatvn.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.dataStore                          
import com.aichatvn.agent.data.model.FacebookPageEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.skills.TuyaManager           
import com.aichatvn.agent.skills.TrainingSkill          
import com.aichatvn.agent.ui.dashboard.DeviceRegistry              
import com.aichatvn.agent.scheduler.CronParser 
import androidx.datastore.preferences.core.stringPreferencesKey    
import kotlinx.coroutines.flow.first                               
import kotlinx.coroutines.flow.debounce                            
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards
// ✅ THÊM IMPORT: Nhận biết interface Quản gia AI
import com.aichatvn.agent.skills.HouseManagerSkill
// ✅ THÊM IMPORT: Trình quản lý tín hiệu cuộc gọi P2P
import com.aichatvn.agent.skills.GatewaySignalingManager

@AndroidEntryPoint
class WebhookGatewayService : Service() {

    // ✅ MỚI: sửa race condition — startTelegramLongPolling() trước đây bọc mỗi tin nhắn trong
    // serviceScope.launch { } RIÊNG BIỆT, không await, nên 2 tin nhắn liên tiếp của CÙNG 1
    // username (vd "Quét camera" rồi "Hủy" gõ nhanh, rơi vào cùng 1 lần getUpdates()) có thể
    // chạy processQuery() SONG SONG, không đảm bảo thứ tự — khiến "Hủy" đôi khi được xử lý
    // TRƯỚC KHI pending intent của "Quét camera" kịp ghi xong vào ChatHistoryManager, nên
    // resolveCancel() không tìm thấy gì để hủy và rơi thẳng xuống chat AI. Dùng 1 Mutex riêng
    // theo từng username để ép đúng thứ tự xử lý tin nhắn của CÙNG 1 người, nhưng vẫn cho
    // nhiều username khác nhau chạy song song bình thường (không dùng chung 1 Mutex global).
    private val userProcessingMutex = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(username: String): Mutex =
        userProcessingMutex.computeIfAbsent(username) { Mutex() }

    // ✅ MỚI: khử trùng lặp sự kiện webhook theo NỘI DUNG (gateway không cấp message/event ID).
    // Chỉ chặn nếu đúng (platform, senderId, text, imageUrl) lặp lại trong vòng
    // WEBHOOK_DEDUP_WINDOW_MS — đủ ngắn để không chặn nhầm khách thật sự gõ lại y hệt sau đó.
    private val recentWebhookEvents = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val WEBHOOK_DEDUP_WINDOW_MS = 10_000L
    private fun isDuplicateWebhookEvent(dedupKey: String): Boolean {
        val now = System.currentTimeMillis()
        recentWebhookEvents.entries.removeIf { now - it.value > WEBHOOK_DEDUP_WINDOW_MS }
        var isDup = false
        recentWebhookEvents.compute(dedupKey) { _, lastSeen ->
            if (lastSeen != null && now - lastSeen <= WEBHOOK_DEDUP_WINDOW_MS) {
                isDup = true
                lastSeen
            } else {
                now
            }
        }
        return isDup
    }

    @Inject
    lateinit var agentKernel: AgentKernel

    @Inject
    lateinit var intentExecutor: com.aichatvn.agent.core.execution.IntentExecutor

    @Inject
    lateinit var chatSkill: ChatSkill

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var configProvider: AppConfigProvider

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>

    @Inject
    lateinit var tuyaManager: TuyaManager           

    @Inject
    lateinit var trainingSkill: TrainingSkill       

    @Inject
    lateinit var deviceRegistry: DeviceRegistry                   

    // ✅ ĐÃ SỬA LỖI 5: Tiêm Provider của Quản gia AI vào cổng dịch vụ
    @Inject
    lateinit var houseManagerProvider: javax.inject.Provider<HouseManagerSkill>

    // ✅ TIÊM THÊM: Trình quản lý tín hiệu cuộc gọi
    @Inject
    lateinit var signalingManager: GatewaySignalingManager

    // ✅ SỬA LỖI: CallSkill.getOrCreateMyDeviceCode() (dùng Mutex, an toàn khi gọi đồng
    // thời) giờ là NGUỒN DUY NHẤT sinh/đọc deviceCode. Trước đây registerDeviceCode() và
    // startCallSignalSSE() bên dưới MỖI HÀM ĐỀU tự đọc-rồi-tự-sinh deviceCode riêng, không
    // khóa với nhau — 2 coroutine chạy gần như đồng thời lúc service khởi động có thể cùng
    // thấy config rỗng, cùng tự sinh 2 mã NGẪU NHIÊN KHÁC NHAU, rồi cái ghi sau "thắng" —
    // trong khi cái ghi trước đã dùng đúng mã của NÓ để mở kết nối SSE/đăng ký. Kết quả:
    // máy nghe mở kênh signaling ở 1 mã, nhưng mã hiển thị/dùng để người khác gọi tới lại
    // là mã khác — không bao giờ khớp nhau.
    @Inject
    lateinit var callSkill: com.aichatvn.agent.skills.CallSkill

    // ✅ MỚI: nhận kết quả thực thi thật từ Camera Node (type="device_command_result" qua SSE
    // /stream/{token}) và chuyển tiếp cho DeviceCommandGatewayClient để hoàn tất Deferred đang
    // chờ — xem handleIncomingWebhookEvent() và resolvePendingResult().
    @Inject
    lateinit var deviceCommandGatewayClient: DeviceCommandGatewayClient

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText = ""

    // ✅ SỬA LỖI (root cause thật sự của "câm/mù, chỉ hết khi restart Render"):
    // okHttpClient.dispatcher.cancelAll() KHÔNG đủ mạnh để đóng chắc chắn 1 kết nối
    // SSE đang mở tới /call/stream/{deviceCode} — endpoint này dùng EventSourceResponse
    // với ping=15 (xem app.py phía server) để giữ kết nối sống bằng comment ping mỗi 15s.
    // Việc giữ kết nối bền bằng ping khiến cancelAll() không luôn ngắt được ngay socket
    // cấp thấp đang trong trạng thái "chờ dữ liệu tiếp theo" — khác với kênh
    // /stream/{token} (không dùng ping) vốn dễ tự timeout/đứt hơn nên vẫn tự reconnect
    // bình thường. Hệ quả quan sát được: sau khi đổi call.device_code, log luôn thấy
    // CloudGateway đứt/nối lại và đăng ký deviceCode MỚI, nhưng KHÔNG BAO GIỜ thấy dòng
    // "[CallSignalSSE] Đang thiết lập..." lặp lại — kênh nhận tín hiệu cuộc gọi
    // (offer/answer/ICE) vẫn treo với deviceCode CŨ vô thời hạn.
    //
    // Cách sửa chắc chắn: giữ thẳng tham chiếu tới Call đang chạy của MỖI kênh, và gọi
    // .cancel() trực tiếp lên đúng Call đó — thay vì cancelAll() gián tiếp qua dispatcher.
    // Call.cancel() đảm bảo đóng ngay socket bất kể trạng thái ping/giữ kết nối.
    @Volatile private var cloudGatewayCall: okhttp3.Call? = null
    @Volatile private var callSignalCall: okhttp3.Call? = null
    // ✅ MỚI: tham chiếu Call riêng cho kênh SSE tin nhắn Website theo widget_key
    // (/widget/inbox/{widget_key}/{token}) — cùng lý do với 2 tham chiếu ở trên: cần
    // .cancel() trực tiếp để đảm bảo đóng chắc chắn, không dựa vào cancelAll() gián tiếp.
    @Volatile private var websiteMessageCall: okhttp3.Call? = null
    // ✅ MỚI: tham chiếu Call riêng cho kênh SSE tin nhắn Facebook/Instagram theo
    // device_code (/page/inbox/{device_code}/{token}) — cùng lý do với 2 tham chiếu ở trên.
    @Volatile private var pageMessageCall: okhttp3.Call? = null
    // ✅ MỚI: tham chiếu Call riêng cho kênh SSE "camera báo động"
    // (/camera/alarm/stream/{device_code}) — cùng lý do với các tham chiếu ở trên.
    @Volatile private var cameraAlarmCall: okhttp3.Call? = null
    // ✅ MỚI: SSE tới /device/command/stream/{deviceCode} — kênh lệnh CHUNG cho mọi thiết bị
    // điều khiển từ xa (Tuya, và các loại khác sẽ tích hợp sau). Chỉ máy đóng vai trò "Camera
    // Node" (đặt cố định ở nhà) cần mở kênh này; máy "Client" (mang theo người, chỉ GỬI lệnh
    // qua POST /device/command) không cần mở SSE này — xem điều kiện bật trong onCreate().
    @Volatile private var deviceCommandCall: okhttp3.Call? = null

    // ✅ SỬA LỖI: gọi .cancel() trực tiếp lên từng Call reference thay vì
    // okHttpClient.dispatcher.cancelAll() — đảm bảo đóng chắc chắn CẢ HAI kênh SSE
    // (kể cả /call/stream/{deviceCode} vốn được giữ bền bằng ping=15 phía server),
    // để cả 2 luôn cùng resync deviceCode/token mới nhất, không bao giờ lệch pha.
    private fun forceResyncBothChannels() {
        cloudGatewayCall?.cancel()
        callSignalCall?.cancel()
        // ✅ MỚI: ngắt luôn kênh SSE tin nhắn Website riêng để nó cũng resync
        // widget_key/token mới nhất đồng thời với 2 kênh còn lại — tránh lệch pha
        // giống hệt lý do đã áp dụng cho callSignalCall.
        websiteMessageCall?.cancel()
        // ✅ MỚI: cùng lý do — ngắt luôn kênh SSE tin nhắn Facebook/Instagram riêng để
        // resync device_code/token mới nhất đồng thời với các kênh còn lại.
        pageMessageCall?.cancel()
        // ✅ MỚI: cùng lý do — kênh camera alarm cũng khóa theo device_code, ngắt luôn để
        // resync đồng thời, tránh kênh này treo với deviceCode cũ trong khi các kênh khác
        // đã chuyển sang deviceCode mới.
        cameraAlarmCall?.cancel()
        // ✅ MỚI: cùng lý do — kênh lệnh thiết bị cũng khóa theo device_code, ngắt luôn để
        // resync đồng thời với các kênh còn lại.
        deviceCommandCall?.cancel()
    }

    // ✅ SỬA LỖI: readTimeout cũ (45s) khiến 2 kênh SSE dài hạn (/stream/{token} và
    // /call/stream/{deviceCode}) bị OkHttp tự ngắt mỗi khi server "im lặng" quá 45s
    // (không có tin nhắn/tín hiệu mới) — dù kết nối vẫn hoàn toàn khỏe mạnh về mặt
    // TCP. Bản chất SSE là giữ 1 kết nối mở hàng giờ/hàng ngày, dữ liệu chỉ đến khi
    // có sự kiện, nên readTimeout hữu hạn là sai mô hình cho luồng này — nó gây ra
    // đúng chu kỳ log "Mất đường ống SSE -> Đang thiết lập lại" lặp liên tục mỗi ~45s.
    // Đặt về 0 (vô hạn) để chỉ dựa vào ping/heartbeat của server (EventSourceResponse
    // ping=15 ở /call/stream, offline_buffers ở /stream) và việc client tự cancel khi
    // cần resync (forceResyncBothChannels) để đóng kết nối, thay vì để timeout đọc
    // ngắt ngang.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ✅ SỬA LỖI (root cause "code mới không có tác dụng cho tới khi restart Render"):
    // apiClient TRƯỚC ĐÂY được tạo bằng okHttpClient.newBuilder(), nghĩa là nó DÙNG
    // CHUNG Dispatcher (và connection pool) với okHttpClient — dispatcher đó chính là
    // cái bị okHttpClient.dispatcher.cancelAll() gọi mỗi khi configUpdatedEvent bắn
    // (xem onCreate() bên dưới). Hệ quả: registerDeviceCode()/registerWidgetKey()/
    // registerPageMappings() — vốn gọi qua apiClient trong 1 coroutine riêng ngay khi
    // SSE reconnect — có thể bị cancelAll() của LẦN GỌI TIẾP THEO hủy giữa chừng nếu
    // configUpdatedEvent bắn liên tiếp nhanh (rất hay gặp khi Import backup, vì Import
    // ghi nhiều key app_config cùng lúc -> emit nhiều lần dồn dập). Request đăng ký mã
    // máy mới vì vậy có thể KHÔNG BAO GIỜ tới được server, dù log vẫn hiện "đăng ký
    // thành công" ở LẦN GỌI TRƯỚC đó với mã CŨ — khiến server chỉ biết mã đầu tiên cho
    // tới khi Render restart (dispatcher mới, không còn request cũ để cancel nhầm).
    //
    // Cho apiClient một Dispatcher RIÊNG (không share với okHttpClient) để
    // cancelAll() của 2 kênh SSE dài hạn không bao giờ đụng tới các request đăng ký
    // ngắn hạn này.
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .dispatcher(Dispatcher())
        .build()

    // ✅ MỚI: Sửa lỗi "chat Website timeout ở đúng 15s, trong khi Telegram/Facebook chat
    // bình thường" — sendWebsiteReply() trước đây dùng CHUNG apiClient (readTimeout=15s),
    // vốn chỉ được thiết kế cho các request đăng ký ngắn hạn (registerDeviceCode/
    // registerWidgetKey/registerPageMappings, xem comment ở apiClient phía trên). Nhưng
    // sendWebsiteReply() gửi phản hồi VỀ Render Gateway sau khi Groq đã trả lời xong — nếu
    // Render đang chậm/cold-start, 15s không đủ, và exception bị bắt nhầm bởi catch-all của
    // handleIncomingWebhookEvent() rồi log sai thành "Lỗi giải mã gói tin webhook". Facebook/
    // Telegram không bị lỗi này vì chúng gửi phản hồi thẳng tới Graph API/Telegram API, không
    // đi qua Render. Dùng Dispatcher RIÊNG (không share với okHttpClient/apiClient) — cùng lý
    // do đã áp dụng cho apiClient — để cancelAll() của các kênh khác không vô tình hủy ngang
    // request gửi phản hồi đang chờ Render.
    private val replyClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .dispatcher(Dispatcher())
        .build()

    private val pollingClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CHANNEL_ID = "WebhookGatewayServiceChannel_v2"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()

        createNotificationChannel()
        startForegroundService()
        
        startCloudGatewaySSE()       
        // ✅ SỬA LỖI: vòng lặp SSE riêng biệt cho tín hiệu cuộc gọi P2P, kết nối tới
        // /call/stream/{deviceCode} — tách hẳn khỏi /stream/{token} dùng chung cho
        // chat. Trước đây call_signal bị bắt lẫn trong luồng chat chung, và vì
        // /stream/{token} broadcast theo token (nhiều máy có thể dùng chung 1 token),
        // máy vừa gọi đi cũng nhận lại tín hiệu của chính mình. Kênh riêng theo
        // deviceCode loại bỏ hoàn toàn vấn đề đó.
        startCallSignalSSE()
        // ✅ MỚI: kênh SSE riêng biệt cho tin nhắn chat của khách Website, kết nối tới
        // /widget/inbox/{widget_key}/{token} — tách hẳn khỏi /stream/{token} dùng chung
        // cho facebook/instagram/telegram. Cùng lý do với startCallSignalSSE() ở trên.
        startWebsiteMessageSSE()
        // ✅ MỚI: kênh SSE riêng biệt cho tin nhắn chat Facebook/Instagram, kết nối tới
        // /page/inbox/{device_code}/{token} — tách hẳn khỏi /stream/{token} dùng chung.
        // Cùng lý do với startWebsiteMessageSSE() ở trên.
        startPageMessageSSE()
        // ✅ MỚI: kênh SSE riêng biệt cho tín hiệu "camera báo động", kết nối tới
        // /camera/alarm/stream/{device_code} — cho phép quét camera sớm hơn ngay khi
        // camera tự phát hiện chuyển động, thay vì chỉ đợi lịch quét định kỳ.
        startCameraAlarmSSE()
        // ✅ MỚI: kênh SSE riêng biệt cho LỆNH ĐIỀU KHIỂN THIẾT BỊ (Tuya, và các loại khác
        // sẽ tích hợp sau), kết nối tới /device/command/stream/{deviceCode}. Chỉ thực sự mở
        // kết nối nếu deviceRole = "camera_node" (xem điều kiện bên trong hàm) — máy "client"
        // gọi startDeviceCommandSSE() vô hại, nó tự đứng chờ, không tốn tài nguyên đáng kể.
        startDeviceCommandSSE()
        startTelegramLongPolling()   
        startHeartbeatLoop()         
        startScheduleLoop()
        
        startTuyaSyncLoop()
        startPatternMiningLoop()

        // ✅ MỚI: SỬA BUG "LỆCH SESSION SSE" — trước đây khi người dùng Import/Lưu cấu
        // hình mới trong SettingsScreen (đổi call.device_code, gateway URL/token...),
        // startCloudGatewaySSE()/startCallSignalSSE() bên dưới KHÔNG biết gì cả: cả 2 đang
        // block ở okHttpClient.newCall(request).execute() với request cũ, và vòng
        // while(isActive) bên trong tuy CÓ đọc lại config mỗi lần lặp, nhưng chỉ lặp lại
        // sau khi request HIỆN TẠI tự đứt (lỗi mạng/server restart) — không có gì chủ động
        // đóng nó lại ngay khi cấu hình đổi. Đây là lý do trước đây phải "Restart OnRender"
        // (ép server đóng kết nối) mới sửa được, thay vì tự nhận cấu hình mới ngay lập tức.
        //
        // dispatcher.cancelAll() ngắt NGAY mọi request HTTP đang chạy dở trên okHttpClient
        // (dùng chung cho cả startCloudGatewaySSE() và startCallSignalSSE(), xem khai báo ở
        // trên) — Call.execute() đang block sẽ ném IOException ngay lập tức, vòng
        // while(isActive) bắt được, lặp lại, và tự đọc deviceCode/URL/token MỚI NHẤT từ
        // configProvider/callSkill ở lượt kế tiếp. KHÔNG đụng tới pollingClient (Telegram
        // long-poll) hay apiClient — 2 client đó tách riêng, không liên quan cấu hình
        // deviceCode/gateway nên không cần ngắt.
        //
        // Chấp nhận đánh đổi: cả 2 kênh SSE (chat/webhook lẫn cuộc gọi) cùng gián đoạn
        // 1-2 giây mỗi lần đổi cấu hình, kể cả khi chỉ 1 trong 2 thực sự cần restart — đơn
        // giản và an toàn hơn nhiều so với tách riêng Call reference cho từng kênh.
        // ✅ SỬA LỖI (root cause "Telegram timeout liên tục, tin nhắn biến mất hoàn toàn"):
        // configUpdatedEvent trước đây được collect() TRỰC TIẾP không debounce — khi Import
        // backup ghi hàng chục key app_config liên tiếp trong <1 giây, mỗi lần ghi bắn 1 sự
        // kiện, mỗi sự kiện gọi forceResyncBothChannels() NGAY LẬP TỨC (huỷ + mở lại 2 kết nối
        // SSE + gọi lại registerDeviceCode/registerWidgetKey qua coroutine launch riêng). Hàng
        // chục lần resync dồn dập như vậy làm bão hoà Dispatchers.IO ngay đúng lúc vòng lặp
        // Telegram long-polling (cũng chạy trên Dispatchers.IO) đang chờ response — khiến
        // request getUpdates() bị đói tài nguyên, vượt readTimeout(25s) của pollingClient, và
        // timeout hết vòng này đến vòng khác cho tới khi storm cấu hình dừng hẳn. Trong lúc đó
        // tin nhắn Telegram gửi tới đơn giản là CHƯA TỪNG được getUpdates() nhận về, nên không
        // bao giờ tới processQuery()/DB — không inbox, không notify, không log Groq nào cả.
        // Debounce 800ms: chỉ resync 1 lần sau khi cơn bão config-update lắng xuống, thay vì
        // resync ngay từng sự kiện một.
        serviceScope.launch {
            configProvider.configUpdatedEvent
                .debounce(800)
                .collect {
                    logger.i("WebhookGatewayService", "🔄 Cấu hình vừa đổi (Import/Lưu Settings) — ngắt SSE cũ để đăng ký lại ngay, không cần đợi Restart OnRender.")
                    forceResyncBothChannels()
                }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIChatVN2::WebhookWakeLock")
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    logger.i("WebhookGateway", "🔒 Đã kích hoạt WakeLock giữ thức CPU 24/7 an toàn.")
                }
            }
        } catch (e: Exception) {
            logger.e("WebhookGateway", "Không thể acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    logger.i("WebhookGateway", "🔓 Đã giải phóng WakeLock.")
                }
            }
        } catch (e: Exception) {
            logger.e("WebhookGateway", "Không thể release WakeLock", e)
        }
    }

    private fun startForegroundService() {
        val notification = buildNotification("Hệ thống Webhook Gateway đang hoạt động...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { 
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Omnichannel")
            .setContentText(contentText)
            // ✅ SỬA: trước đây dùng icon hệ thống chung android.R.drawable.ic_menu_info_details
            // (hình "i" generic, không phải logo app) — khiến notification "trạng thái kết nối
            // Cloud Gateway" của service này không đồng bộ icon với các notification khác trong
            // app (vốn dùng ic_notification_small + ic_launcher qua NotificationSkill.kt). Đổi
            // sang đúng 2 icon đó cho nhất quán.
            .setSmallIcon(R.drawable.ic_notification_small)
            .setLargeIcon(
                android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(contentText: String) {
        if (contentText == lastNotificationText) return 
        lastNotificationText = contentText

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = connectivityManager.activeNetworkInfo
                return activeNetworkInfo != null && activeNetworkInfo.isConnected
            }
        } catch (e: SecurityException) {
            logger.e("WebhookGateway", "⚠️ Thiếu quyền ACCESS_NETWORK_STATE. Mặc định coi như có mạng.")
            return true
        } catch (e: Exception) {
            return true
        }
    }

    private fun findPlugin(pluginId: String): Plugin? {
        return plugins.find { it.manifest.id == pluginId }
    }

    private fun registerPageMappings(gatewayUrl: String, gatewayToken: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dbPages = database.facebookPageDao().getAllPages()
                val pageIds = dbPages.map { it.id }

                if (pageIds.isEmpty()) return@launch

                // ✅ MỚI: kèm deviceCode của chính máy này — để server biết đúng máy nào
                // (trong số nhiều máy có thể dùng CHUNG 1 token) thực sự phụ trách các
                // Page này, phục vụ kênh riêng /page/inbox/{device_code}/{token} (xem
                // startPageMessageSSE()). Dùng chung nguồn duy nhất với registerDeviceCode()
                // (Mutex-safe qua callSkill.getOrCreateMyDeviceCode()).
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                val jsonArray = org.json.JSONArray(pageIds)
                val bodyJson = org.json.JSONObject().apply {
                    put("token", gatewayToken)
                    put("pageIds", jsonArray)
                    put("deviceCode", deviceCode)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = bodyJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/register")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        logger.i("CloudGateway", "📇 Đã đăng ký lại ánh xạ ${pageIds.size} Page ID với Render: $pageIds")
                    } else {
                        logger.w("CloudGateway", "⚠️ Đăng ký ánh xạ Page ID thất bại, mã lỗi: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("CloudGateway", "❌ Lỗi khi đăng ký ánh xạ Page ID: ${e.message}")
            }
        }
    }

    // ✅ SỬA LỖI: Mutex bảo vệ đoạn đọc-rồi-ghi (read-then-write) bên dưới, theo đúng
    // pattern đã áp dụng cho deviceCode ở CallSkill.getOrCreateMyDeviceCode() — nguồn
    // duy nhất sinh/đọc widget_key, tránh race-condition nếu sau này có thêm điểm gọi
    // registerWidgetKey() thứ 2 (hiện tại chỉ có 1 điểm gọi, nhưng rẻ để phòng ngừa
    // ngay từ bây giờ hơn là debug lại race-condition y hệt deviceCode đã từng gặp).
    private val widgetKeyMutex = Mutex()

    private suspend fun getOrCreateMyWidgetKey(): String = widgetKeyMutex.withLock {
        var existing = configProvider.getString(AppConfigDefaults.WEBSITE_WIDGET_KEY).trim()
        if (existing.isNotBlank()) return@withLock existing
        existing = java.util.UUID.randomUUID().toString().replace("-", "")
        configProvider.set(AppConfigDefaults.WEBSITE_WIDGET_KEY, existing)
        // ⚠️ CẢNH BÁO QUAN TRỌNG: widget_key cũ (nếu có) không được lưu ở đâu khác
        // ngoài field này — khi bị xóa (cài lại app, xóa dữ liệu, khôi phục backup
        // thiếu field này...), key MỚI sinh ra ở đây sẽ KHÔNG khớp với mã nhúng
        // <script> đã dán sẵn trên (các) website từ trước (mã đó chứa widget_key CŨ,
        // là văn bản tĩnh, không tự cập nhật theo máy). Hệ quả: mọi tin nhắn từ khách
        // qua các website đó sẽ liên tục bị lọc/bỏ qua (xem bộ lọc incomingWidgetKey ở
        // luồng nhận SSE) cho tới khi người dùng vào Cài đặt lấy lại mã nhúng MỚI và
        // dán đè lên toàn bộ website đang dùng mã cũ.
        logger.w("CloudGateway", "⚠️ ĐÃ TỰ SINH widget_key MỚI (widget_key cũ bị mất do xóa dữ liệu/cài lại). " +
            "Mọi website đang dùng mã nhúng CŨ sẽ ngừng nhận tin nhắn cho tới khi bạn vào " +
            "Cài đặt > Website Chat Widget, lấy mã nhúng MỚI và dán đè lên (các) website đang dùng.")
        updateNotification("⚠️ Widget key đã đổi — cần dán lại mã nhúng mới trên website!")
        existing
    }

    private fun registerWidgetKey(gatewayUrl: String, gatewayToken: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val widgetKey = getOrCreateMyWidgetKey()

                val bodyJson = org.json.JSONObject().apply {
                    put("token", gatewayToken)
                    put("widgetKey", widgetKey)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = bodyJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/register_widget_key")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        logger.i("CloudGateway", "📇 Đã đăng ký lại widget_key với Render.")
                    } else {
                        logger.w("CloudGateway", "⚠️ Đăng ký widget_key thất bại, mã lỗi: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("CloudGateway", "❌ Lỗi khi đăng ký widget_key: ${e.message}")
            }
        }
    }

    // ✅ MỚI: Đăng ký DeviceCode của máy lên Gateway để nhận cuộc gọi thoại/video P2P
    private fun registerDeviceCode(gatewayUrl: String, gatewayToken: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // ✅ SỬA LỖI: gọi qua callSkill.getOrCreateMyDeviceCode() (có Mutex) thay vì
                // tự đọc-rồi-tự-sinh riêng — xem giải thích đầy đủ ở khai báo field callSkill.
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                val bodyJson = org.json.JSONObject().apply {
                    put("token", gatewayToken)
                    put("deviceCode", deviceCode)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = bodyJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/register_device_code")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        logger.i("CloudGateway", "📇 Đã đăng ký device_code ($deviceCode) với Render Gateway.")
                    } else {
                        logger.w("CloudGateway", "⚠️ Đăng ký device_code thất bại, mã lỗi: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("CloudGateway", "❌ Lỗi khi đăng ký device_code: ${e.message}")
            }
        }
    }

    private fun startCloudGatewaySSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("CloudGateway", "⚠️ Thiếu cấu hình Gateway URL hoặc Token trong cài đặt.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    if (!isOfflineLogged) {
                        logger.w("CloudGateway", "⚠️ Không có kết nối Internet. Đang chờ kết nối lại...")
                        updateNotification("Không có Internet, đang chờ kết nối lại...")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                isOfflineLogged = false

                try {
                    logger.i("CloudGateway", "🔌 Đang thiết lập đường ống SSE đến Render Gateway...")
                    updateNotification("Đang kết nối Cloud Gateway...")
                    
                    val request = Request.Builder()
                        .url("$gatewayUrl/stream/$gatewayToken")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    cloudGatewayCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("CloudGateway", "⚠️ Kết nối SSE thất bại với mã lỗi HTTP: ${response.code}")
                            updateNotification("Lỗi kết nối SSE (${response.code})")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null
                        
                        logger.i("CloudGateway", "🟢 Đường ống SSE đã mở! Sẵn sàng nhận Webhook.")
                        updateNotification("Cổng đám mây: Đã kết nối")

                        registerPageMappings(gatewayUrl, gatewayToken)
                        registerWidgetKey(gatewayUrl, gatewayToken)
                        registerDeviceCode(gatewayUrl, gatewayToken) // ✅ MỚI

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    logger.i("CloudGateway", "📥 Nhận dữ liệu Webhook mới từ Cloud: $rawData")
                                    
                                    serviceScope.launch {
                                        handleIncomingWebhookEvent(rawData, gatewayUrl, gatewayToken)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("CloudGateway", "❌ Mất đường ống SSE: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    updateNotification("Mất kết nối Gateway, đang kết nối lại...")
                    // ✅ SỬA LỖI (root cause "câm/mù cho tới khi restart Render"): trước đây
                    // khi CHỈ kênh này (CloudGateway) tự đứt do timeout/lỗi mạng đơn thuần
                    // (không phải do configUpdatedEvent), kênh CallSignalSSE bên dưới KHÔNG
                    // hề bị ảnh hưởng — nó tiếp tục giữ nguyên kết nối SSE cũ với deviceCode
                    // CŨ (đọc 1 lần lúc vòng lặp của NÓ khởi động), dù CloudGateway sắp
                    // reconnect và đăng ký deviceCode MỚI NHẤT qua registerDeviceCode().
                    // Kết quả: server nhận đúng deviceCode mới, nhưng máy vẫn đang LẮNG NGHE
                    // tín hiệu cuộc gọi ở kênh SSE của deviceCode CŨ — 2 kênh lệch pha nhau.
                    // Signaling (offer/answer/ICE) qua /call_signal vẫn "gửi thành công" vì
                    // route chỉ dựa vào targetDeviceCode do máy GỌI ĐI cung cấp, nhưng máy
                    // NHẬN không nhận đúng luồng tín hiệu mới nhất qua kênh SSE cũ của nó ->
                    // WebRTC negotiation không hoàn tất -> kết nối "được" (UI hiện đổ
                    // chuông/nghe máy) nhưng audio/video không bao giờ thiết lập (câm/mù).
                    // Trước đây chỉ restart Render mới sửa được vì restart ép TCP của CẢ 2
                    // kênh cùng đứt đồng thời -> cả 2 cùng resync deviceCode nhất quán.
                    //
                    // Cách sửa: chủ động ngắt cả 2 Call reference ngay tại đây (không chỉ
                    // riêng cancelAll() vốn không đủ mạnh với kênh giữ bền bằng ping) để ép
                    // kênh CallSignalSSE cũng đứt và tự resync deviceCode mới nhất, không
                    // cần đợi restart Render.
                    forceResyncBothChannels()
                    delay(5000)
                }
            }
        }
    }

    // ✅ MỚI: Tách từ khối xử lý vốn nằm trực tiếp trong serviceScope.launch { } bên trong
    // startCloudGatewaySSE() — giữ NGUYÊN Y HỆT toàn bộ logic bên trong (dedup, houseManager
    // quyết định auto-reply, mutex theo unifiedUsername, xử lý ảnh, gửi phản hồi facebook/
    // website...), chỉ đổi return@launch -> return vì giờ đây là thân hàm chứ không còn nằm
    // trong launch { } nữa. Đây là hàm xử lý DÙNG CHUNG cho cả 2 nguồn SSE: facebook/telegram-
    // token-chung (gọi từ startCloudGatewaySSE(), phía trên) và website-per-widget (sẽ gọi từ
    // startWebsiteMessageSSE() sắp thêm bên dưới) — tránh phải chép tay/nhân đôi khối logic dài
    // và dễ lệch pha khi có 2 nguồn nhận sự kiện khác nhau.
    private suspend fun handleIncomingWebhookEvent(rawData: String, gatewayUrl: String, gatewayToken: String) {
                                        try {
                                            val jsonObj = org.json.JSONObject(rawData)

                                            // ✅ MỚI: kết quả thực thi THẬT của 1 lệnh thiết bị (Tuya, và sau này MQTT...) gửi
                                            // qua sendDeviceCommandToHomeAndAwaitResult() — Camera Node báo về qua
                                            // POST /device/command_result, gateway relay vào đúng kênh /stream/{token} này
                                            // với "type"="device_command_result" (KHÁC với "event", vốn dùng cho token_sync/
                                            // chat bên dưới). Xử lý xong thì return ngay — payload này không có
                                            // platform/senderId/text nên không được rơi tiếp xuống nhánh chat phía dưới.
                                            if (jsonObj.optString("type", "") == "device_command_result") {
                                                val requestId = jsonObj.optString("requestId", "")
                                                val success = jsonObj.optBoolean("success", false)
                                                val message = jsonObj.optString("message", "")
                                                if (requestId.isNotEmpty()) {
                                                    logger.i("DeviceCommandResult", "📩 Nhận kết quả thực thi requestId=$requestId success=$success: $message")
                                                    deviceCommandGatewayClient.resolvePendingResult(requestId, success, message)
                                                } else {
                                                    logger.w("DeviceCommandResult", "Nhận device_command_result thiếu requestId, bỏ qua: $rawData")
                                                }
                                                return
                                            }

                                            val event = jsonObj.optString("event", "")

                                            if (event == "token_sync") {
                                                val plat = jsonObj.optString("platform", "")
                                                
                                                if (plat == "facebook") {
                                                    val pagesArray = jsonObj.optJSONArray("pages")
                                                    if (pagesArray != null) {
                                                        val pagesList = mutableListOf<FacebookPageEntity>()
                                                        for (i in 0 until pagesArray.length()) {
                                                            val pObj = pagesArray.getJSONObject(i)
                                                            pagesList.add(
                                                                FacebookPageEntity(
                                                                    id = pObj.getString("id"),
                                                                    name = pObj.getString("name"),
                                                                    accessToken = pObj.getString("accessToken")
                                                                )
                                                            )
                                                        }
                                                        withContext(Dispatchers.IO) {
                                                            database.facebookPageDao().insertPages(pagesList)
                                                        }
                                                        logger.i("CloudGateway", "🔑 Đã lưu ${pagesList.size} Facebook Pages vào cơ sở dữ liệu thành công!")
                                                    }
                                                }
                                            } else {
                                                val platform = jsonObj.optString("platform", "website") 
                                                val senderId = jsonObj.optString("senderId", "external_user")
                                                val text = jsonObj.optString("text", "")
                                                val incomingPageId = jsonObj.optString("pageId", "")
                                                val incomingImageUrl = jsonObj.optString("imageUrl", "").takeIf { it.isNotBlank() }
                                                val incomingImageBase64Raw = jsonObj.optString("imageBase64", "").takeIf { it.isNotBlank() }
                                                // ✅ ĐÃ BỎ bộ lọc incomingWidgetKey cũ: trước đây /stream/{token} broadcast MỌI
                                                // tin nhắn website tới TẤT CẢ máy dùng chung token, nên mỗi máy phải tự so
                                                // widgetKey trong payload với widgetKey của chính nó để loại bỏ tin không
                                                // thuộc mình. Giờ tin nhắn website không còn đi qua /stream/{token} nữa — nó
                                                // đến qua kênh riêng /widget/inbox/{widget_key}/{token} (xem
                                                // startWebsiteMessageSSE()), nơi SERVER đã đảm bảo chỉ đẩy đúng tin của đúng
                                                // widget_key tới đúng máy. Hàm này giờ chỉ còn nhận: (a) facebook/instagram từ
                                                // /stream/{token}, không bao giờ là platform=="website" nữa, hoặc (b) website
                                                // từ /widget/inbox/..., vốn đã chắc chắn thuộc đúng máy — nên không cần lọc gì
                                                // thêm ở đây nữa.
                                                if (text.isNotBlank() || incomingImageUrl != null || incomingImageBase64Raw != null) {
                                                    val unifiedUsername = "${platform}_$senderId"

                                                    // ✅ MỚI: chặn xử lý trùng lặp — gateway SSE có thể gửi lại cùng 1 sự kiện
                                                    // khi client reconnect (đã biết từ trước: mất heartbeat -> reconnect ->
                                                    // gateway gửi lại backlog), không có message/event ID để khử trùng theo ID
                                                    // nên khử theo nội dung trong 1 khung ngắn. Đây chính là nguyên nhân khách
                                                    // nhận được 2 phản hồi cho 1 tin nhắn: processQuery() bị gọi 2 lần độc lập
                                                    // cho cùng 1 nội dung, phản hồi nhanh (catalog/groq) tới trước, phản hồi
                                                    // đúng (device command) tới sau.
                                                    val dedupKey = "$platform|$senderId|$text|${incomingImageUrl ?: ""}"
                                                    if (isDuplicateWebhookEvent(dedupKey)) {
                                                        logger.w("CloudGateway", "⚠️ Bỏ qua sự kiện webhook trùng lặp (trong ${WEBHOOK_DEDUP_WINDOW_MS}ms): $unifiedUsername")
                                                        return
                                                    }

                                                    if (platform == "facebook" && incomingPageId.isNotBlank()) {
                                                        withContext(Dispatchers.IO) {
                                                            val existingForPage = database.cameraDao().getCustomerSetting(senderId)
                                                            if (existingForPage == null) {
                                                                database.cameraDao().insertCustomerSetting(
                                                                    CustomerSettingEntity(
                                                                        customerId = senderId,
                                                                        smartMode = 1,
                                                                        isActive = 1,
                                                                        updatedAt = System.currentTimeMillis(),
                                                                        timestamp = System.currentTimeMillis(),
                                                                        lastFacebookPageId = incomingPageId
                                                                    )
                                                                )
                                                            } else {
                                                                database.cameraDao().updateLastFacebookPageId(senderId, incomingPageId)
                                                            }
                                                        }
                                                    }

                                                    val resolvedImageBase64 = incomingImageBase64Raw
                                                        ?: incomingImageUrl?.let { downloadImageAsBase64(it) }

                                                    // ✅ ĐÃ SỬA: Giao tin nhắn cho Quản gia AI xử lý để cập nhật World State
                                                    // ("chat:*" unread_count, urgency, mood BUSY...) — trước đây bước này bị
                                                    // bỏ qua hoàn toàn nên pendingChatsCount/mood BUSY không bao giờ đúng.
                                                    // Đồng thời dùng luôn quyết định shouldAutoRespond của nó thay vì tự
                                                    // truy vấn cameraDao trùng lặp.
                                                    val chatDecision = houseManagerProvider.get().handleChatEventDecision(
                                                        platform = platform,
                                                        senderId = senderId,
                                                        message = text,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    val isBotEnabled = chatDecision.shouldAutoRespond

                                                    // ✅ MỚI: cùng lý do với Telegram — mỗi sự kiện SSE chạy trong 1
                                                    // serviceScope.launch riêng, không await lẫn nhau, nên 2 tin liên tiếp của
                                                    // CÙNG 1 khách (senderId) có thể chạy processQuery() song song, không đảm
                                                    // bảo thứ tự. Khoá theo unifiedUsername để tin sau đợi tin trước xử lý xong.
                                                    mutexFor(unifiedUsername).withLock {
                                                        if (isBotEnabled) {
                                                            val result = chatSkill.processQuery(
                                                                message = text,
                                                                username = unifiedUsername,
                                                                extraContext = "page_id:$incomingPageId",
                                                                fileUrl = incomingImageUrl,
                                                                imageBase64 = resolvedImageBase64
                                                            )

                                                            if (result is AgentKernel.PluginResult.Success) {
                                                                val replyMap = result.data as? Map<*, *>
                                                                val replyText = (replyMap?.get("response") as? String) ?: ""
                                                                val replyImagePath = replyMap?.get("imagePath") as? String
                                                                val replyImageBase64 = replyImagePath?.let { readLocalFileAsBase64(it) }

                                                                if (replyText.isNotEmpty() || replyImageBase64 != null) {
                                                                    when (platform) {
                                                                        "facebook" -> {
                                                                            val fbParams = mutableMapOf<String, Any>(
                                                                                "recipient_id" to senderId,
                                                                                "message" to replyText,
                                                                                "page_id" to incomingPageId
                                                                            )
                                                                            if (replyImageBase64 != null) fbParams["image_base64"] = replyImageBase64
                                                                            findPlugin("facebook")?.execute("send_messenger", fbParams)
                                                                        }
                                                                        "website" -> {
                                                                            sendWebsiteReply(gatewayUrl, gatewayToken, senderId, replyText, replyImageBase64)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            chatSkill.saveExternalUserMessage(
                                                                message = text,
                                                                username = unifiedUsername,
                                                                fileUrl = incomingImageUrl,
                                                                imageBase64 = resolvedImageBase64
                                                            )
                                                            logger.i("CloudGateway", "👤 Khách hàng $unifiedUsername đang ở chế độ Người Trực. Bot không tự trả lời. (ảnh: ${resolvedImageBase64 != null})")
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            logger.e("CloudGateway", "Lỗi giải mã gói tin webhook: ${e.message}")
                                        }
    }

    // ✅ SỬA LỖI: Vòng lặp SSE riêng biệt, độc lập hoàn toàn với startCloudGatewaySSE(),
    // kết nối tới /call/stream/{deviceCode} trên gateway. deviceCode là mã riêng của
    // TỪNG MÁY (khác với gatewayToken vốn dùng chung cho mọi máy của 1 người dùng),
    // nên tín hiệu cuộc gọi nhận qua kênh này chỉ có thể là của đúng máy hiện tại —
    // không còn khả năng broadcast nhầm sang máy khác dùng chung token.
    private fun startCallSignalSSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()

                // ✅ SỬA LỖI: gọi qua callSkill.getOrCreateMyDeviceCode() (Mutex-safe, cùng
                // nguồn với registerDeviceCode()) thay vì tự đọc trực tiếp từ config — đảm
                // bảo kênh SSE này LUÔN mở đúng bằng deviceCode đã đăng ký với gateway, dù
                // vòng lặp này khởi động trước hay sau registerDeviceCode(). Không cần
                // "đợi rồi thử lại" nữa vì getOrCreateMyDeviceCode() tự sinh (nếu chưa có)
                // và trả về ngay, không bao giờ rỗng.
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("CallSignalSSE", "⚠️ Thiếu cấu hình Gateway URL hoặc Token, tạm hoãn kênh signaling.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    delay(5000)
                    continue
                }

                isOfflineLogged = false

                try {
                    logger.i("CallSignalSSE", "🔌 Đang thiết lập đường ống SSE signaling riêng cho deviceCode=$deviceCode...")

                    val request = Request.Builder()
                        .url("$gatewayUrl/call/stream/$deviceCode")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    callSignalCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("CallSignalSSE", "⚠️ Kết nối SSE signaling thất bại, mã lỗi HTTP: ${response.code}")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null

                        logger.i("CallSignalSSE", "🟢 Đường ống SSE signaling đã mở, sẵn sàng nhận tín hiệu cuộc gọi.")

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    serviceScope.launch {
                                        try {
                                            val jsonObj = org.json.JSONObject(rawData)
                                            val signalObj = jsonObj.optJSONObject("signal") ?: jsonObj
                                            signalingManager.dispatchIncomingSignal(signalObj)
                                        } catch (e: Exception) {
                                            logger.e("CallSignalSSE", "Lỗi giải mã tín hiệu cuộc gọi: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("CallSignalSSE", "❌ Mất đường ống SSE signaling: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    // ✅ SỬA LỖI: đồng bộ chiều ngược lại với startCloudGatewaySSE() — nếu
                    // CHÍNH kênh signaling này tự đứt trước (lỗi mạng riêng của nó), cũng
                    // chủ động ngắt cả 2 Call reference để ép kênh CloudGateway đứt theo và
                    // cả 2 cùng resync deviceCode/token mới nhất đồng thời, tránh lệch pha
                    // deviceCode giữa 2 kênh (xem giải thích đầy đủ trong startCloudGatewaySSE()).
                    forceResyncBothChannels()
                    delay(5000)
                }
            }
        }
    }

    // ✅ MỚI: Vòng lặp SSE riêng biệt cho tín hiệu "camera vừa báo động" (motion do
    // chính camera IP tự phát hiện, đẩy về qua Alarm Server URL người dùng cấu hình
    // trong web UI của camera) — cùng pattern hệt startCallSignalSSE() ở trên, kết nối
    // tới /camera/alarm/stream/{deviceCode}.
    //
    // QUAN TRỌNG: alarm nhận được ở đây KHÔNG bỏ qua bất kỳ bước lọc nhiễu nào của
    // CameraSkill — nó chỉ là 1 cú hích để chạy scanCamera() SỚM HƠN lịch định kỳ, với
    // force=false (nghĩa là vẫn so pHash, vẫn tôn trọng cooldown y hệt 1 lượt quét nền
    // bình thường). Việc có gọi AI hay không vẫn hoàn toàn do logic sẵn có trong
    // scanCamera() quyết định — xem execute("scan", ...) trong CameraSkill.
    private fun startCameraAlarmSSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("CameraAlarmSSE", "⚠️ Thiếu cấu hình Gateway URL hoặc Token, tạm hoãn kênh báo động camera.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    delay(5000)
                    continue
                }

                isOfflineLogged = false

                try {
                    logger.i("CameraAlarmSSE", "🔌 Đang thiết lập đường ống SSE báo động camera cho deviceCode=$deviceCode...")

                    val request = Request.Builder()
                        .url("$gatewayUrl/camera/alarm/stream/$deviceCode")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    cameraAlarmCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("CameraAlarmSSE", "⚠️ Kết nối SSE báo động camera thất bại, mã lỗi HTTP: ${response.code}")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null

                        logger.i("CameraAlarmSSE", "🟢 Đường ống SSE báo động camera đã mở, sẵn sàng nhận tín hiệu.")

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    serviceScope.launch {
                                        try {
                                            val jsonObj = org.json.JSONObject(rawData)
                                            val cameraId = jsonObj.optString("cameraId", "").trim()
                                            if (cameraId.isNotEmpty()) {
                                                logger.i("CameraAlarmSSE", "🚨 Camera $cameraId báo động, kích hoạt quét sớm (vẫn tôn trọng pHash/cooldown).")
                                                // ✅ force=false: KHÔNG bypass cooldown/pHash — chỉ chạy sớm hơn lịch,
                                                // logic quyết định có gọi AI hay không vẫn nằm nguyên trong scanCamera().
                                                findPlugin("camera")?.execute(
                                                    "scan",
                                                    mapOf("cameraId" to cameraId, "force" to false)
                                                )
                                            }
                                        } catch (e: Exception) {
                                            logger.e("CameraAlarmSSE", "Lỗi giải mã tín hiệu báo động camera: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("CameraAlarmSSE", "❌ Mất đường ống SSE báo động camera: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    forceResyncBothChannels()
                    delay(5000)
                }
            }
        }
    }

    // ✅ MỚI: kênh SSE riêng biệt cho LỆNH ĐIỀU KHIỂN THIẾT BỊ (Tuya, và các loại khác sẽ
    // tích hợp sau — MQTT, Zigbee2MQTT...), kết nối tới /device/command/stream/{deviceCode}.
    // Cùng mẫu với startCameraAlarmSSE() ở trên, khác ở 2 điểm:
    // (1) chỉ khởi động nếu deviceRole = "camera_node" — máy "client" (mang theo người) không
    //     cần mở kênh này, nó chỉ CHỦ ĐỘNG GỬI lệnh qua sendDeviceCommand()/POST bên dưới, không
    //     bao giờ cần NHẬN lệnh;
    // (2) payload không xử lý trực tiếp tại đây — chỉ đọc "device_type" để định tuyến tới đúng
    //     Manager (hiện tại: TuyaManager qua handleTuyaCommand()), rồi tự báo kết quả ngược lại
    //     qua POST /device/command_result. Thêm 1 loại thiết bị mới CHỈ cần thêm 1 nhánh "when"
    //     mới ở đây — không cần sửa gì phía gateway.py.
    private fun startDeviceCommandSSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val deviceRole = configProvider.getString(AppConfigDefaults.DEVICE_ROLE, "client").trim()
                if (deviceRole != "camera_node") {
                    // Máy "client": không mở kênh nhận lệnh, chỉ đứng yên chờ config đổi (ví dụ
                    // người dùng đổi vai trò máy sau này) — kiểm tra lại mỗi 30s là đủ, không cần
                    // phản ứng ngay lập tức như các kênh chat/gọi.
                    delay(30_000)
                    continue
                }

                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("DeviceCommandSSE", "⚠️ Thiếu cấu hình Gateway URL hoặc Token, tạm hoãn kênh lệnh thiết bị.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    delay(5000)
                    continue
                }

                isOfflineLogged = false

                try {
                    logger.i("DeviceCommandSSE", "🔌 Đang thiết lập đường ống SSE lệnh thiết bị cho deviceCode=$deviceCode...")

                    val request = Request.Builder()
                        .url("$gatewayUrl/device/command/stream/$deviceCode")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    deviceCommandCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("DeviceCommandSSE", "⚠️ Kết nối SSE lệnh thiết bị thất bại, mã lỗi HTTP: ${response.code}")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null

                        logger.i("DeviceCommandSSE", "🟢 Đường ống SSE lệnh thiết bị đã mở, sẵn sàng nhận lệnh.")

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    serviceScope.launch {
                                        handleIncomingDeviceCommand(rawData, gatewayUrl, gatewayToken)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("DeviceCommandSSE", "❌ Mất đường ống SSE lệnh thiết bị: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    delay(5000)
                }
            }
        }
    }

    /**
     * Nhận 1 lệnh thiết bị từ SSE, định tuyến theo "device_type", thực thi, rồi báo kết quả
     * ngược lại qua POST /device/command_result. KHÔNG throw ra ngoài trong bất kỳ trường hợp
     * nào — 1 lệnh lỗi/JSON hỏng không được phép làm gãy vòng lặp đọc SSE phía trên.
     */
    private suspend fun handleIncomingDeviceCommand(rawData: String, gatewayUrl: String, gatewayToken: String) {
        var requestId = ""
        try {
            val jsonObj = org.json.JSONObject(rawData)
            requestId = jsonObj.optString("request_id", "")
            val deviceType = jsonObj.optString("device_type", "").trim()
            val command = jsonObj.optJSONObject("command")

            if (deviceType.isEmpty() || command == null) {
                logger.w("DeviceCommandSSE", "Lệnh thiết bị thiếu device_type hoặc command, bỏ qua: $rawData")
                return
            }

            logger.i("DeviceCommandSSE", "📥 Nhận lệnh device_type=$deviceType requestId=$requestId")

            // ✅ Điểm mở rộng: thêm 1 loại thiết bị mới (MQTT, Zigbee2MQTT, ESPHome...) trong
            // tương lai chỉ cần thêm 1 nhánh "when" mới ở đây — không đụng gì tới gateway.py hay
            // vòng lặp SSE phía trên, vì cả 2 đều coi "command" là JSON mù, không hiểu nội dung.
            val result: CommandExecutionResult = when (deviceType) {
                "tuya" -> handleTuyaCommand(command)
                else -> CommandExecutionResult(false, "device_type không được hỗ trợ: $deviceType")
            }

            if (requestId.isNotEmpty()) {
                reportDeviceCommandResult(gatewayUrl, gatewayToken, requestId, result)
            }
        } catch (e: Exception) {
            logger.e("DeviceCommandSSE", "Lỗi xử lý lệnh thiết bị: ${e.message}")
            if (requestId.isNotEmpty()) {
                reportDeviceCommandResult(gatewayUrl, gatewayToken, requestId,
                    CommandExecutionResult(false, "Lỗi xử lý trên Camera Node: ${e.message}"))
            }
        }
    }

    /** Kết quả thực thi 1 lệnh thiết bị — dùng chung cho mọi loại device_type. */
    private data class CommandExecutionResult(val success: Boolean, val message: String)

    /**
     * Diễn giải "command" JSON thành lời gọi TuyaManager thật. Tái dùng ĐÚNG turnOn()/turnOff()
     * hiện có (đã có sẵn toàn bộ logic local-first/cloud-fallback trong TuyaManager) — không
     * viết lại logic bật/tắt ở đây, chỉ dịch payload JSON thành tham số gọi hàm.
     *
     * Định dạng command mong đợi: {"deviceName": "Đèn phòng khách", "action": "on" | "off"}
     */
    private suspend fun handleTuyaCommand(command: org.json.JSONObject): CommandExecutionResult {
        val deviceName = command.optString("deviceName", "").trim()
        val action = command.optString("action", "").trim().lowercase()

        if (deviceName.isEmpty()) {
            return CommandExecutionResult(false, "Thiếu deviceName trong lệnh Tuya")
        }

        return try {
            when (action) {
                "on" -> {
                    tuyaManager.turnOn(deviceName)
                    CommandExecutionResult(true, "Đã bật $deviceName")
                }
                "off" -> {
                    tuyaManager.turnOff(deviceName)
                    CommandExecutionResult(true, "Đã tắt $deviceName")
                }
                else -> CommandExecutionResult(false, "action không hợp lệ: '$action' (chỉ chấp nhận on/off)")
            }
        } catch (e: Exception) {
            CommandExecutionResult(false, "Lỗi điều khiển Tuya: ${e.message}")
        }
    }

    /** Báo kết quả thực thi lệnh ngược lại gateway, để nó route về đúng Client đang chờ. */
    private fun reportDeviceCommandResult(
        gatewayUrl: String, gatewayToken: String, requestId: String, result: CommandExecutionResult
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val bodyJson = org.json.JSONObject().apply {
                    put("token", gatewayToken)
                    put("requestId", requestId)
                    put("success", result.success)
                    put("message", result.message)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = bodyJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/device/command_result")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("DeviceCommandSSE", "⚠️ Báo kết quả lệnh thất bại, mã lỗi: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("DeviceCommandSSE", "❌ Lỗi báo kết quả lệnh: ${e.message}")
            }
        }
    }

    /**
     * Client (máy mang theo người) gửi lệnh xuống Camera Node ở nhà khi ở ngoài mạng LAN — xem
     * DeviceCommandGatewayClient.kt (tách ra khỏi Service này vì nơi gọi thật, SmartSwitchSkill,
     * không nên phụ thuộc vòng đời Service chỉ để POST 1 lệnh đơn giản).
     */

    // toàn với startCloudGatewaySSE() (facebook/instagram/telegram-token-chung) — kết nối
    // tới /widget/inbox/{widget_key}/{token} trên gateway. Cùng lý do và cùng mẫu với
    // startCallSignalSSE() ở trên: widget_key là mã riêng của TỪNG MÁY (khác với
    // gatewayToken vốn dùng chung cho mọi máy của 1 người dùng), nên tin nhắn website
    // nhận qua kênh này chỉ có thể là của đúng máy hiện tại — không còn khả năng broadcast
    // nhầm sang máy khác dùng chung token như trước (/stream/{token} không còn nhận tin
    // website nữa, xem app.py: /webhook giờ push qua push_message_to_widget()).
    //
    // Dữ liệu SSE nhận được ở đây đã đúng NGUYÊN VẸN định dạng JSON mà
    // handleIncomingWebhookEvent() mong đợi (platform/senderId/text/imageUrl/imageBase64 —
    // xem unified_payload trong app.py /webhook), nên gọi thẳng vào hàm xử lý DÙNG CHUNG đó
    // — không cần chép tay/nhân đôi logic dedup, houseManager, mutex, xử lý ảnh... vốn đã
    // nằm sẵn trong handleIncomingWebhookEvent().
    // ✅ MỚI: Vòng lặp SSE riêng biệt cho TIN NHẮN CHAT của khách Website, độc lập hoàn
    // toàn với startCloudGatewaySSE() (facebook/instagram/telegram-token-chung) — kết nối
    // tới /widget/inbox/{widget_key}/{token} trên gateway. Cùng lý do và cùng mẫu với
    // startCallSignalSSE() ở trên: widget_key là mã riêng của TỪNG MÁY (khác với
    // gatewayToken vốn dùng chung cho mọi máy của 1 người dùng), nên tin nhắn website
    // nhận qua kênh này chỉ có thể là của đúng máy hiện tại — không còn khả năng broadcast
    // nhầm sang máy khác dùng chung token như trước (/stream/{token} không còn nhận tin
    // website nữa, xem app.py: /webhook giờ push qua push_message_to_widget()).
    //
    // Dữ liệu SSE nhận được ở đây đã đúng NGUYÊN VẸN định dạng JSON mà
    // handleIncomingWebhookEvent() mong đợi (platform/senderId/text/imageUrl/imageBase64 —
    // xem unified_payload trong app.py /webhook), nên gọi thẳng vào hàm xử lý DÙNG CHUNG đó
    // — không cần chép tay/nhân đôi logic dedup, houseManager, mutex, xử lý ảnh... vốn đã
    // nằm sẵn trong handleIncomingWebhookEvent().
    private fun startWebsiteMessageSSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("WidgetInboxSSE", "⚠️ Thiếu cấu hình Gateway URL hoặc Token, tạm hoãn kênh inbox website.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    delay(5000)
                    continue
                }

                // ✅ SỬA LỖI: gọi qua getOrCreateMyWidgetKey() (Mutex-safe, cùng nguồn với
                // registerWidgetKey()) thay vì tự đọc trực tiếp từ config — đảm bảo kênh SSE
                // này LUÔN mở đúng bằng widget_key đã đăng ký với gateway, dù vòng lặp này
                // khởi động trước hay sau registerWidgetKey().
                val widgetKey = getOrCreateMyWidgetKey()

                isOfflineLogged = false

                try {
                    logger.i("WidgetInboxSSE", "🔌 Đang thiết lập đường ống SSE inbox riêng cho widget_key=$widgetKey...")

                    val request = Request.Builder()
                        .url("$gatewayUrl/widget/inbox/$widgetKey/$gatewayToken")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    websiteMessageCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("WidgetInboxSSE", "⚠️ Kết nối SSE inbox website thất bại, mã lỗi HTTP: ${response.code}")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null

                        logger.i("WidgetInboxSSE", "🟢 Đường ống SSE inbox website đã mở, sẵn sàng nhận tin nhắn khách.")

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    logger.i("WidgetInboxSSE", "📥 Nhận tin nhắn Website mới: $rawData")
                                    serviceScope.launch {
                                        handleIncomingWebhookEvent(rawData, gatewayUrl, gatewayToken)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("WidgetInboxSSE", "❌ Mất đường ống SSE inbox website: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    // ✅ Đồng bộ với 2 kênh còn lại — nếu CHÍNH kênh này tự đứt trước, cũng
                    // chủ động ngắt cả 3 Call reference để cùng resync widget_key/deviceCode/
                    // token mới nhất đồng thời, tránh lệch pha giữa các kênh (xem giải thích
                    // đầy đủ trong startCloudGatewaySSE()/startCallSignalSSE()).
                    forceResyncBothChannels()
                    delay(5000)
                }
            }
        }
    }

    // ✅ MỚI: Vòng lặp SSE riêng biệt cho TIN NHẮN Facebook/Instagram, độc lập hoàn
    // toàn với startCloudGatewaySSE() (giờ chỉ còn nhận token_sync + fallback broadcast
    // khi server chưa kịp biết deviceCode phụ trách Page nào) — kết nối tới
    // /page/inbox/{device_code}/{token}. Cùng lý do và cùng mẫu với
    // startWebsiteMessageSSE() ở trên: tái sử dụng deviceCode (đã có sẵn cho Cuộc gọi
    // P2P, xem callSkill.getOrCreateMyDeviceCode()) làm ID ổn định của máy — không cần
    // sinh thêm 1 loại mã mới cho Facebook, vì mỗi máy vốn đã có deviceCode riêng.
    //
    // Dữ liệu SSE nhận được ở đây đã đúng NGUYÊN VẸN định dạng JSON mà
    // handleIncomingWebhookEvent() mong đợi (platform/senderId/text/pageId/imageUrl —
    // xem unified_payload trong app.py /webhook), nên gọi thẳng vào hàm xử lý DÙNG CHUNG
    // đó — không cần chép tay/nhân đôi logic dedup, houseManager, mutex, xử lý ảnh...
    private fun startPageMessageSSE() {
        serviceScope.launch(Dispatchers.IO) {
            var isOfflineLogged = false

            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank()) {
                    if (!isOfflineLogged) {
                        logger.w("PageInboxSSE", "⚠️ Thiếu cấu hình Gateway URL hoặc Token, tạm hoãn kênh inbox Facebook/Instagram.")
                        isOfflineLogged = true
                    }
                    delay(5000)
                    continue
                }

                if (!isNetworkAvailable()) {
                    delay(5000)
                    continue
                }

                // ✅ SỬA LỖI: gọi qua callSkill.getOrCreateMyDeviceCode() (Mutex-safe, cùng
                // nguồn với registerDeviceCode()/registerPageMappings()) thay vì tự đọc trực
                // tiếp từ config — đảm bảo kênh SSE này LUÔN mở đúng bằng deviceCode đã đăng
                // ký các Page của mình với gateway.
                val deviceCode = callSkill.getOrCreateMyDeviceCode()

                isOfflineLogged = false

                try {
                    logger.i("PageInboxSSE", "🔌 Đang thiết lập đường ống SSE inbox riêng cho Facebook/Instagram, deviceCode=$deviceCode...")

                    val request = Request.Builder()
                        .url("$gatewayUrl/page/inbox/$deviceCode/$gatewayToken")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = okHttpClient.newCall(request)
                    pageMessageCall = call
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("PageInboxSSE", "⚠️ Kết nối SSE inbox Facebook/Instagram thất bại, mã lỗi HTTP: ${response.code}")
                            delay(5000)
                            return@use
                        }

                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null

                        logger.i("PageInboxSSE", "🟢 Đường ống SSE inbox Facebook/Instagram đã mở, sẵn sàng nhận tin nhắn khách.")

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    logger.i("PageInboxSSE", "📥 Nhận tin nhắn Facebook/Instagram mới: $rawData")
                                    serviceScope.launch {
                                        handleIncomingWebhookEvent(rawData, gatewayUrl, gatewayToken)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("PageInboxSSE", "❌ Mất đường ống SSE inbox Facebook/Instagram: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    // ✅ Đồng bộ với các kênh còn lại — nếu CHÍNH kênh này tự đứt trước, cũng
                    // chủ động ngắt cả 4 Call reference để cùng resync deviceCode/widget_key/
                    // token mới nhất đồng thời, tránh lệch pha giữa các kênh.
                    forceResyncBothChannels()
                    delay(5000)
                }
            }
        }
    }

    // ✅ SỬA LỖI (bổ sung, phòng vệ thêm cho storm cấu hình): dispatcher IO RIÊNG với độ song
    // song giới hạn, tách hẳn khỏi Dispatchers.IO dùng chung — để hàng chục coroutine
    // resync/register bắn dồn dập lúc Import Settings không còn có thể tranh giành thread với
    // vòng lặp long-polling Telegram đang chờ response (vốn chỉ cần 1 thread riêng, luôn rảnh).
    private val telegramPollDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun startTelegramLongPolling() {
        serviceScope.launch(telegramPollDispatcher) {
            var lastUpdateId = 0
            delay(3000)

            while (isActive) {
                val botToken = configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim()

                if (botToken.isBlank() || !isNetworkAvailable()) {
                    delay(10000)
                    continue
                }

                try {
                    val request = Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=15")
                        .get()
                        .build()

                    pollingClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val json = org.json.JSONObject(responseBody)
                            val ok = json.optBoolean("ok", false)
                            
                            if (ok) {
                                val result = json.optJSONArray("result") ?: org.json.JSONArray()
                                for (i in 0 until result.length()) {
                                    val update = result.getJSONObject(i)
                                    val updateId = update.getInt("update_id")
                                    lastUpdateId = updateId + 1

                                    val message = update.optJSONObject("message") ?: continue
                                    val text = message.optString("text", "")
                                    val chatId = message.getJSONObject("chat").getLong("id").toString()
                                    val photoArray = message.optJSONArray("photo")
                                    val largestPhotoFileId = if (photoArray != null && photoArray.length() > 0) {
                                        photoArray.getJSONObject(photoArray.length() - 1).optString("file_id", "")
                                    } else null
                                    val caption = message.optString("caption", "")

                                    if (text.isNotBlank() || !largestPhotoFileId.isNullOrEmpty()) {
                                        val effectiveText = if (text.isNotBlank()) text else caption
                                        logger.i("TelegramPoll", "📥 Nhận tin nhắn Telegram mới: '$effectiveText' từ ChatId: $chatId (ảnh: ${!largestPhotoFileId.isNullOrEmpty()})")
                                        val unifiedUsername = "telegram_$chatId"

                                        // ✅ ĐÃ SỬA: Cùng một điểm vào Quản gia AI như nhánh Facebook/Website,
                                        // để World State "chat:telegram_*" được cập nhật đầy đủ.
                                        val chatDecision = houseManagerProvider.get().handleChatEventDecision(
                                            platform = "telegram",
                                            senderId = chatId,
                                            message = effectiveText,
                                            timestamp = System.currentTimeMillis()
                                        )
                                        val isBotEnabled = chatDecision.shouldAutoRespond

                                        serviceScope.launch {
                                            val imageBase64 = largestPhotoFileId?.let { downloadTelegramPhotoAsBase64(botToken, it) }

                                            // ✅ MỚI: khoá theo đúng username này — nếu tin nhắn TRƯỚC của CÙNG
                                            // chatId vẫn đang xử lý dở (vd đang chờ Groq trả lời), tin nhắn này
                                            // sẽ ĐỢI đến lượt thay vì chạy song song không kiểm soát thứ tự.
                                            mutexFor(unifiedUsername).withLock {
                                                if (isBotEnabled) {
                                                    val result = chatSkill.processQuery(
                                                        message = effectiveText,
                                                        username = unifiedUsername,
                                                        imageBase64 = imageBase64
                                                    )
                                                    if (result is AgentKernel.PluginResult.Success) {
                                                        val replyMap = result.data as? Map<*, *>
                                                        val replyText = (replyMap?.get("response") as? String) ?: ""
                                                        val replyImagePath = replyMap?.get("imagePath") as? String
                                                        val replyImageBase64 = replyImagePath?.let { readLocalFileAsBase64(it) }
                                                        if (replyImageBase64 != null) {
                                                            sendTelegramPhoto(botToken, chatId, replyImageBase64, replyText)
                                                        } else if (replyText.isNotEmpty()) {
                                                            sendTelegramMessage(botToken, chatId, replyText)
                                                        }
                                                    }
                                                } else {
                                                    chatSkill.saveExternalUserMessage(
                                                        message = effectiveText,
                                                        username = unifiedUsername,
                                                        imageBase64 = imageBase64
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("TelegramPoll", "Lỗi thăm dò tin nhắn Telegram: ${e.message}")
                    delay(10000)
                }
            }
        }
    }

    private suspend fun sendWebsiteReply(gatewayUrl: String, gatewayToken: String, senderId: String, message: String, imageBase64: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                // ✅ SỬA LỖI: server giờ bắt buộc widgetKey trong payload /send để xác
                // định đúng khách website (nhiều máy có thể chung 1 gatewayToken, nên
                // token thôi không đủ để phân biệt khách của widget nào). Luôn dùng
                // widgetKey của chính máy này — máy chỉ nên trả lời khách thuộc widget
                // của mình (đã được lọc đúng từ bước nhận, xem incomingWidgetKey ở trên).
                val myWidgetKey = getOrCreateMyWidgetKey()
                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", message)
                    put("widgetKey", myWidgetKey)
                    if (!imageBase64.isNullOrEmpty()) {
                        put("imageBase64", imageBase64)
                    }
                }.toString()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/send/$gatewayToken")
                    .post(requestBody)
                    .build()

                replyClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("CloudGateway", "⚠️ Gửi phản hồi website thất bại, HTTP: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("CloudGateway", "Gửi phản hồi cho khách Website thất bại: ${e.message}")
            }
        }
    }

    private suspend fun downloadImageAsBase64(imageUrl: String, maxRetries: Int = 3): String? {
        return withContext(Dispatchers.IO) {
            var lastErrorMessage: String? = null
            repeat(maxRetries) { attempt ->
                try {
                    val request = Request.Builder().url(imageUrl).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastErrorMessage = "HTTP ${response.code}"
                            return@use
                        }
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            return@withContext android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        }
                        lastErrorMessage = "Body rỗng"
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                }
                if (attempt < maxRetries - 1) {
                    logger.w("CloudGateway", "⚠️ Tải ảnh đính kèm thất bại (lần ${attempt + 1}/$maxRetries): $lastErrorMessage. Đang thử lại...")
                    delay(1500L * (attempt + 1))
                }
            }
            logger.e("CloudGateway", "❌ Tải ảnh đính kèm thất bại sau $maxRetries lần thử: $lastErrorMessage")
            null
        }
    }

    private fun readLocalFileAsBase64(absolutePath: String): String? {
        return try {
            val file = java.io.File(absolutePath)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            logger.e("CloudGateway", "Lỗi đọc file ảnh cục bộ '$absolutePath': ${e.message}")
            null
        }
    }

    private suspend fun getTelegramFilePath(botToken: String, fileId: String, maxRetries: Int = 3): String? {
        return withContext(Dispatchers.IO) {
            var lastErrorMessage: String? = null
            repeat(maxRetries) { attempt ->
                try {
                    val getFileRequest = Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/getFile?file_id=$fileId")
                        .get()
                        .build()

                    pollingClient.newCall(getFileRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastErrorMessage = "HTTP ${response.code}"
                            return@use
                        }
                        val body = response.body?.string()
                        val json = body?.let { org.json.JSONObject(it) }
                        if (json?.optBoolean("ok", false) == true) {
                            val path = json.optJSONObject("result")?.optString("file_path")
                            if (!path.isNullOrEmpty()) return@withContext path
                        }
                        lastErrorMessage = "Phản hồi getFile không hợp lệ"
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                }
                if (attempt < maxRetries - 1) {
                    logger.w("TelegramPoll", "⚠️ getFile thất bại (lần ${attempt + 1}/$maxRetries): $lastErrorMessage. Đang thử lại...")
                    delay(1500L * (attempt + 1))
                }
            }
            logger.e("TelegramPoll", "❌ getFile thất bại sau $maxRetries lần thử: $lastErrorMessage")
            null
        }
    }

    private suspend fun downloadTelegramPhotoAsBase64(botToken: String, fileId: String, maxRetries: Int = 3): String? {
        val filePath = getTelegramFilePath(botToken, fileId, maxRetries) ?: return null

        return withContext(Dispatchers.IO) {
            var lastErrorMessage: String? = null
            repeat(maxRetries) { attempt ->
                try {
                    val fileRequest = Request.Builder()
                        .url("https://api.telegram.org/file/bot$botToken/$filePath")
                        .get()
                        .build()

                    okHttpClient.newCall(fileRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastErrorMessage = "HTTP ${response.code}"
                            return@use
                        }
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            return@withContext android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        }
                        lastErrorMessage = "Body rỗng"
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                }
                if (attempt < maxRetries - 1) {
                    logger.w("TelegramPoll", "⚠️ Tải ảnh Telegram thất bại (lần ${attempt + 1}/$maxRetries): $lastErrorMessage. Đang thử lại...")
                    delay(1500L * (attempt + 1))
                }
            }
            logger.e("TelegramPoll", "❌ Tải ảnh Telegram thất bại sau $maxRetries lần thử: $lastErrorMessage")
            null
        }
    }

    private suspend fun sendTelegramPhoto(token: String, chatId: String, imageBase64: String, caption: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                val captionSafe = caption.take(1024) 

                val multipartBuilder = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                if (captionSafe.isNotBlank()) {
                    multipartBuilder.addFormDataPart("caption", captionSafe)
                }
                multipartBuilder.addFormDataPart(
                    "photo",
                    "image.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendPhoto")
                    .post(multipartBuilder.build())
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("TelegramPoll", "⚠️ Gửi ảnh Telegram thất bại, HTTP: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("TelegramPoll", "Gửi ảnh về Telegram thất bại: ${e.message}")
            }
        }
    }

    private suspend fun sendTelegramMessage(token: String, chatId: String, text: String) {
        withContext(Dispatchers.IO) {
            try {
                val payload = org.json.JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("TelegramPoll", "⚠️ Gửi phản hồi telegram thất bại, HTTP: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("TelegramPoll", "Gửi phản hồi về Telegram thất bại: ${e.message}")
            }
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(10000)
            
            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                
                if (gatewayUrl.isNotBlank() && gatewayToken.isNotBlank() && isNetworkAvailable()) {
                    try {
                        logger.d("Heartbeat", "💓 Đang gửi nhịp tim gia hạn cổng kết nối...")
                        
                        val request = Request.Builder()
                            .url("$gatewayUrl/ping/$gatewayToken")
                            .get()
                            .build()

                        apiClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                logger.d("Heartbeat", "✅ Nhịp tim phản hồi tốt. Các cấu hình ánh xạ đã được gia hạn!")
                            }
                        }
                    } catch (e: Exception) {
                        logger.e("Heartbeat", "❌ Gửi nhịp tim giữ thức thất bại: ${e.message}")
                    }
                }
                delay(10 * 60 * 1000L)
            }
        }
    }

    private fun startScheduleLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(5000) 
            while (isActive) {
                try {
                    val schedules = database.scheduleDao().getAllSchedules()
                    val now = System.currentTimeMillis()

                    for (schedule in schedules) {
                        if (schedule.enabled != 1) continue

                        val shouldRun = CronParser.matches(schedule.cron, now, schedule.lastRunAt) ||
                                (schedule.intervalMinutes > 0 && 
                                 (now - schedule.lastRunAt) >= (schedule.intervalMinutes * 60_000L - 10_000L))

                        if (shouldRun) {
                            val plugin = plugins.find { it.manifest.id == schedule.pluginId }
                            if (plugin == null) {
                                logger.w("ScheduleLoop", "⚠️ Plugin không tồn tại: ${schedule.pluginId}")
                                continue
                            }
                            
                            try {
                                val params = if (schedule.params.isNotEmpty()) {
                                    jsonObjectToMap(org.json.JSONObject(schedule.params))
                                } else {
                                    emptyMap()
                                }

                                val result = intentExecutor.executePluginAction(schedule.pluginId, schedule.action, params)
                                when (result) {
                                    is AgentKernel.PluginResult.Failure ->
                                        logger.w("ScheduleLoop", "⚠️ Lịch trình bị chặn/lỗi: ${schedule.pluginId}.${schedule.action} -> ${result.error}")
                                    else ->
                                        logger.i("ScheduleLoop", "✅ Thực thi lịch trình thành công: ${schedule.pluginId}.${schedule.action} -> $result")
                                }
                            } catch (e: Exception) {
                                logger.e("ScheduleLoop", "❌ Lỗi thực thi action: ${schedule.pluginId}.${schedule.action}: ${e.message}")
                            }
                            database.scheduleDao().updateLastRun(schedule.id, now)
                        }
                    }
                } catch (e: Exception) {
                    logger.e("ScheduleLoop", "⚠️ Gặp lỗi trong vòng lặp quét lịch trình: ${e.message}")
                }
                delay(60_000L) 
            }
        }
    }

    private fun startTuyaSyncLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(15_000L) 
            while (isActive) {
                try {
                    syncTuyaDeviceStates()
                } catch (e: Exception) {
                    logger.e("TuyaSync", "Lỗi đồng bộ trạng thái Tuya dưới nền: ${e.message}")
                }
                // ✅ SỬA (tối ưu quota API): 10 phút -> 30 phút. Vòng lặp này chạy 24/7 bất kể
                // user có mở app hay không, nên interval càng ngắn thì chi phí nền càng lớn dù
                // không ai đụng vào app. Kết hợp với getStatusBatch() (xem TuyaSync bên dưới),
                // interval 30 phút giảm call nền xuống còn ~1/6 so với trước (10 phút, gọi từng
                // thiết bị). Có thể chỉnh lại nếu cần độ trễ phát hiện thay đổi vật lý ngắn hơn.
                delay(30 * 60 * 1000L) 
            }
        }
    }

    private suspend fun syncTuyaDeviceStates() = withContext(Dispatchers.IO) {
        val tuyaUidKey = stringPreferencesKey("tuya_uid") 
        val tuyaUid = applicationContext.dataStore.data.first()[tuyaUidKey] ?: "" 
        
        if (tuyaUid.isBlank()) return@withContext

        logger.d("TuyaSync", "🔄 Bắt đầu thăm dò trạng thái thiết bị thực tế từ Tuya Cloud...")
        
        val currentCloudDevices = tuyaManager.scanDevices()
        val now = System.currentTimeMillis()

        // ✅ SỬA (tối ưu quota API): trước đây gọi tuyaManager.getStatus(cloudDev.name) RIÊNG
        // cho từng thiết bị online (N call). Giờ gộp thành 1 lần getStatusBatch() cho TẤT CẢ
        // thiết bị online cùng lúc (chia nhóm 20 nếu nhiều hơn 20 thiết bị) — 1 call thay vì N.
        val onlineDeviceIds = currentCloudDevices.values.filter { it.online }.map { it.id }
        val batchStates = try {
            tuyaManager.getStatusBatch(onlineDeviceIds)
        } catch (e: Exception) {
            logger.e("TuyaSync", "Lỗi lấy trạng thái hàng loạt: ${e.message}")
            emptyMap()
        }

        currentCloudDevices.forEach { (deviceId, cloudDev) ->
            val cleanId = deviceId.trim()
            val existingState = database.worldStateDao().getState("tuya", cleanId)
            
            val oldStateStr = existingState?.let {
                try { org.json.JSONObject(it.attributesJson).optString("state", "false") } catch (e: Exception) { "false" }
            } ?: "false"

            val oldState = oldStateStr.toBooleanStrictOrNull() ?: false

            // batchStates thiếu key nghĩa là: thiết bị offline, hoặc call batch lỗi, hoặc
            // không dò được switch code -> giữ nguyên trạng thái cũ thay vì ép về false, để
            // tránh báo "tắt" giả khi thực ra chỉ là không lấy được dữ liệu.
            val newState = if (cloudDev.online) {
                batchStates[cleanId] ?: oldState
            } else {
                false
            }

            if (newState != oldState) {
                logger.i("TuyaSync", "🔁 Phát hiện biến động vật lý bên ngoài: Thiết bị $cleanId đổi trạng thái: $oldState ➔ $newState")
                
                com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                    database.worldStateDao(), "tuya", cleanId, "state", newState.toString()
                )

                database.eventLogDao().insertLog(
                    com.aichatvn.agent.data.model.EventLogEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = now,
                        source = "tuya",
                        sourceId = cleanId,
                        eventType = "state_change",
                        value = newState.toString(),
                        summary = "Thiết bị Tuya ${cloudDev.name} được chuyển sang trạng thái: ${if (newState) "Bật" else "Tắt"} thủ công từ bên ngoài."
                    )
                )

                deviceRegistry.updateNode(cleanId) { current ->
                    current.copy(
                        online = true,
                        status = if (newState) "Đang bật" else "Đang tắt",
                        lastSeen = now
                    )
                }
            }
        }
    }

    private fun startPatternMiningLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(30_000L) 
            while (isActive) {
                try {
                    // ✅ ĐÃ SỬA: Ủy quyền hoàn toàn tiến trình tự học thói quen SQLite cho Quản gia xử lý
                    houseManagerProvider.get().mineUserHabits()
                } catch (e: Exception) {
                    logger.e("PatternMining", "Lỗi khi khai thác mẫu hành vi: ${e.message}", e)
                }
                delay(24 * 60 * 60 * 1000L) 
            }
        }
    }

    private fun jsonObjectToMap(json: org.json.JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val value = json.get(key)
            if (value != org.json.JSONObject.NULL) {
                map[key] = when (value) {
                    is org.json.JSONObject -> jsonObjectToMap(value)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            val item = value.get(i)
                            if (item != org.json.JSONObject.NULL) {
                                list.add(if (item is org.json.JSONObject) jsonObjectToMap(item) else item)
                            }
                        }
                        list
                    }
                    else -> value
                }
            }
        }
        return map
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Webhook Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock() 
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        logger.i("WebhookGateway", "Task removed - restarting service")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent)
            } else {
                startService(restartServiceIntent)
            }
        } catch (e: Exception) {
            logger.e("WebhookGateway", "Không thể tự khởi động lại service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock() 
        serviceScope.cancel()
        logger.i("WebhookGateway", "Dịch vụ đã tắt hoàn toàn.")
    }
}