package com.aichatvn.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var sshSession: Session? = null

    companion object {
        private const val CHANNEL_ID = "WebhookGatewayServiceChannel"
        private const val NOTIFICATION_ID = 1002
        private const val VERIFY_TOKEN = "YOUR_VERIFY_TOKEN_HERE" // Verify Token chung cho Meta (FB/IG) và WhatsApp
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Giữ CPU chạy ổn định khi tắt màn hình điện thoại
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AIChatVN2::WebhookWakeLock").apply {
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

    // ✅ Tạo builder dùng chung, để cả lúc khởi động và lúc cập nhật đều đồng nhất cấu hình im lặng
    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Omnichannel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Chỉ rung/kêu đúng 1 lần lúc tạo mới, các lần update sau im lặng
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ✅ Cập nhật nội dung notification khi có domain thật (không tạo thông báo mới, không rung/kêu lại)
    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
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
                        // Nhóm tất cả các router dưới tiền tố chung /webhook
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
                                        // TODO: Phân tách 'fbText' và 'fbPsid' từ JSON thô
                                        val fbText = "Tin nhắn test từ FB"
                                        val fbPsid = "fb_sender_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = fbText, username = fbPsid, chatMode = "COMBINED"))

                                        // Gọi plugin Facebook để gửi tin nhắn đi
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
                                        // TODO: Phân tách 'igText' và 'igPsid'
                                        val igText = "Tin nhắn test từ Instagram"
                                        val igPsid = "ig_sender_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = igText, username = igPsid, chatMode = "COMBINED"))

                                        // Instagram dùng chung hạ tầng Graph API với Facebook
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
                                        // TODO: Phân tách 'zaloText' và 'zaloUserId' từ JSON thô của Zalo
                                        val zaloText = "Tin nhắn test từ Zalo"
                                        val zaloUserId = "zalo_user_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = zaloText, username = zaloUserId, chatMode = "COMBINED"))

                                        // Gọi Zalo Skill tự nhận diện động sau này khi bạn khởi tạo file
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
                                        // TODO: Phân tách 'waText' và 'waPhone'
                                        val waText = "Tin nhắn test từ WhatsApp"
                                        val waPhone = "wa_phone_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = waText, username = waPhone, chatMode = "COMBINED"))

                                        // Gọi WhatsApp Skill tự nhận diện động sau này
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
                                        // TODO: Phân tách 'tgText' và 'tgChatId'
                                        val tgText = "Tin nhắn test từ Telegram"
                                        val tgChatId = "tg_chat_id_placeholder"

                                        val reply = agentKernel.chat(ChatRequest(message = tgText, username = tgChatId, chatMode = "COMBINED"))

                                        // Gọi Telegram Skill tự nhận diện động sau này
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

            while (isActive) {
                try {
                    logger.i("WebhookGateway", "🔑 Đang kết nối hầm bảo mật SSH cố định đa kênh (serveo.net)...")
                    val jsch = JSch()
                    jsch.addIdentity(privateKeyPath)

                    sshSession = jsch.getSession("current_user", "serveo.net", 22)
                    val config = java.util.Properties()
                    config["StrictHostKeyChecking"] = "no"
                    sshSession?.setConfig(config)

                    sshSession?.connect(15000)
                    // ✅ ĐÃ SỬA: KHÔNG chỉ định tên subdomain riêng nữa (gây đòi đăng ký key qua trình duyệt).
                    // Để Serveo tự cấp subdomain theo IP + username SSH — thường giữ nguyên giữa các lần kết nối lại.
                    sshSession?.setPortForwardingR(80, "127.0.0.1", 8080)
                    logger.i("WebhookGateway", "🟢 Đã gửi yêu cầu đăng ký hầm đa kênh, đang chờ Serveo cấp domain...")

                    val channel = sshSession?.openChannel("shell")
                    val inputStream = channel?.inputStream
                    channel?.connect()

                    val reader = BufferedReader(InputStreamReader(inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logger.d("WebhookGateway", "Serveo: $line")

                        // ✅ ĐÃ SỬA: Chỉ trích domain từ đúng dòng "Forwarding HTTP traffic from",
                        // KHÔNG bắt nhầm link đăng ký key (console.serveo.net/ssh/keys?add=...)
                        if (line?.contains("Forwarding HTTP traffic from") == true) {
                            val cleanLink = Regex("https?://[a-zA-Z0-9.-]+\\.serveo\\.net").find(line!!)?.value
                            if (cleanLink != null) {
                                val webhookBase = "$cleanLink/webhook"
                                logger.i("WebhookGateway", "🌍 URL WEBHOOK ĐA KÊNH CỦA BẠN: $webhookBase")

                                // ✅ Cập nhật notification hiển thị domain thật, không tạo thông báo mới
                                updateNotification("Đã kết nối: $cleanLink")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("WebhookGateway", "❌ Lỗi kết nối hầm: ${e.message}. Thử lại sau 10 giây...", e)
                    updateNotification("Mất kết nối, đang thử lại...")
                    delay(10000)
                } finally {
                    sshSession?.disconnect()
                }
            }
        }
    }

    // ✅ ĐÃ SỬA: IMPORTANCE_LOW thay vì DEFAULT — im lặng hoàn toàn, không rung/kêu khi mạng chập chờn
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Webhook Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
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
