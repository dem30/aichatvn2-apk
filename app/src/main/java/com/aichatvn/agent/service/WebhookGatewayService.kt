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
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.scheduler.CronParser // ĐÃ THÊM: Import lớp phân tích cron độc lập
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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiClient = okHttpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
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
        startTelegramLongPolling()   
        startHeartbeatLoop()         
        
        // ✅ THÊM MỚI: Kích hoạt vòng lặp quét lịch độc lập từng phút
        startScheduleLoop()
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ (API 34+)
            // Đồng bộ chính xác kiểu SERVICE_TYPE_SPECIAL_USE khớp với Manifest
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Đối với các phiên bản Android cũ hơn, khởi chạy tiền cảnh tiêu chuẩn không cần truyền tham số Type đặc biệt
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
                val pageIds = dbPages.map { it.id }

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
                                                }
                                           } else {
    val platform = jsonObj.optString("platform", "website") // ✅ Đổi từ "web" thành "website"
    val senderId = jsonObj.optString("senderId", "external_user")
    val text = jsonObj.optString("text", "")
    val incomingPageId = jsonObj.optString("pageId", "")
                                              // do widget Website gửi trực tiếp — Render gateway forward nguyên 2 field này.
                                                val incomingImageUrl = jsonObj.optString("imageUrl", "").takeIf { it.isNotBlank() }
                                                val incomingImageBase64Raw = jsonObj.optString("imageBase64", "").takeIf { it.isNotBlank() }

                                                // ✅ SỬA: trước đây chỉ xử lý khi có text — tin nhắn CHỈ có ảnh (không kèm
                                                // caption) bị bỏ qua hoàn toàn. Giờ xử lý khi có text HOẶC có ảnh.
                                                if (text.isNotBlank() || incomingImageUrl != null || incomingImageBase64Raw != null) {
                                                    val unifiedUsername = "${platform}_$senderId"

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

                                                    // ✅ MỚI: Tải ảnh về máy (nếu là URL của Meta CDN) rồi mã hoá base64 để
                                                    // truyền tiếp cho AgentKernel (Vision Plugin dùng imageBase64 để phân tích).
                                                    // Website đã gửi sẵn base64 nên dùng thẳng, không cần tải lại.
                                                    val resolvedImageBase64 = incomingImageBase64Raw
                                                        ?: incomingImageUrl?.let { downloadImageAsBase64(it) }

                                                    val setting = withContext(Dispatchers.IO) {
                                                        database.cameraDao().getCustomerSetting(senderId)
                                                    }
                                                    val isBotEnabled = setting?.smartMode != 0

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
                                                            // ✅ MỚI: nếu bot/plugin trả kèm ảnh (vd. CameraSkill quét camera),
                                                            // đọc bytes cục bộ để gửi kèm ra đúng kênh.
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
                                                        // ✅ ĐÃ SỬA: trước đây gọi saveExternalUserMessage() chỉ với (text, unifiedUsername) —
                                                        // resolvedImageBase64/incomingImageUrl đã tải/giải mã xong ở trên nhưng KHÔNG được
                                                        // truyền vào, nên ảnh khách gửi tới khi Admin đang ở chế độ Người Trực bị rớt mất
                                                        // hoàn toàn, chỉ còn lại tin nhắn text rỗng (hoặc mất hẳn nếu text cũng rỗng).
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
                                    // ✅ MỚI: Telegram gửi ảnh dưới dạng mảng "photo" gồm nhiều kích cỡ (file_id
                                    // khác nhau) — trước đây hoàn toàn không được đọc, chỉ "text" mới được xử lý.
                                    // Lấy phần tử CUỐI (kích thước lớn nhất) trong mảng.
                                    val photoArray = message.optJSONArray("photo")
                                    val largestPhotoFileId = if (photoArray != null && photoArray.length() > 0) {
                                        photoArray.getJSONObject(photoArray.length() - 1).optString("file_id", "")
                                    } else null
                                    val caption = message.optString("caption", "")

                                    if (text.isNotBlank() || !largestPhotoFileId.isNullOrEmpty()) {
                                        val effectiveText = if (text.isNotBlank()) text else caption
                                        logger.i("TelegramPoll", "📥 Nhận tin nhắn Telegram mới: '$effectiveText' từ ChatId: $chatId (ảnh: ${!largestPhotoFileId.isNullOrEmpty()})")
                                        val unifiedUsername = "telegram_$chatId"

                                        val setting = withContext(Dispatchers.IO) {
                                            database.cameraDao().getCustomerSetting(chatId)
                                        }
                                        val isBotEnabled = setting?.smartMode != 0

                                        serviceScope.launch {
                                            // ✅ MỚI: Tải ảnh từ Telegram (getFile -> URL file thật -> tải bytes -> base64)
                                            val imageBase64 = largestPhotoFileId?.let { downloadTelegramPhotoAsBase64(botToken, it) }

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
                                                // ✅ ĐÃ SỬA: cùng lỗi như nhánh Website/FB — imageBase64 đã tải xong ở trên
                                                // (downloadTelegramPhotoAsBase64) nhưng không được truyền vào, khiến ảnh khách
                                                // gửi qua Telegram lúc Admin đang Người Trực bị rớt, chỉ còn tin nhắn rỗng.
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
                } catch (e: Exception) {
                    logger.e("TelegramPoll", "Lỗi thăm dò tin nhắn Telegram: ${e.message}")
                    delay(10000)
                }
            }
        }
    }

    // ✅ MỚI: nhận thêm imageBase64 tuỳ chọn để đẩy kèm ảnh vào hàng đợi SSE của widget Website.
    private suspend fun sendWebsiteReply(gatewayUrl: String, gatewayToken: String, senderId: String, message: String, imageBase64: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", message)
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

    // ✅ MỚI: Tải 1 ảnh từ URL công khai (vd. Facebook CDN) về bộ nhớ rồi mã hoá base64,
    // để đưa vào ChatRequest.imageBase64 cho Vision Plugin phân tích. Trả về null nếu lỗi bất kỳ
    // (mạng lỗi, ảnh quá lớn...) — không được để lỗi tải ảnh làm rớt luôn cả phần text đi kèm.
    // ✅ ĐÃ SỬA: trước đây thất bại (timeout/mạng chập chờn/HTTP lỗi) là bỏ luôn ngay lần đầu,
    // không có retry — trong khi các hàm gửi đi (safe_post_request_async bên Gateway Python)
    // đều có retry 3 lần + backoff. Đây là nguyên nhân ảnh khách gửi "lúc được lúc không" ở
    // chế độ bot tự động: mạng chập chờn thoáng qua là ảnh rớt âm thầm, bot vẫn trả lời (chỉ
    // dựa trên text nếu có) như không có gì xảy ra.
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

    // ✅ MỚI: Đọc 1 file ảnh đã lưu cục bộ trên máy (vd. context.filesDir/chat_images/*.jpg do
    // CameraSkill tạo ra) rồi mã hoá base64 để gửi ra kênh ngoài. Trả về null nếu file không tồn
    // tại hoặc đọc lỗi — không làm crash toàn bộ luồng trả lời.
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

    // ✅ MỚI: Telegram chỉ gửi "file_id" trong webhook/getUpdates, phải gọi thêm "getFile" để lấy
    // "file_path" thật, rồi mới tải được bytes từ https://api.telegram.org/file/bot<token>/<file_path>.
    // ✅ ĐÃ SỬA: thêm retry cho CẢ 2 bước (getFile lấy file_path, rồi tải bytes thật) — trước đây
    // chỉ cần 1 trong 2 request bị trễ/lỗi thoáng qua là mất ảnh ngay lập tức, không có cơ hội
    // thử lại, gây hiện tượng ảnh Telegram "lúc được lúc không" ở chế độ bot tự động.
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

    // ✅ MỚI: Gửi ảnh (kèm caption tuỳ chọn) trực tiếp cho Telegram bằng multipart/form-data —
    // "sendPhoto" nhận file nhị phân trực tiếp, không cần ảnh có URL công khai. Gọi thẳng
    // api.telegram.org, KHÔNG đi qua Render Gateway (giống sendTelegramMessage hiện có).
    private suspend fun sendTelegramPhoto(token: String, chatId: String, imageBase64: String, caption: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                val captionSafe = caption.take(1024) // Giới hạn caption của Telegram Bot API

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

    // ✅ THÊM MỚI: Luồng quét và thực thi lịch trình cục bộ (Thay thế cho WorkManager)
    private fun startScheduleLoop() {
        serviceScope.launch(Dispatchers.IO) {
            delay(5000) // Đợi database và cơ chế tiêm phụ thuộc ổn định
            while (isActive) {
                try {
                    val schedules = database.scheduleDao().getAllSchedules()
                    val now = System.currentTimeMillis()

                    for (schedule in schedules) {
                        if (schedule.enabled != 1) continue

                        // Tận dụng CronParser đã sửa bug logic ở File 1
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

                                val result = plugin.execute(schedule.action, params)
                                logger.i("ScheduleLoop", "✅ Thực thi lịch trình thành công: ${schedule.pluginId}.${schedule.action} -> $result")
                            } catch (e: Exception) {
                                logger.e("ScheduleLoop", "❌ Lỗi thực thi action: ${schedule.pluginId}.${schedule.action}: ${e.message}")
                            }
                            database.scheduleDao().updateLastRun(schedule.id, now)
                        }
                    }
                } catch (e: Exception) {
                    logger.e("ScheduleLoop", "⚠️ Gặp lỗi trong vòng lặp quét lịch trình: ${e.message}")
                }
                delay(60_000L) // Quét database mỗi 60 giây một lần với WakeLock bảo vệ
            }
        }
    }

    // ✅ THÊM MỚI: Hàm chuyển đổi đệ quy an toàn JSON sang Map thuần tuý cho các Action
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