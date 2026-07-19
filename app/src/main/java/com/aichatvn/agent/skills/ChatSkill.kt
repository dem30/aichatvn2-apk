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
import kotlinx.coroutines.CancellationException // ✅ Chống nuốt coroutine exception
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
            "sự kiện", "biến cố", "cảnh báo", "báo cáo", "camera", "thiết bị"
        )

        private const val DEFAULT_MEMORY_LOOKBACK_DAYS = 3

        private val QUANTITY_KEYWORDS = setOf(
            "bao nhieu", "may lan", "so luong", "tong cong", "duoc may"
        )
    }

    private fun isQuantityQuery(normalizedMsg: String): Boolean =
        QUANTITY_KEYWORDS.any { normalizedMsg.contains(it) }

    private fun extractStateKeyword(originalMessage: String, normalizedMsg: String): List<String>? = when {
        originalMessage.contains("tắt", ignoreCase = true) || containsAnyWord(normalizedMsg, "off") ->
            listOf("tắt", "off", "Tắt", "OFF")
        originalMessage.contains("bật", ignoreCase = true) || containsAnyWord(normalizedMsg, "on") ->
            listOf("bật", "on", "Bật", "ON")
        else -> null
    }

    private fun containsAnyWord(text: String, vararg words: String) =
        words.any { com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(text, it) }

    private val OBJECT_LABEL_KEYWORDS = mapOf(
        "person" to listOf("nguoi la", "co nguoi", "nguoi dot nhap", "xam nhap", "trom"),
        "car" to listOf("oto", "xe hoi", "xe oto"),
        "motorbike" to listOf("xe may"),
        "dog" to listOf("con cho", "cho "),
        "cat" to listOf("con meo", "meo "),
        "package" to listOf("goi hang", "buu kien", "shipper")
    )

    private fun extractObjectLabel(normalizedMsg: String): String? =
        OBJECT_LABEL_KEYWORDS.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key

    private fun logHasObject(summary: String, label: String): Boolean =
        summary.contains("[objects:", ignoreCase = true) && summary.contains(label, ignoreCase = true)

    private fun isPastMemoryQuery(message: String): Boolean {
        val norm = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(message)
        return PAST_QUERY_KEYWORDS.any { keyword ->
            val normKeyword = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(keyword)
            norm.contains(normKeyword)
        }
    }

    private suspend fun buildMemoryContext(username: String, userMessage: String): String = withContext(Dispatchers.IO) {
        if (username != "default_user") return@withContext ""

        try {
            val now = System.currentTimeMillis()
            val maxLogs = configProvider.getInt(AppConfigDefaults.GLOBAL_MAX_MEMORY_LOGS, 30)

            val activeCameras = database.cameraDao().getActiveCameras()
            val activeDevices = database.tuyaDeviceDao().getAllDevices()
            val normalizedMsg = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userMessage.lowercase())

            val parsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalizedMsg, now)
            val since = parsedRange?.since
                ?: (now - DEFAULT_MEMORY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L)
            val rangeLabel = parsedRange?.label ?: "$DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất"

            val matchedCamera = activeCameras.find { cam ->
                val normCamName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(cam.customername.lowercase())
                normalizedMsg.contains(normCamName) || normalizedMsg.contains(cam.id.lowercase())
            }
            val matchedDevice = activeDevices.find { dev ->
                val normDevName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(dev.name.lowercase())
                normalizedMsg.contains(normDevName) || normalizedMsg.contains(dev.id.lowercase())
            }

            val platformKeywords = mapOf(
                "facebook" to listOf("facebook", "fb", "fanpage"),
                "telegram" to listOf("telegram"),
                "website" to listOf("website", "trang web", "widget web")
            )
            val matchedPlatform = if (matchedCamera == null && matchedDevice == null) {
                platformKeywords.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key
            } else null

            val until = parsedRange?.until ?: now
            val rawLogs = database.eventLogDao().getLogsInTimeframe(since, until)
            val filteredLogs = when {
                matchedCamera != null -> rawLogs.filter {
                    it.sourceId == matchedCamera.id || it.summary.contains(matchedCamera.customername, ignoreCase = true)
                }
                matchedDevice != null -> rawLogs.filter {
                    it.sourceId == matchedDevice.id || it.summary.contains(matchedDevice.name, ignoreCase = true)
                }
                matchedPlatform != null -> rawLogs.filter { it.source == matchedPlatform }
                else -> rawLogs.filter { it.eventType in setOf("person_detected", "state_change") }
            }

            val dateFmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault())

            val objectLabel = extractObjectLabel(normalizedMsg)
            val objectFilteredLogs = if (objectLabel != null) {
                filteredLogs.filter { logHasObject(it.summary, objectLabel) }
            } else filteredLogs

            if (isQuantityQuery(normalizedMsg) && objectFilteredLogs.isNotEmpty()) {
                val subjectLabel = when {
                    matchedCamera != null -> "camera ${matchedCamera.customername}"
                    matchedDevice != null -> "thiết bị ${matchedDevice.name}"
                    matchedPlatform != null -> "kênh $matchedPlatform"
                    else -> "hệ thống"
                }
                val stateKeyword = extractStateKeyword(userMessage, normalizedMsg)
                val countedLogs = if (stateKeyword != null) {
                    objectFilteredLogs.filter { log -> stateKeyword.any { log.summary.contains(it, ignoreCase = true) } }
                } else objectFilteredLogs
                val stateNote = if (stateKeyword != null) " (trạng thái: ${stateKeyword.first()})" else ""
                val objectNote = if (objectLabel != null) " (đối tượng: $objectLabel)" else ""
                return@withContext buildString {
                    append("<SYSTEM_MEMORY>\n") // Chuẩn hóa tag Giai đoạn 3
                    append("Trong khoảng $rangeLabel, $subjectLabel ghi nhận ${countedLogs.size} sự kiện$stateNote$objectNote.\n")
                    append("Hãy trả lời trực tiếp con số này, không cần liệt kê chi tiết trừ khi người dùng hỏi thêm.\n")
                    append("</SYSTEM_MEMORY>") // Chuẩn hóa tag Giai đoạn 3
                }
            }

            val activityLines = objectFilteredLogs
                .sortedBy { it.timestamp }
                .filter { matchedPlatform != null || it.eventType != "incoming_message" }
                .take(maxLogs)
                .map { log -> "${dateFmt.format(java.util.Date(log.timestamp))}: ${log.summary}" }

            val unreadLines = try {
                database.chatMessageDao().getAllMessagesRaw(500)
                    .filter { it.role == "user" && !it.isRead && it.username != "default_user" }
                    .filter { matchedPlatform == null || it.username.substringBefore("_") == matchedPlatform }
                    .sortedByDescending { it.timestamp }
                    .take(10)
                    .map { msg ->
                        val platform = msg.username.substringBefore("_")
                        val rawId = msg.username.substringAfter("_")
                        "${dateFmt.format(java.util.Date(msg.timestamp))}: [$platform] $rawId nhắn: \"${msg.content.take(80)}\""
                    }
            } catch (e: Exception) {
                emptyList()
            }

            val worldState = when {
                matchedCamera != null -> database.worldStateDao().getState("camera", matchedCamera.id)
                matchedDevice != null -> database.worldStateDao().getState("tuya", matchedDevice.id)
                else -> null
            }

            if (activityLines.isEmpty() && unreadLines.isEmpty() && worldState == null) return@withContext ""

            buildString {
                append("<SYSTEM_MEMORY>\n") // Chuẩn hóa tag Giai đoạn 3
                if (matchedCamera != null) {
                    append("--- Nhật ký hoạt động Camera ${matchedCamera.customername} ($rangeLabel) ---\n")
                } else if (matchedDevice != null) {
                    append("--- Nhật ký hoạt động Thiết bị ${matchedDevice.name} ($rangeLabel) ---\n")
                } else if (matchedPlatform != null) {
                    append("--- Nhật ký hoạt động kênh $matchedPlatform ($rangeLabel) ---\n")
                } else {
                    append("--- Nhật ký hoạt động tổng hợp ($rangeLabel) ---\n")
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
                append("</SYSTEM_MEMORY>") // Chuẩn hóa tag Giai đoạn 3
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

    /**
     * ✅ KHẮC PHỤC LỖI #2: Dọn dẹp an toàn chuỗi Base64 (loại bỏ tiền tố data:image/jpeg;base64,) trước khi decode.
     */
    private fun saveIncomingChatImage(imageBase64: String): String? {
        return try {
            val cleanBase64 = if (imageBase64.contains(",")) {
                imageBase64.substringAfter(",")
            } else {
                imageBase64
            }
            val bytes = android.util.Base64.decode(cleanBase64.trim(), android.util.Base64.NO_WRAP)
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

                    // ✅ KHẮC PHỤC LỖI #3: Phòng vệ kiểm tra rỗng Page ID sớm để tránh request Facebook bị lỗi.
                    if (pageId.isNullOrBlank()) {
                        logger.w("ChatSkill", "⚠️ Gửi Messenger thất bại: Không tìm thấy Page ID hợp lệ cho sender $rawSenderId.")
                        return PluginResult.Failure("⚠️ Không thể xác định Page ID hợp lệ để phản hồi Facebook.")
                    }

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
                    if (isExternal) {
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
                    buildMemoryContext(username, message) 
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
                        fileUrl = resolvedFileUrl, // ✅ KHẮC PHỤC LỖI #1: Truyền resolvedFileUrl (đã lưu máy) thay vì fileUrl rỗng lên AgentKernel
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
        } catch (e: CancellationException) {
            throw e // ✅ Khôi phục coroutine cancellation, bảo vệ vòng đời ứng dụng
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