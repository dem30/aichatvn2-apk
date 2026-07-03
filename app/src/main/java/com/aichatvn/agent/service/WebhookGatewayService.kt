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
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@AndroidEntryPoint
class WebhookGatewayService : Service() {

    @Inject
    lateinit var agentKernel: AgentKernel

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var configProvider: AppConfigProvider

    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: NettyApplicationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastNotificationText = ""

    companion object {
        private const val CHANNEL_ID = "WebhookGatewayServiceChannel"
        private const val NOTIFICATION_ID = 1002
        private const val VERIFY_TOKEN = "YOUR_VERIFY_TOKEN_HERE"
    }

    override fun onCreate() {
        super.onCreate()

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
        
        startCloudGatewaySSE()       // Lắng nghe kết nối hầm SSE từ Hugging Face
        startTelegramLongPolling()   // Tự nhận tin nhắn Telegram trực tiếp (Long Polling)
        startHeartbeatLoop()         // Giữ thức Hugging Face Space không bị ngủ đông
    }

    private fun startForegroundService() {
        val notification = buildNotification("Hệ thống Webhook Gateway đang hoạt động...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Omnichannel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
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

    // ===== 🔌 KÊNH 1: NHẬN TIN NHẮN TỪ HUGGING FACE QUA SSE =====
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
                var connection: HttpURLConnection? = null

                try {
                    logger.i("CloudGateway", "🔌 Đang thiết lập đường ống SSE đến Hugging Face...")
                    updateNotification("Đang kết nối Cloud Gateway...")
                    
                    val url = URL("$gatewayUrl/stream/$gatewayToken")
                    connection = url.openConnection() as HttpURLConnection
                    connection.setRequestProperty("Accept", "text/event-stream")
                    connection.readTimeout = 0 // Giữ kết nối mở vô hạn không timeout
                    
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    var line: String? = null // ✅ ĐÃ KHỞI TẠO GIÁ TRỊ ĐỂ KHẮC PHỤC LỖI BIÊN DỊCH GRADLE
                    
                    logger.i("CloudGateway", "🟢 Đường ống SSE đã mở! Sẵn sàng nhận Webhook.")
                    updateNotification("Cổng đám mây: Đã kết nối")

                    while (isActive && reader.readLine().also { line = it } != null) {
                        val trimmedLine = line?.trim() ?: ""
                        if (trimmedLine.startsWith("data:")) {
                            val rawData = trimmedLine.substring(5).trim()
                            if (rawData.isNotEmpty()) {
                                logger.i("CloudGateway", "📥 Nhận dữ liệu Webhook mới từ Cloud: $rawData")
                                
                                serviceScope.launch {
                                    try {
                                        val jsonObj = org.json.JSONObject(rawData)
                                        val platform = jsonObj.optString("platform", "web")
                                        val senderId = jsonObj.optString("senderId", "external_user")
                                        val text = jsonObj.optString("text", "")

                                        if (text.isNotBlank()) {
                                            // Gửi tin nhắn thô vào AgentKernel để xử lý cục bộ
                                            val reply = agentKernel.chat(
                                                ChatRequest(message = text, username = senderId, chatMode = "COMBINED")
                                            )
                                            logger.i("CloudGateway", "🧠 Phản hồi của Agent: ${reply.responseText}")

                                            // Tự động điều hướng và gọi đúng Plugin Skill để gửi phản hồi đi
                                            when (platform) {
                                                "facebook" -> {
                                                    findPlugin("facebook")?.execute(
                                                        "send_messenger",
                                                        mapOf("recipient_id" to senderId, "message" to reply.responseText)
                                                    )
                                                }
                                                "instagram" -> {
                                                    findPlugin("instagram")?.execute(
                                                        "send_messenger",
                                                        mapOf("recipient_id" to senderId, "message" to reply.responseText)
                                                    )
                                                }
                                                "zalo" -> {
                                                    findPlugin("zalo")?.execute(
                                                        "send_message",
                                                        mapOf("recipient_id" to senderId, "message" to reply.responseText)
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logger.e("CloudGateway", "Lỗi giải mã gói tin webhook: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("CloudGateway", "❌ Mất đường ống SSE: ${e.message}. Đang thử kết nối lại sau 15 giây...")
                    updateNotification("Mất kết nối Gateway, đang kết nối lại...")
                    delay(15000)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    // ===== 🔌 KÊNH 2: TỰ NHẬN TIN NHẮN TELEGRAM (LONG POLLING) =====
    private fun startTelegramLongPolling() {
        serviceScope.launch(Dispatchers.IO) {
            var lastUpdateId = 0
            delay(3000)

            while (isActive) {
                val botToken = configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim()

                if (botToken.isBlank() || !isNetworkAvailable()) {
                    delay(10000)
                    continue
                }

                var conn: HttpURLConnection? = null
                try {
                    val url = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=15")
                    conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.readTimeout = 20000
                    
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
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

                            if (text.isNotBlank()) {
                                logger.i("TelegramPoll", "📥 Nhận tin nhắn Telegram mới: '$text' từ ChatId: $chatId")
                                
                                serviceScope.launch {
                                    val reply = agentKernel.chat(
                                        ChatRequest(message = text, username = chatId, chatMode = "COMBINED")
                                    )
                                    sendTelegramMessage(botToken, chatId, reply.responseText)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e("TelegramPoll", "Lỗi thăm dò tin nhắn Telegram: ${e.message}")
                    delay(10000)
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    private suspend fun sendTelegramMessage(token: String, chatId: String, text: String) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Exception) {
                logger.e("TelegramPoll", "Gửi phản hồi về Telegram thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ===== 🔌 KÊNH 3: NHỊP TIM GIỮ THỨC HUGGING FACE SPACE (HEARTBEAT LOOP) =====
    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(10000)
            
            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                if (gatewayUrl.isNotBlank() && isNetworkAvailable()) {
                    var conn: HttpURLConnection? = null
                    try {
                        logger.d("Heartbeat", "💓 Đang gửi nhịp tim giữ thức Hugging Face Space...")
                        val url = URL("$gatewayUrl/health")
                        conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        
                        val responseCode = conn.responseCode
                        if (responseCode == 200) {
                            logger.d("Heartbeat", "✅ Nhịp tim phản hồi tốt. Hugging Face Space đang thức!")
                        }
                    } catch (e: Exception) {
                        logger.e("Heartbeat", "❌ Gửi nhịp tim giữ thức thất bại: ${e.message}")
                    } finally {
                        conn?.disconnect()
                    }
                }
                delay(15 * 60 * 1000L) // Gửi nhịp tim định kỳ mỗi 15 phút
            }
        }
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
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        serviceScope.cancel()
        logger.i("WebhookGateway", "Dịch vụ đã tắt hoàn toàn.")
    }
}