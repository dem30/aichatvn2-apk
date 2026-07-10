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
        return processQuery(message, extraContext, username, isManual = true) // Mặc định gọi từ hàm Kịch bản bên ngoài là trực tiếp
    }

    private suspend fun handleClearHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        return clearHistory(username)
    }

    // Lưu nhanh tin nhắn khách hàng gửi từ Webhook vào SQLite dưới vai trò USER (Không trả lời tự động)
    // ✅ MỚI: nhận thêm fileUrl (dùng thẳng cho URL ảnh Facebook/Instagram CDN) hoặc imageBase64
    // (Telegram/Website — không có URL công khai nên lưu file cục bộ rồi lấy đường dẫn) — trước
    // đây khi Admin bật "Người Trực" (bot tắt), ảnh khách gửi tới bị bỏ qua hoàn toàn, chỉ còn text.
    suspend fun saveExternalUserMessage(message: String, username: String, fileUrl: String? = null, imageBase64: String? = null) {
        val resolvedFileUrl = fileUrl ?: imageBase64?.let { saveIncomingChatImage(it) }
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionToken = "session_$username",
            username = username,
            content = message,
            role = "user", // ✅ SỬA: Đảm bảo lưu đúng vai trò khách gửi là "user" chứ không phải "assistant"
            type = if (resolvedFileUrl != null) "image" else "text",
            fileUrl = resolvedFileUrl,
            timestamp = System.currentTimeMillis(),
            // ✅ MỚI: Tin nhắn khách gửi tới từ Webhook luôn bắt đầu ở trạng thái CHƯA ĐỌC —
            // sẽ được đánh dấu đã đọc khi Admin thực sự mở ChatScreen của khách này
            // (xem ChatViewModel.init() -> markThreadAsRead()).
            isRead = false
        )
        withContext(Dispatchers.IO) {
            database.chatMessageDao().insertMessage(userMessage)
        }
        messagesMutex.withLock {
            if (currentUsername == username) {
                _messages.value = _messages.value + userMessage
            }
        }
    }

    // ✅ MỚI: Lưu ảnh khách gửi tới (base64, không kèm URL công khai — trường hợp Telegram/Website)
    // vào bộ nhớ cục bộ, cùng thư mục "chat_images" mà CameraSkill dùng cho ảnh chat của Admin.
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

    // ✅ MỚI: Hàm RIÊNG để "mở 1 thread lên màn hình" — CHỈ được gọi khi Admin thực sự điều
    // hướng vào xem 1 khách cụ thể (ChatViewModel.init()). Đây là NƠI DUY NHẤT được phép đổi
    // currentUsername + nạp lại _messages. Trước đây logic này nằm lẫn trong processQuery() và
    // chạy cho MỌI lời gọi — kể cả lời gọi ngầm của Webhook/SSE xử lý tin nhắn tự động cho MỘT
    // KHÁCH KHÁC. Hậu quả: đang xem chat khách A, khách B nhắn tới, bot tự trả lời B bằng
    // processQuery(username="B") ở nền -> đoạn switch cũ lập tức đổi currentUsername sang "B"
    // và NẠP ĐÈ toàn bộ _messages bằng lịch sử của B, trong khi tiêu đề màn hình (lấy từ route
    // param cố định) vẫn hiển thị A -> "Nick vẫn của người cũ, nội dung của người mới".
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
        isManual: Boolean = false // ✅ Đmax THÊM: Cờ hiệu phân biệt luồng Admin gõ tay / Máy chủ tự động gọi
    ): PluginResult {
        return try {
            // ✅ ĐÃ XÓA: đoạn "if (currentUsername != username) { currentUsername = username;
            // reloadMessages(username) }" trước đây nằm ở đây — đây chính là bug làm lẫn lộn
            // tin nhắn giữa các khách (xem giải thích ở openThread() phía trên). processQuery()
            // giờ KHÔNG BAO GIỜ tự ý đổi currentUsername nữa; việc đổi thread hiển thị chỉ có
            // thể xảy ra qua openThread() gọi tường minh từ ChatViewModel khi màn hình mở lên.

            // ✅ ĐÃ THÊM: message rỗng + không kèm ảnh/file nghĩa là lệnh gọi này chỉ dùng để
            // NẠP LẠI LỊCH SỬ (ví dụ ChatViewModel.init() gọi processQuery(message = "", ...)
            // mỗi khi mở 1 màn hình chat) — KHÔNG phải câu hỏi thật của người dùng. Trước đây
            // thiếu guard này nên chuỗi rỗng bị lưu thành 1 tin nhắn "user" trong DB rồi bypass
            // thẳng xuống gọi Groq — tốn quota vô ích và gây lỗi ngay khi vừa mở app, trước khi
            // người dùng kịp gõ gì cả (đây chính là log "Bắt đầu tiếp nhận thông điệp: ''").
            if (message.isBlank() && fileUrl.isNullOrEmpty() && imageBase64.isNullOrEmpty()) {
                return PluginResult.Success(
                    mapOf(
                        "response" to "",
                        "mode" to "history_reload_only"
                    )
                )
            }

            // ✅ ĐÃ SỬA: Thêm "website_" — khách chat qua widget Website cũng là khách ngoại kênh,
            // không phải chủ app, nên không được phép chạm vào logic điều khiển thiết bị.
            val isExternal = username.startsWith("facebook_") || username.startsWith("telegram_") ||
                username.startsWith("instagram_") || username.startsWith("website_")

            if (isExternal && isManual) {
                // 👤 LUỒNG 1: NGƯỜI TRỰC CHAT THỦ CÔNG (ADMIN GÕ TAY TỪ ĐIỆN THOẠI)
                // ✅ MỚI: trước đây imageBase64/fileUrl của chính lời gọi này chưa từng được đọc ở
                // nhánh LUỒNG 1 — admin đính kèm ảnh khi trả lời khách ngoại kênh chỉ lưu được
                // chuỗi placeholder "[Hình ảnh]" cục bộ, ảnh thật không hề được gửi ra ngoài.
                val hasImage = !imageBase64.isNullOrEmpty()
                val adminMessageId = UUID.randomUUID().toString()
                val adminMessage = ChatMessageEntity(
                    id = adminMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "assistant", // Ghi nhận là người trực trả lời
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

                // Đẩy tin nhắn thật ra các cổng liên kết mạng xã hội [1]
                val rawSenderId = username.substringAfter("_")
                if (username.startsWith("facebook_")) {
                    // ✅ ĐÃ SỬA: Trước đây chỉ đọc page_id từ extraContext, nhưng ChatViewModel.
                    // sendMessageWithImage() không truyền tham số này nên luôn rỗng -> gửi thất bại
                    // âm thầm khi có nhiều Fanpage liên kết. Giờ đọc lại Page ID đã được
                    // WebhookGatewayService lưu vào customer_settings ngay lúc tin nhắn đến,
                    // giữ extraContext làm phương án dự phòng cho các nơi gọi khác (nếu có).
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
                    // ✅ ĐÃ THÊM: Trước đây rơi vào đây là hết — tin nhắn admin gõ tay chỉ lưu local,
                    // không có gì gửi ra ngoài cho khách Website đang chờ trên widget.
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
                // 🤖 LUỒNG 2: AI TỰ ĐỘNG PHẢN HỒI (MÁY CHỦ SSE HOẶC default_user GỌI)
                val userMessageId = UUID.randomUUID().toString()
                val userMessage = ChatMessageEntity(
                    id = userMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "user", // Lưu dạng tin khách hỏi
                    type = if (!fileUrl.isNullOrEmpty() || !imageBase64.isNullOrEmpty()) "image" else "text",
                    fileUrl = fileUrl,
                    timestamp = System.currentTimeMillis(),
                    // ✅ MỚI: Tin nhắn khách ngoại kênh gửi tới (kể cả khi AI tự động trả lời ngay)
                    // vẫn tính là "chưa đọc" cho tới khi Admin thực sự mở thread — default_user
                    // (chat cá nhân của chủ app) thì không cần nên vẫn giữ true.
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

                // ✅ ĐÃ SỬA: Dùng ĐÚNG công tắc đã có sẵn trong Settings (GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL)
                // thay vì tự chế cờ mới. Trước đây key này tồn tại trong AppConfigDefaults và hiện ra
                // như 1 switch trong màn Cài đặt, nhưng KHÔNG có chỗ nào đọc giá trị của nó — bật/tắt
                // không có tác dụng gì. Giờ đọc đúng giá trị đó để quyết định có cho quét lệnh thiết bị
                // hay không khi trả lời khách hàng ngoại kênh.
                val blockExternalDeviceControl = isExternal && configProvider.getBoolean(
                    AppConfigDefaults.GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL,
                    false
                )

                val response = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = message,
                        username = username,
                        imageBase64 = imageBase64,
                        fileUrl = fileUrl,
                        extraContext = extraContext,
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
                    type = if (response.imagePath != null) "image" else "text",   // ✅ SỬA
                    fileUrl = response.imagePath,                                  // ✅ MỚI
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
                        // ✅ MỚI: trước đây imagePath bị cắt mất ở đây — bot/plugin (vd. CameraSkill
                        // quét camera trả ảnh) không bao giờ có cách gửi ảnh đó ra kênh ngoại (FB/
                        // Telegram/Website), vì WebhookGatewayService chỉ đọc field "response".
                        "imagePath" to response.imagePath
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Failed to process query")
        }
    }

    // ✅ ĐÃ THÊM: Đẩy tin nhắn admin gõ tay ra khách Website qua Gateway (không có Graph API như
    // Facebook/Telegram nên phải đi qua hàng đợi SSE riêng /send/{token} platform=website).
    // ✅ MỚI: nhận thêm imageBase64 tuỳ chọn — Render sẽ đẩy nguyên field này vào hàng đợi SSE
    // của khách, widget Website tự giải mã và hiển thị <img>.
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

    // ✅ MỚI: Gửi ảnh (kèm caption tuỳ chọn) trực tiếp cho Telegram bằng multipart/form-data —
    // Telegram Bot API "sendPhoto" nhận file nhị phân trực tiếp, không cần ảnh có URL công khai.
    // Gọi thẳng api.telegram.org (giống sendTelegramMessage), KHÔNG đi qua Render Gateway.
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
            // ✅ ĐÃ SỬA: Trước đây xóa `_messages` VÔ ĐIỀU KIỆN bất kể `username` truyền vào có
            // phải là thread đang mở trên màn hình hay không — cùng nhóm lỗi với processQuery(),
            // nếu có nơi nào gọi clearHistory() cho 1 khách khác trong lúc Admin đang xem khách
            // hiện tại, màn hình đang mở sẽ bị trắng xóa nhầm. Giờ chỉ xóa _messages khi đúng là
            // thread đang active.
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