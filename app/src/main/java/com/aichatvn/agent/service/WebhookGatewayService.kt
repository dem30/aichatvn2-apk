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

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.FacebookPageEntity
import com.aichatvn.agent.skills.ChatSkill
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
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

@AndroidEntryPoint
class WebhookGatewayService : Service() {

    @Inject
    lateinit var agentKernel: AgentKernel

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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText = ""

    // OkHttp Client chuyên dụng để tái sử dụng Connection Pool và Keep-Alive
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Giữ vô hạn cho luồng kéo tin SSE không bị ngắt
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Client OkHttp cho các tác vụ API thông thường (Register, Heartbeat, Send)
    private val apiClient = okHttpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Client OkHttp chuyên dụng cho Long Polling Telegram
    private val pollingClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CHANNEL_ID = "WebhookGatewayServiceChannel"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock() // ✅ ĐÃ SỬA: Giữ WakeLock an toàn ngay khi khởi tạo dịch vụ

        createNotificationChannel()
        startForegroundService()
        
        startCloudGatewaySSE()       // Lắng nghe kết nối hầm SSE từ Render
        startTelegramLongPolling()   // Tự nhận tin nhắn Telegram trực tiếp (Long Polling)
        startHeartbeatLoop()         // Giữ thức Render Gateway (Heartbeat)
    }

    // ✅ ĐÃ THÊM: Hàm bổ trợ quản lý WakeLock an toàn chống crash và tránh hao pin khi thoát dịch vụ
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

    private fun registerPageMappings(gatewayUrl: String, gatewayToken: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dbPages = database.facebookPageDao().getAllPages()
                val dbPageIds = dbPages.map { it.id }

                val igPageId = configProvider.getString(AppConfigDefaults.INSTAGRAM_PAGE_ID).trim()
                val pageIds = dbPageIds + listOfNotNull(igPageId.takeIf { it.isNotEmpty() })

                if (pageIds.isEmpty()) return@launch

                val jsonArray = org.json.JSONArray(pageIds)
                val bodyJson = org.json.JSONObject().apply {
                    put("token", gatewayToken)
                    put("pageIds", jsonArray)
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

    private fun registerWidgetKey(gatewayUrl: String, gatewayToken: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                var widgetKey = configProvider.getString(AppConfigDefaults.WEBSITE_WIDGET_KEY).trim()
                if (widgetKey.isEmpty()) {
                    widgetKey = java.util.UUID.randomUUID().toString().replace("-", "")
                    configProvider.set(AppConfigDefaults.WEBSITE_WIDGET_KEY, widgetKey)
                    logger.i("CloudGateway", "🔑 Đã tự sinh widget_key mới cho Website widget.")
                }

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

    // ===== 🔌 KÊNH 1: NHẬN TIN NHẮN TỪ RENDER QUA SSE =====
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

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            logger.w("CloudGateway", "⚠️ Kết nối SSE thất bại với mã lỗi HTTP: ${response.code}")
                            updateNotification("Lỗi kết nối SSE (${response.code})")
                            delay(5000)
                            return@use
                        }

                        // ✅ ĐÃ SỬA: Kiểm tra an toàn khác null để tránh lỗi sập NullPointerException trên response.body
                        val body = response.body ?: throw IOException("Mất nội dung phản hồi từ máy chủ (Empty response body)")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String? = null
                        
                        logger.i("CloudGateway", "🟢 Đường ống SSE đã mở! Sẵn sàng nhận Webhook.")
                        updateNotification("Cổng đám mây: Đã kết nối")

                        registerPageMappings(gatewayUrl, gatewayToken)
                        registerWidgetKey(gatewayUrl, gatewayToken)

                        while (isActive && reader.readLine().also { line = it } != null) {
                            val trimmedLine = line?.trim() ?: ""
                            if (trimmedLine.startsWith("data:")) {
                                val rawData = trimmedLine.substring(5).trim()
                                if (rawData.isNotEmpty()) {
                                    logger.i("CloudGateway", "📥 Nhận dữ liệu Webhook mới từ Cloud: $rawData")
                                    
                                    serviceScope.launch {
                                        try {
                                            val jsonObj = org.json.JSONObject(rawData)
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
                                                } else if (plat == "instagram") {
                                                    val tokenValue = jsonObj.optString("pageAccessToken", "")
                                                    val pageIdValue = jsonObj.optString("pageId", "")
                                                    if (tokenValue.isNotEmpty()) {
                                                        configProvider.set(AppConfigDefaults.INSTAGRAM_PAGE_ACCESS_TOKEN, tokenValue)
                                                        if (pageIdValue.isNotEmpty()) {
                                                            configProvider.set(AppConfigDefaults.INSTAGRAM_PAGE_ID, pageIdValue)
                                                        }
                                                        logger.i("CloudGateway", "🔑 Đã đồng bộ động Instagram Page Access Token thành công!")
                                                    }
                                                }
                                            } else {
                                                val platform = jsonObj.optString("platform", "web")
                                                val senderId = jsonObj.optString("senderId", "external_user")
                                                val text = jsonObj.optString("text", "")
                                                val incomingPageId = jsonObj.optString("pageId", "")

                                                if (text.isNotBlank()) {
                                                    val unifiedUsername = "${platform}_$senderId"
                                                    val setting = withContext(Dispatchers.IO) {
                                                        database.cameraDao().getCustomerSetting(senderId)
                                                    }
                                                    val isBotEnabled = setting?.smartMode != 0

                                                    if (isBotEnabled) {
                                                        val result = chatSkill.processQuery(
                                                            message = text,
                                                            username = unifiedUsername,
                                                            extraContext = "page_id:$incomingPageId"
                                                        )

                                                        if (result is AgentKernel.PluginResult.Success) {
                                                            val replyMap = result.data as? Map<*, *>
                                                            val replyText = (replyMap?.get("response") as? String) ?: ""
                                                            if (replyText.isNotEmpty()) {
                                                                when (platform) {
                                                                    "facebook" -> {
                                                                        findPlugin("facebook")?.execute(
                                                                            "send_messenger",
                                                                            mapOf(
                                                                                "recipient_id" to senderId,
                                                                                "message" to replyText,
                                                                                "page_id" to incomingPageId
                                                                            )
                                                                        )
                                                                    }
                                                                    "instagram" -> {
                                                                        findPlugin("instagram")?.execute(
                                                                            "send_messenger",
                                                                            mapOf("recipient_id" to senderId, "message" to replyText)
                                                                        )
                                                                    }
                                                                    "website" -> {
                                                                        sendWebsiteReply(gatewayUrl, gatewayToken, senderId, replyText)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        chatSkill.saveExternalUserMessage(text, unifiedUsername)
                                                        logger.i("CloudGateway", "👤 Khách hàng $unifiedUsername đang ở chế độ Người Trực. Bot không tự trả lời.")
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
                    }
                } catch (e: Exception) {
                    logger.e("CloudGateway", "❌ Mất đường ống SSE: ${e.message}. Đang thử kết nối lại sau 5 giây...")
                    updateNotification("Mất kết nối Gateway, đang kết nối lại...")
                    delay(5000)
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

                                    if (text.isNotBlank()) {
                                        logger.i("TelegramPoll", "📥 Nhận tin nhắn Telegram mới: '$text' từ ChatId: $chatId")
                                        val unifiedUsername = "telegram_$chatId"

                                        val setting = withContext(Dispatchers.IO) {
                                            database.cameraDao().getCustomerSetting(chatId)
                                        }
                                        val isBotEnabled = setting?.smartMode != 0

                                        serviceScope.launch {
                                            if (isBotEnabled) {
                                                val result = chatSkill.processQuery(
                                                    message = text,
                                                    username = unifiedUsername
                                                )
                                                if (result is AgentKernel.PluginResult.Success) {
                                                    val replyMap = result.data as? Map<*, *>
                                                    val replyText = (replyMap?.get("response") as? String) ?: ""
                                                    if (replyText.isNotEmpty()) {
                                                        sendTelegramMessage(botToken, chatId, replyText)
                                                    }
                                                }
                                            } else {
                                                chatSkill.saveExternalUserMessage(text, unifiedUsername)
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

    private suspend fun sendWebsiteReply(gatewayUrl: String, gatewayToken: String, senderId: String, message: String) {
        withContext(Dispatchers.IO) {
            try {
                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", message)
                }.toString()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$gatewayUrl/send/$gatewayToken")
                    .post(requestBody)
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("CloudGateway", "⚠️ Gửi phản hồi website thất bại, HTTP: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                logger.e("CloudGateway", "Gửi phản hồi cho khách Website thất bại: ${e.message}")
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

    // ===== 🔌 KÊNH 3: NHỊP TIM GIỮ THỨC RENDER GATEWAY (HEARTBEAT LOOP) =====
    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(10000)
            
            while (isActive) {
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                if (gatewayUrl.isNotBlank() && isNetworkAvailable()) {
                    try {
                        logger.d("Heartbeat", "💓 Đang gửi nhịp tim giữ thức Gateway...")
                        
                        val request = Request.Builder()
                            .url("$gatewayUrl/health")
                            .get()
                            .build()

                        apiClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                logger.d("Heartbeat", "✅ Nhịp tim phản hồi tốt. Gateway đang thức!")
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
        acquireWakeLock() // Củng cố khóa giữ thức CPU bất cứ khi nào service được kích hoạt chạy
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Cơ chế tự khởi chạy lại Service khi bị người dùng vuốt đóng ứng dụng khỏi đa nhiệm (Task Swiped)
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
        releaseWakeLock() // ✅ ĐÃ SỬA: Giải phóng khóa giữ thức CPU triệt để khi hủy dịch vụ
        serviceScope.cancel()
        logger.i("WebhookGateway", "Dịch vụ đã tắt hoàn toàn.")
    }
}