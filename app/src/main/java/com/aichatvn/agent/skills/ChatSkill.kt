package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatMode {
    GROQ,
    QA,
    COMBINED
}

@Singleton
class ChatSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(),
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "chat",
                description = "Trò chuyện với AI hoặc khách hàng ngoại tuyến",
                parameters = listOf(
                    PluginParameter("message", "string", "Tin nhắn", true),
                    PluginParameter("username", "string", "Tên người dùng hoặc ID khách hàng", true),
                    PluginParameter("extraContext", "string", "Ngữ cảnh bổ sung", false)
                )
            ),
            PluginAction(
                name = "clear_history",
                description = "Xóa lịch sử chat",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            )
        )
    )

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.COMBINED)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private var currentUsername: String = "default_user"
    private val messagesMutex = Mutex()

    companion object {
        private val PAST_QUERY_KEYWORDS = setOf(
            "mấy hôm trước", "hôm qua", "hôm nay", "gần đây", "vừa rồi", "lúc nãy",
            "lịch sử", "nhật ký", "hoạt động", "đã làm gì", "đã xảy ra",
            "có ai gọi", "có ai nhắn", "tin nhắn gì", "cuộc gọi", "bỏ lỡ", "chưa đọc",
            "mấy ngày nay", "tuần trước", "tuần này", "trước đó",
            // 💡 Bổ sung từ khóa mở rộng: đảm bảo câu hỏi nhắc thẳng đến camera/thiết bị/sự kiện cũng kích hoạt buildMemoryContext
            "sự kiện", "biến cố", "cảnh báo", "báo cáo", "camera", "thiết bị"
        )

        private const val DEFAULT_MEMORY_LOOKBACK_DAYS = 3
    }

    private fun isPastMemoryQuery(message: String): Boolean {
        val norm = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(message)
        return PAST_QUERY_KEYWORDS.any { keyword ->
            val normKeyword = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(keyword)
            norm.contains(normKeyword)
        }
    }

    // ⚙️ Sửa đổi chữ ký hàm để nhận diện thêm userMessage từ khung chat gửi xuống,
    // phục vụ việc trích xuất thực thể camera/thiết bị/kênh và lọc log theo đúng đối tượng được hỏi
    private suspend fun buildMemoryContext(username: String, userMessage: String): String = withContext(Dispatchers.IO) {
        // ✅ SỬA LỖI #1: Chốt chặn an ninh chặn đứng rò rỉ dữ liệu riêng tư smarthome của chủ nhà cho khách ngoại tuyến
        if (username != "default_user") return@withContext ""

        try {
            val now = System.currentTimeMillis()
            val since = now - (DEFAULT_MEMORY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L)

            // Đọc giới hạn số lượng dòng log tối đa cấu hình từ DB (chống Token Bloat, thay cho hard-code take(200))
            val maxLogs = configProvider.getInt(AppConfigDefaults.GLOBAL_MAX_MEMORY_LOGS, 30)

            // Truy xuất danh sách camera/thiết bị đang hoạt động để đối chiếu thực thể trong câu hỏi
            val activeCameras = database.cameraDao().getActiveCameras()
            val activeDevices = database.tuyaDeviceDao().getAllDevices()
            val normalizedMsg = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userMessage.lowercase())

            // 🎯 Bước 1: Phân tích trích xuất thực thể xem câu hỏi có nhắc đích danh camera/thiết bị nào không
            val matchedCamera = activeCameras.find { cam ->
                val normCamName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(cam.customername.lowercase())
                normalizedMsg.contains(normCamName) || normalizedMsg.contains(cam.id.lowercase())
            }
            val matchedDevice = activeDevices.find { dev ->
                val normDevName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(dev.name.lowercase())
                normalizedMsg.contains(normDevName) || normalizedMsg.contains(dev.id.lowercase())
            }

            // ✅ MỚI: Các kênh chat đa nền tảng (Facebook/Telegram/Website) cũng là một "đối tượng" xứng đáng
            // được lọc riêng, tránh trộn lẫn tin nhắn của kênh này với kênh khác khi Admin hỏi đích danh.
            // Chỉ áp dụng khi KHÔNG khớp camera/thiết bị, vì camera/thiết bị là ưu tiên cao hơn (cụ thể hơn).
            val platformKeywords = mapOf(
                "facebook" to listOf("facebook", "fb", "fanpage"),
                "telegram" to listOf("telegram"),
                "website" to listOf("website", "trang web", "widget web")
            )
            val matchedPlatform = if (matchedCamera == null && matchedDevice == null) {
                platformKeywords.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key
            } else null

            // 🎯 Bước 2: Truy vấn có bộ lọc phân vùng để tránh rò rỉ ngữ cảnh chéo giữa các camera/thiết bị/kênh
            val rawLogs = database.eventLogDao().getLogsInTimeframe(since, now)
            val filteredLogs = when {
                matchedCamera != null -> rawLogs.filter {
                    it.sourceId == matchedCamera.id || it.summary.contains(matchedCamera.customername, ignoreCase = true)
                }
                matchedDevice != null -> rawLogs.filter {
                    it.sourceId == matchedDevice.id || it.summary.contains(matchedDevice.name, ignoreCase = true)
                }
                // Hỏi đích danh 1 kênh đa nền tảng: lấy đúng log incoming_message của kênh đó (kể cả đã đọc,
                // vì đây là truy vấn kiểm toán lịch sử, không chỉ riêng tin chưa đọc)
                matchedPlatform != null -> rawLogs.filter { it.source == matchedPlatform }
                // Hỏi chung chung: chỉ lấy các biến động quan trọng, bỏ bớt log rác hệ thống
                else -> rawLogs.filter { it.eventType in setOf("person_detected", "state_change") }
            }

            val dateFmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault())

            // 🎯 Bước 3: Áp dụng giới hạn động thay vì take(200) cứng
            // Chỉ loại incoming_message khi KHÔNG hỏi đích danh 1 kênh (tránh trùng với unreadLines phía dưới,
            // nhưng khi hỏi đích danh kênh thì vẫn cần incoming_message để trả lời được lịch sử tin nhắn kênh đó)
            val activityLines = filteredLogs
                .sortedBy { it.timestamp }
                .filter { matchedPlatform != null || it.eventType != "incoming_message" }
                .take(maxLogs)
                .map { log -> "${dateFmt.format(java.util.Date(log.timestamp))}: ${log.summary}" }

            val unreadLines = try {
                database.chatMessageDao().getAllMessagesRaw(500)
                    .filter { it.role == "user" && !it.isRead && it.username != "default_user" }
                    // Nếu Admin hỏi đích danh 1 kênh, chỉ lấy tin chưa đọc của đúng kênh đó
                    .filter { matchedPlatform == null || it.username.substringBefore("_") == matchedPlatform }
                    .sortedByDescending { it.timestamp }
                    .take(10) // Tối ưu hóa từ 30 xuống 10 dòng chưa đọc để giảm token
                    .map { msg ->
                        val platform = msg.username.substringBefore("_")
                        val rawId = msg.username.substringAfter("_")
                        "${dateFmt.format(java.util.Date(msg.timestamp))}: [$platform] $rawId nhắn: \"${msg.content.take(80)}\""
                    }
            } catch (e: Exception) {
                emptyList()
            }

            // ✅ MỚI: Tra cứu World State (Bản sao số) — trạng thái SỐNG hiện tại, khác với event_logs (lịch sử
            // biến động). Trước đây buildMemoryContext không hề đọc world_state nên AI không có cách nào trả lời
            // đúng "đèn đang bật hay tắt" — chỉ có lịch sử bật/tắt trong quá khứ, không có trạng thái tức thời.
            // Chỉ tra khi khớp đích danh 1 camera/thiết bị cụ thể (world_state không áp dụng cho kênh chat).
            val worldState = when {
                matchedCamera != null -> database.worldStateDao().getState("camera", matchedCamera.id)
                matchedDevice != null -> database.worldStateDao().getState("tuya", matchedDevice.id)
                else -> null
            }

            if (activityLines.isEmpty() && unreadLines.isEmpty() && worldState == null) return@withContext ""

            buildString {
                append("<system_memory_context>\n")
                if (matchedCamera != null) {
                    append("--- Nhật ký hoạt động Camera ${matchedCamera.customername} ($DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất) ---\n")
                } else if (matchedDevice != null) {
                    append("--- Nhật ký hoạt động Thiết bị ${matchedDevice.name} ($DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất) ---\n")
                } else if (matchedPlatform != null) {
                    append("--- Nhật ký hoạt động kênh $matchedPlatform ($DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất) ---\n")
                } else {
                    append("--- Nhật ký hoạt động tổng hợp ($DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất) ---\n")
                }
                if (worldState != null) {
                    append("--- Trạng thái SỐNG hiện tại (Bản sao số, cập nhật lúc ${dateFmt.format(java.util.Date(worldState.updatedAt))}) ---\n")
                    append(worldState.attributesJson)
                    append("\n")
                }
                if (activityLines.isNotEmpty()) {
                    append(activityLines.joinToString("\n"))
                    append("\n")
                }
                if (unreadLines.isNotEmpty()) {
                    append("--- Tin nhắn khách hàng chưa đọc ---\n")
                    append(unreadLines.joinToString("\n"))
                    append("\n")
                }
                append("Hãy sử dụng dữ liệu chính xác này để trả lời câu hỏi của người dùng. Tuyệt đối không tự bịa đặt thông tin không có trong nhật ký.\n")
                append("</system_memory_context>")
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "buildMemoryContext error: ${e.message}", e)
            ""
        }
    }

    override suspend fun initialize() {
        reloadMessages(currentUsername)
    }

    override suspend fun shutdown() {}

    private suspend fun reloadMessages(username: String) {
        val loaded = withContext(Dispatchers.IO) {
            database.chatMessageDao().getMessages(username, 500)
        }
        _messages.value = loaded
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "chat" -> handleChat(params)
            "clear_history" -> handleClearHistory(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleChat(params: Map<String, Any>): PluginResult {
        val message = params["message"] as? String ?: return PluginResult.Failure("Thiếu tin nhắn")
        val username = params["username"] as? String ?: "default_user"
        val extraContext = params["extraContext"] as? String ?: ""
        return processQuery(message, extraContext, username, isManual = true) 
    }

    private suspend fun handleClearHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        return clearHistory(username)
    }

    suspend fun saveExternalUserMessage(message: String, username: String, fileUrl: String? = null, imageBase64: String? = null) {
        val resolvedFileUrl = if (!imageBase64.isNullOrEmpty()) {
            saveIncomingChatImage(imageBase64)
        } else {
            fileUrl
        }
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionToken = "session_$username",
            username = username,
            content = message,
            role = "user", 
            type = if (resolvedFileUrl != null) "image" else "text",
            fileUrl = resolvedFileUrl,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        withContext(Dispatchers.IO) {
            database.chatMessageDao().insertMessage(userMessage)
            
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = userMessage.timestamp,
                    source = username.substringBefore("_"),
                    sourceId = username.substringAfter("_"),
                    eventType = "incoming_message",
                    value = message.take(200),
                    summary = "Tin nhắn mới từ ${username.substringBefore("_")} (${username.substringAfter("_")}): \"${message.take(80)}\""
                )
            )
        }
        messagesMutex.withLock {
            if (currentUsername == username) {
                _messages.value = _messages.value + userMessage
            }
        }
    }

    private fun saveIncomingChatImage(imageBase64: String): String? {
        return try {
            val bytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
            val dir = java.io.File(context.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "${UUID.randomUUID()}.jpg")
            java.io.FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            logger.e("ChatSkill", "Lỗi lưu ảnh khách gửi tới: ${e.message}")
            null
        }
    }

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    suspend fun openThread(username: String) {
        messagesMutex.withLock {
            currentUsername = username
            reloadMessages(username)
        }
    }

    suspend fun processQuery(
        message: String,
        extraContext: String = "",
        username: String,
        fileUrl: String? = null,
        imageBase64: String? = null,
        isManual: Boolean = false 
    ): PluginResult {
        return try {
            if (message.isBlank() && fileUrl.isNullOrEmpty() && imageBase64.isNullOrEmpty()) {
                return PluginResult.Success(
                    mapOf(
                        "response" to "",
                        "mode" to "history_reload_only"
                    )
                )
            }

            val isExternal = username.startsWith("facebook_") || username.startsWith("telegram_") ||
                username.startsWith("instagram_") || username.startsWith("website_")

            if (isExternal && isManual) {
                val hasImage = !imageBase64.isNullOrEmpty()
                val adminMessageId = UUID.randomUUID().toString()
                val adminMessage = ChatMessageEntity(
                    id = adminMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "assistant", 
                    type = if (hasImage) "image" else "text",
                    fileUrl = if (hasImage) fileUrl else null,
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = "human"
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(adminMessage)
                }
                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + adminMessage
                    }
                }

                val rawSenderId = username.substringAfter("_")
                if (username.startsWith("facebook_")) {
                    val pageId = withContext(Dispatchers.IO) {
                        database.cameraDao().getCustomerSetting(rawSenderId)?.lastFacebookPageId
                    } ?: extraContext.removePrefix("page_id:")
                    val fbParams = mutableMapOf<String, Any>(
                        "recipient_id" to rawSenderId,
                        "message" to message,
                        "page_id" to pageId
                    )
                    if (hasImage) fbParams["image_base64"] = imageBase64!!
                    agentKernel.executePluginAction("facebook", "send_messenger", fbParams)
                } else if (username.startsWith("telegram_")) {
                    val botToken = configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim()
                    if (hasImage) {
                        sendTelegramPhoto(botToken, rawSenderId, imageBase64!!, message)
                    } else {
                        sendTelegramMessage(botToken, rawSenderId, message)
                    }
                } else if (username.startsWith("website_")) {
                    val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                    val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                    sendWebsiteReply(gatewayUrl, gatewayToken, rawSenderId, message, if (hasImage) imageBase64 else null)
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to message,
                        "messageId" to adminMessageId,
                        "mode" to "human_agent"
                    )
                )

            } else {
                val resolvedFileUrl = if (!imageBase64.isNullOrEmpty()) {
                    saveIncomingChatImage(imageBase64)
                } else {
                    fileUrl
                }

                val userMessageId = UUID.randomUUID().toString()
                val userMessage = ChatMessageEntity(
                    id = userMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "user", 
                    type = if (!resolvedFileUrl.isNullOrEmpty()) "image" else "text", 
                    fileUrl = resolvedFileUrl, 
                    timestamp = System.currentTimeMillis(),
                    isRead = !isExternal
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(userMessage)
                }

                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + userMessage
                    }
                }

                val blockExternalDeviceControl = isExternal && configProvider.getBoolean(
                    AppConfigDefaults.GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL,
                    false
                )

                val memoryContext = if (imageBase64.isNullOrEmpty() && fileUrl.isNullOrEmpty() && isPastMemoryQuery(message)) {
                    buildMemoryContext(username, message) // 💡 Đã truyền thêm tham số 'message' để phục vụ việc trích xuất thực thể
                } else {
                    ""
                }
                
                val finalExtraContext = if (memoryContext.isNotBlank()) {
                    if (extraContext.isNotBlank()) "$extraContext\n\n$memoryContext" else memoryContext
                } else {
                    extraContext
                }

                val response = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = message,
                        username = username,
                        imageBase64 = imageBase64,
                        fileUrl = fileUrl,
                        extraContext = finalExtraContext,
                        chatMode = _chatMode.value.name,
                        allowDeviceControl = !blockExternalDeviceControl
                    )
                )

                val assistantMessageId = UUID.randomUUID().toString()
                val assistantMessage = ChatMessageEntity(
                    id = assistantMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = response.responseText,
                    role = "assistant",
                    type = if (response.imagePath != null) "image" else "text",   
                    fileUrl = response.imagePath,                                  
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = response.usedPluginId
                )

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(assistantMessage)
                }

                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + assistantMessage
                    }
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to response.responseText,
                        "messageId" to assistantMessageId,
                        "mode" to response.usedMode,
                        "imagePath" to response.imagePath
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Failed to process query")
        }
    }

    private suspend fun sendWebsiteReply(gatewayUrl: String, gatewayToken: String, senderId: String, text: String, imageBase64: String? = null) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$gatewayUrl/send/$gatewayToken")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", text)
                    if (!imageBase64.isNullOrEmpty()) {
                        put("imageBase64", imageBase64)
                    }
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi phản hồi cho khách Website thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
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

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi phản hồi về Telegram thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private suspend fun sendTelegramPhoto(token: String, chatId: String, imageBase64: String, caption: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                val boundary = "----AIChatVNBoundary${System.currentTimeMillis()}"
                val url = URL("https://api.telegram.org/bot$token/sendPhoto")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                conn.outputStream.use { os ->
                    fun writeField(name: String, value: String) {
                        os.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                        os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                        os.write("$value\r\n".toByteArray(Charsets.UTF_8))
                    }
                    writeField("chat_id", chatId)
                    if (caption.isNotBlank()) writeField("caption", caption)

                    os.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    os.write("Content-Disposition: form-data; name=\"photo\"; filename=\"image.jpg\"\r\n".toByteArray(Charsets.UTF_8))
                    os.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.UTF_8))
                    os.write(imageBytes)
                    os.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code != 200) {
                    logger.w("ChatSkill", "⚠️ Gửi ảnh Telegram thất bại, HTTP: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi ảnh về Telegram thất bại: ${e.message}")
            }
        }
    }

    suspend fun clearHistory(username: String): PluginResult {
        return try {
            withContext(Dispatchers.IO) {
                database.chatMessageDao().clearMessages(username)
            }
            messagesMutex.withLock {
                if (currentUsername == username) {
                    _messages.value = emptyList()
                }
            }
            PluginResult.Success(mapOf("message" to "History cleared"))
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Lỗi khi xóa lịch sử")
        }
    }
}