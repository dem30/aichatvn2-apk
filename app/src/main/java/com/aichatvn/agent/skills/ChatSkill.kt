package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.ChatHistoryManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val chatHistoryManager: ChatHistoryManager,
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

    // ✅ SỬA (theo yêu cầu): trước đây _chatMode là 1 StateFlow DUY NHẤT cho toàn app —
    // admin đổi mode để test thread của 1 khách Facebook sẽ vô tình đổi mode của MỌI khách
    // khác đang chat live cùng lúc (ChatSkill là @Singleton, dùng chung 1 state cho mọi
    // username). Tách thành 2 state độc lập: admin (default_user) và khách ngoại kênh
    // (facebook_/telegram_/instagram_/website_ — dùng CHUNG 1 mode cho tất cả khách, không
    // phải per-khách-hàng, vì user không yêu cầu tách sâu tới mức đó). allowDeviceControl
    // (blockExternalDeviceControl) giữ nguyên hoàn toàn logic cũ, không đổi gì.
    private val _adminChatMode = MutableStateFlow(ChatMode.QA)
    val adminChatMode: StateFlow<ChatMode> = _adminChatMode.asStateFlow()

    private val _externalChatMode = MutableStateFlow(ChatMode.QA)
    val externalChatMode: StateFlow<ChatMode> = _externalChatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private var currentUsername: String = "default_user"
    private val messagesMutex = Mutex()

    private val chatMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    // ✅ MỚI: scope riêng CHỈ để ghi _adminChatMode/_externalChatMode xuống app_config (bền
    // vững qua khởi động lại app) mà không phải đổi setAdminChatMode()/setExternalChatMode()
    // thành suspend fun — tránh phải sửa mọi nơi UI (ViewModel/Screen) đang gọi 2 hàm này.
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    companion object {
        // ✅ MỚI: chặn tin nhắn khách ngoại kênh gửi tới quá dài trước khi nó đi vào cả 3 nơi:
        // DB lưu trữ, event_log summary, và context gửi lên LLM (tốn token trực tiếp, và
        // không kiểm soát được vì khách hàng bên ngoài là nguồn không tin cậy).
        // 4000 ký tự ~ đủ cho một đoạn mô tả dài, đồng thời chặn được kiểu spam/paste-bomb.
        private const val MAX_INCOMING_MESSAGE_LENGTH = 4000
    }

    private fun capIncomingMessage(raw: String): String {
        if (raw.length <= MAX_INCOMING_MESSAGE_LENGTH) return raw
        return raw.take(MAX_INCOMING_MESSAGE_LENGTH) +
            "\n\n[...tin nhắn đã bị cắt bớt, tổng gốc ${raw.length} ký tự, vượt giới hạn xử lý]"
    }


    override suspend fun initialize() {
        reloadMessages(currentUsername)
        // ✅ MỚI: nạp lại mode đã lưu ở app_config (nếu admin từng đổi qua UI) — nếu key
        // chưa có/giá trị hỏng (vd sửa tay DB) thì rơi về default hiện tại của StateFlow (QA),
        // không crash.
        loadPersistedChatModes()
    }

    private suspend fun loadPersistedChatModes() {
        val savedAdmin = configProvider.getString(
            AppConfigDefaults.GLOBAL_ADMIN_CHAT_MODE,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_ADMIN_CHAT_MODE)
        )
        val savedExternal = configProvider.getString(
            AppConfigDefaults.GLOBAL_EXTERNAL_CHAT_MODE,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_EXTERNAL_CHAT_MODE)
        )
        runCatching { ChatMode.valueOf(savedAdmin) }.getOrNull()?.let { _adminChatMode.value = it }
        runCatching { ChatMode.valueOf(savedExternal) }.getOrNull()?.let { _externalChatMode.value = it }
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

    /**
     * Đồng bộ hóa và cấu trúc JSON cho các sự kiện tin nhắn ngoại kênh gửi đến
     */
    private suspend fun recordIncomingChatEvent(
        message: String,
        username: String,
        timestamp: Long,
        hasImage: Boolean = false   // ✅ MỚI: để summary/event log biết khách có gửi kèm ảnh không
    ): String = withContext(Dispatchers.IO) {
        val mutex = chatMutexes.getOrPut(username) { Mutex() }
        mutex.withLock {
            val platform = username.substringBefore("_")
            val rawId = username.substringAfter("_")
            val normMsg = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(message.lowercase()) ?: ""

            val intent = when {
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "gia bao nhieu") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "bao gia") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "mua") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "ban") -> "ask_price"
                
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "loi") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "hong") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "bao hanh") ||
                com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "ho tro") -> "support"
                
                else -> "general"
            }

            val urgency = if (com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "gap") || com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "ngay") || com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "khan") || com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "nguy") || com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "trom") || com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(normMsg, "chay")) "high" else "normal"

            val existingState = database.worldStateDao().getState("chat", username)
            val prevUnread = existingState?.let {
                try { org.json.JSONObject(it.attributesJson).optInt("unread_count", 0) } catch (e: Exception) { 0 }
            } ?: 0
            val newUnread = prevUnread + 1

            // ✅ MỚI: nhúng nội dung tin nhắn thật vào summary — đây là chỗ DatabaseSearchHelper
            // và objectAliasResolver đọc để trả lời câu hỏi về NỘI DUNG, giống cách camera nhúng
            // [objects: ...] vào summary của nó. Trước đây summary chỉ có metadata (intent/urgency/
            // unread) nên mọi câu hỏi "khách nhắn gì" không bao giờ khớp được gì cả.
            val contentPreview = message.replace("\n", " ").replace("\r", " ").trim().take(300)
            val displayContent = when {
                contentPreview.isNotBlank() && hasImage -> "📷 $contentPreview"
                contentPreview.isNotBlank() -> contentPreview
                hasImage -> "[Gửi hình ảnh, không có chú thích]"
                else -> "(trống)"
            }

            val eventPayload = org.json.JSONObject().apply {
                put("platform", platform)
                put("senderId", rawId)
                put("session_status", "waiting_agent")
                put("customer_intent", intent)
                put("urgency", urgency)
                put("unread_count", newUnread)
                put("last_message", message.take(80))
                put("has_image", hasImage)   // ✅ MỚI
            }.toString()

            // 1. Cập nhật trạng thái sống World State
            database.worldStateDao().upsertState(
                com.aichatvn.agent.data.model.WorldStateEntity(
                    id = "chat:$username",
                    source = "chat",
                    sourceId = username,
                    attributesJson = eventPayload,
                    updatedAt = System.currentTimeMillis()
                )
            )

            // 2. Ghi EventLog cấu trúc JSON (Sử dụng username đầy đủ để đồng bộ nhóm với openThread/toggleBotSmartMode)
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    source = platform,
                    sourceId = username, // ✅ ĐÃ SỬA: Đổi từ rawId sang username để đồng bộ nhóm
                    eventType = "chat_session_state_change",
                    value = eventPayload,
                    summary = "Tin nhắn mới từ [$platform] $rawId: \"$displayContent\" (Ý định: $intent, Độ khẩn cấp: $urgency, Chưa đọc: $newUnread)"
                )
            )

            eventPayload
        }
    }

    suspend fun saveExternalUserMessage(message: String, username: String, fileUrl: String? = null, imageBase64: String? = null) {
        // ✅ MỚI: luôn ngoại kênh ở hàm này nên luôn cắt độ dài trước khi lưu/log/gửi AI
        val safeMessage = capIncomingMessage(message)

        val resolvedFileUrl = if (!imageBase64.isNullOrEmpty()) {
            saveIncomingChatImage(imageBase64)
        } else {
            fileUrl
        }
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionToken = "session_$username",
            username = username,
            content = safeMessage,
            role = "user", 
            type = if (resolvedFileUrl != null) "image" else "text",
            fileUrl = resolvedFileUrl,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        withContext(Dispatchers.IO) {
            database.chatMessageDao().insertMessage(userMessage)
            
            // Gọi hàm dùng chung recordIncomingChatEvent để ghi JSON/WorldState đồng nhất khi tắt Bot
            recordIncomingChatEvent(safeMessage, username, userMessage.timestamp, hasImage = !resolvedFileUrl.isNullOrEmpty())
        }
        messagesMutex.withLock {
            if (currentUsername == username) {
                _messages.value = _messages.value + userMessage
            }
        }
    }

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

    fun setAdminChatMode(mode: ChatMode) {
        _adminChatMode.value = mode
        // affectsConnection = false: đổi mode chat không liên quan gateway/SSE, không cần
        // ép WebhookGatewayService resync (xem comment trong AppConfigProvider.set()).
        persistScope.launch {
            configProvider.set(AppConfigDefaults.GLOBAL_ADMIN_CHAT_MODE, mode.name, affectsConnection = false)
        }
    }

    fun setExternalChatMode(mode: ChatMode) {
        _externalChatMode.value = mode
        persistScope.launch {
            configProvider.set(AppConfigDefaults.GLOBAL_EXTERNAL_CHAT_MODE, mode.name, affectsConnection = false)
        }
    }

    suspend fun openThread(username: String) {
        messagesMutex.withLock {
            currentUsername = username
            reloadMessages(username)
        }

        withContext(Dispatchers.IO) {
            val existingState = database.worldStateDao().getState("chat", username)
            if (existingState != null) {
                try {
                    val json = org.json.JSONObject(existingState.attributesJson)
                    json.put("session_status", "resolved")
                    json.put("unread_count", 0)

                    val updatedPayload = json.toString()
                    
                    database.worldStateDao().upsertState(
                        com.aichatvn.agent.data.model.WorldStateEntity(
                            id = "chat:$username",
                            source = "chat",
                            sourceId = username,
                            attributesJson = updatedPayload,
                            updatedAt = System.currentTimeMillis()
                        )
                    )

                    database.eventLogDao().insertLog(
                        com.aichatvn.agent.data.model.EventLogEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            source = "chat",
                            sourceId = username,
                            eventType = "chat_session_state_change",
                            value = updatedPayload,
                            summary = "Phiên chat với ${username.substringAfter("_")} đã được quản trị viên mở đọc và chuyển trạng thái: Đã giải quyết (resolved)."
                        )
                    )
                } catch (e: Exception) {
                    logger.e("ChatSkill", "Lỗi cập nhật trạng thái đã đọc vào WorldState: ${e.message}")
                }
            }
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
                    val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_GATEWAY_URL)).trim()
                    val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN)).trim()
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
                // ✅ MỚI: chỉ cắt độ dài với khách ngoại kênh (nguồn không tin cậy) — người dùng
                // nội bộ (default_user) có thể cố ý dán đoạn dài cần AI đọc nguyên vẹn.
                val safeMessage = if (isExternal) capIncomingMessage(message) else message

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
                    content = safeMessage,
                    role = "user", 
                    type = if (!resolvedFileUrl.isNullOrEmpty()) "image" else "text", 
                    fileUrl = resolvedFileUrl, 
                    timestamp = System.currentTimeMillis(),
                    isRead = !isExternal
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(userMessage)
                    if (isExternal) {
                        // ✅ SỬA LỖI LỚN BƯỚC 2: Gọi hàm dùng chung bóc tách JSON khi nhận tin nhắn đa kênh (kể cả khi Bot đang BẬT)
                        recordIncomingChatEvent(safeMessage, username, userMessage.timestamp, hasImage = !resolvedFileUrl.isNullOrEmpty())
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

                // ✅ SỬA (theo yêu cầu: bỏ hẳn nhồi SYSTEM_MEMORY chủ động trước lượt gọi model):
                // buildMemoryContext() từng tự đoán domain + query DB + nhồi log vào system prompt
                // TRƯỚC KHI model kịp quyết định có cần data hay không — chạy song song, độc lập
                // với Two-Pass db_search của AgentKernel. Hệ quả đúng như log build thật cho thấy:
                // model vẫn cứ tự phát db_search bất kể đã có SYSTEM_MEMORY nhồi sẵn hay chưa, nên
                // phần nhồi trước chỉ tốn token gấp đôi mà không đổi được hành vi của model. Two-Pass
                // (category=chat/qa, keyword lọc nội dung, KEYWORD_MATCH_LIMIT) đã đủ để model tự
                // tra đúng lúc cần — không cần bước lọc-trước-rồi-gửi-kèm này nữa.
                val finalExtraContext = extraContext

                // ✅ SỬA: chọn đúng mode theo role thay vì dùng chung 1 state toàn app — xem
                // comment ở khai báo _adminChatMode/_externalChatMode phía trên.
                val effectiveChatMode = if (isExternal) _externalChatMode.value else _adminChatMode.value

                val response = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = safeMessage,
                        username = username,
                        imageBase64 = imageBase64,
                        fileUrl = resolvedFileUrl, 
                        extraContext = finalExtraContext,
                        chatMode = effectiveChatMode.name,
                        allowDeviceControl = !blockExternalDeviceControl
                    )
                )

                val assistantMessageId = UUID.randomUUID().toString()
                // ✅ SỬA: AgentKernel.ChatResponse.imagePath (String? đơn) đã đổi thành imagePaths
                // (List<String>?) — khi câu hỏi khớp nhiều cảnh báo, response.imagePaths có thể
                // chứa nhiều ảnh. ChatMessageEntity.fileUrl vẫn là String? đơn (không đổi schema
                // Room) nên KHÔNG dồn hết vào 1 dòng — thay vào đó: 1 dòng "text" luôn chứa toàn bộ
                // responseText, cộng thêm 1 dòng "image" riêng cho MỖI ảnh khớp. Mỗi dòng "image"
                // tái dùng nguyên cơ chế ChatBubble/BitmapFactory.decodeFile() sẵn có (xem
                // ChatScreen.kt) — không cần đổi UI hay migration DB.
                val imagePaths = response.imagePaths.orEmpty()

                // ✅ MỚI (fix "ảnh bị lỗi"/bong bóng trống trong ChatBubble): AgentKernel đã lọc
                // File.exists() ở tầng data (xem runLocalQAEventAnalysis()/interceptAndExecuteToolCall()
                // trong AgentKernel.kt), nhưng vẫn kiểm tra lại NGAY TRƯỚC KHI insert ở đây — đây là
                // lớp phòng thủ gần nhất với thời điểm tạo ChatMessageEntity, tránh mọi khả năng
                // dangling reference lọt qua (race condition với cleanupChatImages()/cleanupOldAlerts()
                // chạy song song, hoặc nguồn imagePaths nào khác trong tương lai không đi qua
                // AgentKernel). Không bao giờ tạo ChatMessageEntity(type="image") trỏ tới file
                // không tồn tại — ChatScreen.kt/ChatBubble() sẽ không còn vẽ ra bong bóng rỗng.
                val validImagePaths = withContext(Dispatchers.IO) {
                    imagePaths.filter { path -> path.isNotBlank() && java.io.File(path).exists() }
                }
                if (validImagePaths.size < imagePaths.size) {
                    logger.w(
                        "ChatSkill",
                        "⚠️ Bỏ qua ${imagePaths.size - validImagePaths.size}/${imagePaths.size} ảnh không tồn tại trên đĩa (đã bị dọn hoặc path sai) trước khi tạo ChatMessageEntity."
                    )
                }

                // ✅ MỚI (nút bấm chọn nhanh trong chat): serialize response.quickReplies (List
                // (label,value) — xem AgentKernel.ChatResponse) thành JSON array-of-array để lưu
                // vào ChatMessageEntity.quickRepliesJson. null khi response không phải đang hỏi
                // chọn (đa số trường hợp) — ChatBubble chỉ vẽ nút khi field này khác null/rỗng.
                val quickRepliesJson = response.quickReplies?.takeIf { it.isNotEmpty() }?.let { replies ->
                    org.json.JSONArray().apply {
                        replies.forEach { (label, value) ->
                            put(org.json.JSONArray().apply { put(label); put(value) })
                        }
                    }.toString()
                }

                val assistantMessage = ChatMessageEntity(
                    id = assistantMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = response.responseText,
                    role = "assistant",
                    type = "text",
                    fileUrl = null,
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = response.usedPluginId,
                    quickRepliesJson = quickRepliesJson
                )

                val imageMessages = validImagePaths.mapIndexed { index, path ->
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionToken = "session_$username",
                        username = username,
                        content = "",
                        role = "assistant",
                        type = "image",
                        fileUrl = path,
                        // ✅ Cộng thêm index*1ms để giữ đúng thứ tự hiển thị (text trước, ảnh sau,
                        // ảnh theo đúng thứ tự bestImagePaths) khi UI sort theo timestamp.
                        timestamp = System.currentTimeMillis() + index + 1,
                        sourcePlugin = response.usedPluginId
                    )
                }

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(assistantMessage)
                    imageMessages.forEach { database.chatMessageDao().insertMessage(it) }
                }

                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + assistantMessage + imageMessages
                    }
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to response.responseText,
                        "messageId" to assistantMessageId,
                        "mode" to response.usedMode,
                        "imagePaths" to validImagePaths
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e 
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

                // ✅ SỬA LỖI: server giờ BẮT BUỘC widgetKey trong payload /send để xác định
                // đúng khách website (nhiều máy có thể chung 1 gatewayToken). Trước đây hàm
                // này (dùng riêng cho người trực trả lời thủ công, khác với sendWebsiteReply
                // của WebhookGatewayService dùng cho bot tự động) không gửi widgetKey — server
                // từ chối request với "Missing widgetKey", nhưng vì code chỉ đọc responseCode
                // mà không kiểm tra nội dung lỗi nên trông như gửi thành công, khiến người
                // trực KHÔNG THỂ chat được với khách dù bot tự động vẫn hoạt động bình thường
                // (đường bot đã có widgetKey từ trước). Widget_key của máy này là nguồn duy
                // nhất, giống hệt cách WebhookGatewayService đọc.
                val widgetKey = configProvider.getString(AppConfigDefaults.WEBSITE_WIDGET_KEY).trim()

                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", text)
                    put("widgetKey", widgetKey)
                    if (!imageBase64.isNullOrEmpty()) {
                        put("imageBase64", imageBase64)
                    }
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    logger.e("ChatSkill", "⚠️ Gửi phản hồi Website thất bại, HTTP $code: $errBody")
                }
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