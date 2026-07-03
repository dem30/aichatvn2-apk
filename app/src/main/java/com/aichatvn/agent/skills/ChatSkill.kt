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
    private val configProvider: AppConfigProvider, // Inject configProvider để lấy cấu hình kết nối Render
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
        return processQuery(message, extraContext, username)
    }

    private suspend fun handleClearHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        return clearHistory(username)
    }

    // Lưu tin nhắn khách hàng gửi từ Webhook vào SQLite (Không trả lời tự động)
    suspend fun saveExternalUserMessage(message: String, username: String) {
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionToken = "session_$username",
            username = username,
            content = message,
            role = "user",
            type = "text",
            timestamp = System.currentTimeMillis()
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

    // ✅ ĐÃ KHÔI PHỤC: Hàm setChatMode bị thiếu trong bản nâng cấp trước
    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    suspend fun processQuery(
        message: String,
        extraContext: String = "",
        username: String,
        fileUrl: String? = null,
        imageBase64: String? = null
    ): PluginResult {
        return try {
            messagesMutex.withLock {
                if (currentUsername != username) {
                    currentUsername = username
                    reloadMessages(username)
                }
            }

            // Kiểm tra xem đây có phải là tài khoản khách hàng từ Facebook/Telegram/Instagram không
            val isExternal = username.startsWith("facebook_") || username.startsWith("telegram_") || username.startsWith("instagram_")

            if (isExternal) {
                // 👤 LUỒNG 1: NGƯỜI TRỰC CHAT THỦ CÔNG (CƯỚP QUYỀN CHAT / HUMAN TAKEOVER)
                // Admin gõ tin nhắn trực tiếp từ màn hình điện thoại -> Lưu dưới vai trò Trợ lý (assistant) [1]
                val adminMessageId = UUID.randomUUID().toString()
                val adminMessage = ChatMessageEntity(
                    id = adminMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "assistant", // Lưu dạng assistant để giao diện hiểu người trực đang phản hồi [1]
                    type = "text",
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = "human" // Đánh dấu đây là người thật trả lời thủ công
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(adminMessage)
                }
                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + adminMessage
                    }
                }

                // Tách lấy ID gốc của khách hàng để đẩy tin nhắn thật ra các kênh liên kết [1]
                val rawSenderId = username.substringAfter("_")
                if (username.startsWith("facebook_")) {
                    val pageId = extraContext.removePrefix("page_id:") // Lấy page_id nếu có lưu trong extraContext
                    agentKernel.executePluginAction(
                        "facebook",
                        "send_messenger",
                        mapOf("recipient_id" to rawSenderId, "message" to message, "page_id" to pageId)
                    )
                } else if (username.startsWith("telegram_")) {
                    val botToken = configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim()
                    sendTelegramMessage(botToken, rawSenderId, message)
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to message,
                        "messageId" to adminMessageId,
                        "mode" to "human_agent"
                    )
                )

            } else {
                // 🤖 LUỒNG 2: AI TỰ ĐỘNG TRẢ LỜI (MẶC ĐỊNH CHO DEFAULT_USER)
                val userMessageId = UUID.randomUUID().toString()
                val userMessage = ChatMessageEntity(
                    id = userMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "user",
                    type = if (!fileUrl.isNullOrEmpty() || !imageBase64.isNullOrEmpty()) "image" else "text",
                    fileUrl = fileUrl,
                    timestamp = System.currentTimeMillis()
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(userMessage)
                }

                messagesMutex.withLock {
                    _messages.value = _messages.value + userMessage
                }

                val response = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = message,
                        username = username,
                        imageBase64 = imageBase64,
                        fileUrl = fileUrl,
                        extraContext = extraContext,
                        chatMode = _chatMode.value.name
                    )
                )

                val assistantMessageId = UUID.randomUUID().toString()
                val assistantMessage = ChatMessageEntity(
                    id = assistantMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = response.responseText,
                    role = "assistant",
                    type = "text",
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = response.usedPluginId
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(assistantMessage)
                }

                messagesMutex.withLock {
                    _messages.value = _messages.value + assistantMessage
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to response.responseText,
                        "messageId" to assistantMessageId,
                        "mode" to response.usedMode
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Failed to process query")
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
                logger.e("ChatSkill", "Gửi phản hồi về Telegram thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun clearHistory(username: String): PluginResult {
        return try {
            withContext(Dispatchers.IO) {
                database.chatMessageDao().clearMessages(username)
            }
            messagesMutex.withLock {
                _messages.value = emptyList()
            }
            PluginResult.Success(mapOf("message" to "History cleared"))
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Lỗi khi xóa lịch sử")
        }
    }
}