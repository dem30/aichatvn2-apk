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
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.ChatRequest
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.utils.Logger
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@AndroidEntryPoint
class WebhookGatewayService : Service() {

    @Inject
    lateinit var agentKernel: AgentKernel

    @Inject
    lateinit var logger: Logger

    // Nạp động Set các Plugin để tránh lỗi biên dịch của Hilt khi chưa tạo Zalo/WhatsApp/Telegram Skill
    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: NettyApplicationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sshSession: Session? = null

    // Biến lưu trạng thái thông báo gần nhất để chống spam cập nhật hệ thống
    private var lastNotificationText = ""

    companion object {
        private const val CHANNEL_ID = "WebhookGatewayServiceChannel"
        private const val NOTIFICATION_ID = 1002
        private const val VERIFY_TOKEN = "YOUR_VERIFY_TOKEN_HERE" // Verify Token chung cho Meta (FB/IG) và WhatsApp
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Giữ CPU chạy ổn định khi tắt màn hình điện thoại
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIChatVN2::WebhookWakeLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            logger.e("WebhookGateway", "Lỗi khởi tạo WakeLock", e)
        }

        createNotificationChannel()
        startForegroundService()
        startKtorServer()
        startEmbeddedSSHTunnel() // 2. Kích hoạt hầm kết nối SSH bảo mật cố định
    }

    // ✅ Notification khởi tạo ban đầu — trạng thái "đang kết nối"
    private fun startForegroundService() {
        val notification = buildNotification("Ktor Gateway & SSH Tunnel đang kết nối đa kênh...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ✅ ĐÃ SỬA: Đưa Priority về DEFAULT để luôn hiển thị icon trên thanh trạng thái và màn hình khóa
    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Omnichannel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Chỉ đổ chuông/rung đúng 1 lần đầu tiên khi dịch vụ chạy, các lần cập nhật sau sẽ im lặng
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    // ✅ Cập nhật nội dung notification và lọc trùng lặp để chống spam hệ thống
    private fun updateNotification(contentText: String) {
        if (contentText == lastNotificationText) return 
        lastNotificationText = contentText

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    // ✅ ĐÃ SỬA: Bọc bảo vệ chống sập ứng dụng nếu điện thoại chưa khai báo quyền ACCESS_NETWORK_STATE trong Manifest
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
            logger.e("WebhookGateway", "⚠️ Thiếu quyền ACCESS_NETWORK_STATE trong AndroidManifest.xml. Mặc định chạy tiếp không crash.")
            return true
        } catch (e: Exception) {
            return true
        }
    }

    // Hàm phụ tìm kiếm Plugin động ở Runtime bằng ID
    private fun findPlugin(pluginId: String): Plugin? {
        return plugins.find { it.manifest.id == pluginId }
    }

    private fun startKtorServer() {
        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = 8080) {
                    install(ContentNegotiation) {
                        gson()
                    }
                    routing {
                        route("/webhook") {

                            // ─── CỔNG 1: FACEBOOK MESSENGER ───
                            route("/facebook") {
                                get {
                                    val mode = call.request.queryParameters["hub.mode"]
                                    val token = call.request.queryParameters["hub.verify_token"]
                                    val challenge = call.request.queryParameters["hub.challenge"]

                                    if (mode == "subscribe" && token == VERIFY_TOKEN) {
                                        call.respondText(challenge ?: "")
                                    } else {
                                        call.respond(io.ktor.http.HttpStatusCode.Forbidden, "Verification failed")
                                    }
                                }
                                post {
                                    val body = call.receiveText()
                                    serviceScope.launch {
                                        val fbText = "Tin nhắn test từ FB"
                                        val fbPsid = "fb_sender_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = fbText, username = fbPsid, chatMode = "COMBINED"))
                                        findPlugin("facebook")?.execute("send_messenger", mapOf("recipient_id" to fbPsid, "message" to reply.responseText))
                                    }
                                    call.respond(io.ktor.http.HttpStatusCode.OK, "EVENT_RECEIVED")
                                }
                            }

                            // ─── CỔNG 2: INSTAGRAM MESSENGER ───
                            route("/instagram") {
                                get {
                                    val mode = call.request.queryParameters["hub.mode"]
                                    val token = call.request.queryParameters["hub.verify_token"]
                                    val challenge = call.request.queryParameters["hub.challenge"]
                                    if (mode == "subscribe" && token == VERIFY_TOKEN) {
                                        call.respondText(challenge ?: "")
                                    } else {
                                        call.respond(io.ktor.http.HttpStatusCode.Forbidden)
                                    }
                                }
                                post {
                                    val body = call.receiveText()
                                    serviceScope.launch {
                                        val igText = "Tin nhắn test từ Instagram"
                                        val igPsid = "ig_sender_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = igText, username = igPsid, chatMode = "COMBINED"))
                                        findPlugin("instagram")?.execute("send_messenger", mapOf("recipient_id" to igPsid, "message" to reply.responseText))
                                    }
                                    call.respond(io.ktor.http.HttpStatusCode.OK)
                                }
                            }

                            // ─── CỔNG 3: ZALO OA ───
                            route("/zalo") {
                                post {
                                    val body = call.receiveText()
                                    serviceScope.launch {
                                        val zaloText = "Tin nhắn test từ Zalo"
                                        val zaloUserId = "zalo_user_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = zaloText, username = zaloUserId, chatMode = "COMBINED"))
                                        findPlugin("zalo")?.execute("send_message", mapOf("recipient_id" to zaloUserId, "message" to reply.responseText))
                                    }
                                    call.respond(io.ktor.http.HttpStatusCode.OK)
                                }
                            }

                            // ─── CỔNG 4: WHATSAPP BUSINESS ───
                            route("/whatsapp") {
                                get {
                                    val mode = call.request.queryParameters["hub.mode"]
                                    val token = call.request.queryParameters["hub.verify_token"]
                                    val challenge = call.request.queryParameters["hub.challenge"]
                                    if (mode == "subscribe" && token == VERIFY_TOKEN) {
                                        call.respondText(challenge ?: "")
                                    } else {
                                        call.respond(io.ktor.http.HttpStatusCode.Forbidden)
                                    }
                                }
                                post {
                                    val body = call.receiveText()
                                    serviceScope.launch {
                                        val waText = "Tin nhắn test từ WhatsApp"
                                        val waPhone = "wa_phone_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = waText, username = waPhone, chatMode = "COMBINED"))
                                        findPlugin("whatsapp")?.execute("send_message", mapOf("phone" to waPhone, "message" to reply.responseText))
                                    }
                                    call.respond(io.ktor.http.HttpStatusCode.OK)
                                }
                            }

                            // ─── CỔNG 5: TELEGRAM BOT ───
                            route("/telegram") {
                                post {
                                    val body = call.receiveText()
                                    serviceScope.launch {
                                        val tgText = "Tin nhắn test từ Telegram"
                                        val tgChatId = "tg_chat_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = tgText, username = tgChatId, chatMode = "COMBINED"))
                                        findPlugin("telegram")?.execute("send_message", mapOf("chat_id" to tgChatId, "message" to reply.responseText))
                                    }
                                    call.respond(io.ktor.http.HttpStatusCode.OK)
                                }
                            }
                        }
                    }
                }
                server?.start(wait = true)
                logger.i("WebhookGateway", "Ktor Gateway Server khởi chạy tại cổng 8080")
            } catch (e: Exception) {
                logger.e("WebhookGateway", "Khởi chạy Ktor Gateway Server thất bại", e)
            }
        }
    }

    private fun getOrCreateSshKey(): String {
        val keyFile = File(filesDir, "id_rsa")
        if (!keyFile.exists()) {
            try {
                val jsch = JSch()
                val keyPair = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.RSA, 2048)
                keyPair.writePrivateKey(keyFile.absolutePath)
                keyPair.dispose()
                logger.i("WebhookGateway", "🔑 Đã khởi tạo cặp khóa bảo mật SSH cố định trên máy.")
            } catch (e: Exception) {
                logger.e("WebhookGateway", "Lỗi tạo khóa bảo mật SSH", e)
            }
        }
        return keyFile.absolutePath
    }

    private fun startEmbeddedSSHTunnel() {
        serviceScope.launch(Dispatchers.IO) {
            val privateKeyPath = getOrCreateSshKey()
            var isOfflineLogged = false // Cờ đánh dấu để chỉ ghi log mất mạng 1 lần duy nhất

            while (isActive) {
                // KIỂM TRA MẠNG: Nếu mất kết nối Internet, tạm dừng kết nối hầm để tránh spam log lỗi và notification
                if (!isNetworkAvailable()) {
                    if (!isOfflineLogged) {
                        logger.i("WebhookGateway", "⚠️ Không có kết nối Internet. Tạm dừng kết nối hầm...")
                        updateNotification("Không có Internet, đang chờ kết nối lại...")
                        isOfflineLogged = true
                    }
                    delay(5000) // Đợi 5 giây rồi kiểm tra lại một cách im lặng
                    continue
                }

                // Khi có mạng trở lại, reset trạng thái ghi nhận offline
                if (isOfflineLogged) {
                    isOfflineLogged = false
                    logger.i("WebhookGateway", "📶 Đã có kết nối Internet trở lại. Bắt đầu kết nối hầm...")
                }

                try {
                    logger.i("WebhookGateway", "🔑 Đang kết nối hầm bảo mật SSH cố định đa kênh (serveo.net)...")
                    val jsch = JSch()
                    jsch.addIdentity(privateKeyPath)

                    sshSession = jsch.getSession("current_user", "serveo.net", 22)
                    val config = Properties()
                    config["StrictHostKeyChecking"] = "no"
                    sshSession?.setConfig(config)

                    sshSession?.connect(15000)
                    
                    // Kích hoạt chuyển tiếp cổng ẩn danh để tránh bị bắt đăng ký key
                    sshSession?.setPortForwardingR(80, "127.0.0.1", 8080)
                    logger.i("WebhookGateway", "🟢 Đã gửi yêu cầu đăng ký hầm đa kênh, đang chờ Serveo cấp domain...")

                    val channel = sshSession?.openChannel("shell")
                    val inputStream = channel?.inputStream
                    channel?.connect()

                    val reader = BufferedReader(InputStreamReader(inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logger.d("WebhookGateway", "Serveo: $line")

                        // Kiểm tra dòng chứa thông tin chuyển tiếp từ Serveo
                        if (line?.contains("Forwarding HTTP traffic from") == true) {
                            // Regex hỗ trợ cả đuôi .serveo.net và .serveousercontent.com
                            val cleanLink = Regex("https?://[a-zA-Z0-9.-]+\\.(serveo\\.net|serveousercontent\\.com)").find(line!!)?.value
                            if (cleanLink != null) {
                                val webhookBase = "$cleanLink/webhook"
                                logger.i("WebhookGateway", "🌍 URL WEBHOOK ĐA KÊNH CỦA BẠN: $webhookBase")

                                // Cập nhật notification hiển thị domain thật lên thanh trạng thái
                                updateNotification("Đã kết nối: $cleanLink")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("WebhookGateway", "❌ Lỗi kết nối hầm: ${e.message}. Thử lại sau 15 giây...", e)
                    updateNotification("Mất kết nối, đang thử lại...")
                    delay(15000) // Đợi 15 giây trước khi thử kết nối lại
                } finally {
                    sshSession?.disconnect()
                }
            }
        }
    }

    // ✅ ĐÃ SỬA: Chuyển sang IMPORTANCE_DEFAULT để tránh bị hệ điều hành tự động ẩn/bỏ vào mục im lặng của các dòng máy Xiaomi/Oppo/Samsung
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Webhook Gateway Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 5000)
        sshSession?.disconnect()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        serviceScope.cancel()
        logger.i("WebhookGateway", "Dịch vụ đã tắt hoàn toàn.")
    }
}