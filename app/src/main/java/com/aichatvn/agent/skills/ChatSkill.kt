package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatMode {
    GROQ,
    QA,
    COMBINED
}

private const val ANTI_HALLUCINATION_GUARD =
    "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
    "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
    "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được."

private fun routerFailedNotice(reason: String) =
    "⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định " +
    "được chính xác (lý do nội bộ: $reason). Hãy báo cho user là lệnh CHƯA thực hiện được và " +
    "hỏi họ nói rõ hơn (tên thiết bị/hành động), ĐỪNG khẳng định đã làm."

@Singleton
class ChatSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val agentKernel: AgentKernel,
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger), Plugin {

    override val routable: Boolean = false
    override val visibleOnDashboard: Boolean = false
    override val autoGenerateQA: Boolean = false

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.GROQ)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private var currentUsername: String = "default_user"
    private val messagesMutex = Mutex()

    override suspend fun initialize() {
        val loaded = withContext(Dispatchers.IO) {
            database.chatMessageDao().getMessages(currentUsername, 500)
        }
        _messages.value = loaded
        logger.d("ChatSkill", "Initialized with ${loaded.size} messages for user '$currentUsername'")
    }

    override suspend fun shutdown() {}

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "chat",
                description = "Trò chuyện với AI",
                parameters = listOf(
                    PluginParameter("message", "string", "Tin nhắn", true),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "clear_history",
                description = "Xóa lịch sử chat",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "get_history",
                description = "Lấy lịch sử chat",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true),
                    PluginParameter("limit", "int", "Số lượng tin nhắn tối đa", false)
                )
            )
        )
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        logger.d("ChatSkill", "execute: action=$action")
        
        return when (action) {
            "chat" -> handleChat(params)
            "clear_history" -> handleClearHistory(params)
            "get_history" -> handleGetHistory(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleChat(params: Map<String, Any>): PluginResult {
        val message = params["message"] as? String
            ?: return PluginResult.Failure("Thiếu tham số message")
        
        val username = params["username"] as? String ?: "default_user"
        val fileUrl = params["fileUrl"] as? String
        val imageBase64 = params["imageBase64"] as? String
        val extraContext = params["extraContext"] as? String ?: ""
        
        val result = processQuery(message, extraContext, username, fileUrl, imageBase64)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Không thể xử lý")
        }
    }

    private suspend fun handleClearHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        return clearHistory(username)
    }

    private suspend fun handleGetHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        val limit = (params["limit"] as? Number)?.toInt() ?: 100
        return getChatHistory(username, limit)
    }

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    private suspend fun buildQAContext(message: String, username: String): String {
        val result = trainingSkill.fuzzyMatchQuestion(message, username, 0.7f)
        return when (result) {
            is PluginResult.Success -> {
                val data = result.data
                if (data == null) return ""
                @Suppress("UNCHECKED_CAST")
                val matches = data as? List<Map<String, Any>> ?: return ""
                if (matches.isEmpty()) return ""
                
                matches.joinToString("\n") { match ->
                    val qa = match["qa"] as? com.aichatvn.agent.data.model.QAEntity
                    val similarity = match["similarity"] as? Float ?: 0f
                    if (qa != null) {
                        "📚 Q: ${qa.question}\n   A: ${qa.answer} (độ tương tự: ${String.format("%.2f", similarity)})"
                    } else ""
                }
            }
            is PluginResult.Failure -> {
                logger.d("ChatSkill", "buildQAContext failed: ${result.error}")
                ""
            }
            else -> ""
        }
    }

    suspend fun addAssistantMessage(response: String, username: String): ChatMessageEntity {
        val assistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessageEntity(
            id = assistantMessageId,
            sessionToken = "session_$username",
            username = username,
            content = response,
            role = "assistant",
            type = "text",
            timestamp = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            database.chatMessageDao().insertMessage(assistantMessage)
        }
        
        messagesMutex.withLock {
            _messages.value = _messages.value + assistantMessage
        }
        return assistantMessage
    }

    suspend fun processQuery(
        message: String,
        extraContext: String = "",
        username: String,
        fileUrl: String? = null,
        imageBase64: String? = null
    ): PluginResult {
        return try {
            // SỬA ĐỒNG BỘ: Bảo bọc khối logic đổi tài khoản người dùng ngầm bằng Khóa Mutex an toàn
            messagesMutex.withLock {
                if (currentUsername != username) {
                    currentUsername = username
                    val loaded = withContext(Dispatchers.IO) {
                        database.chatMessageDao().getMessages(username, 500)
                    }
                    _messages.value = loaded
                    logger.d("ChatSkill", "Username changed to '$username', reloaded ${loaded.size} messages")
                }
            }

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

            val responseText: String
            val usedMode: String
            var usedPluginId: String? = null
            
            if (message.startsWith("Học:") || message.startsWith("Dạy:")) {
                val parts = message.substringAfter(":").split("→").map { it.trim() }
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    val learnResult = trainingSkill.addQA(key, value, "general", username)
                    responseText = when (learnResult) {
                        is PluginResult.Success -> "✅ Đã học: $key → $value"
                        is PluginResult.Failure -> "❌ Lỗi học: ${learnResult.error}"
                        else -> "❌ Không thể học"
                    }
                    usedMode = "learn"
                    usedPluginId = "learn"
                } else {
                    responseText = "❌ Cú pháp: Học: câu hỏi → câu trả lời"
                    usedMode = "learn_error"
                }
            }
            else if (imageBase64 != null || fileUrl != null) {
                usedMode = "vision"
                
                // TỐI ƯU HÓA LỚN: Bao bọc tác vụ đọc dữ liệu tệp tin ảnh ổ cứng trong IO Dispatcher ngầm
                val imageDataUrl = if (!imageBase64.isNullOrEmpty()) {
                    "data:image/jpeg;base64,$imageBase64"
                } else if (!fileUrl.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val file = java.io.File(fileUrl)
                            if (file.exists()) {
                                val bytes = file.readBytes()
                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                "data:image/jpeg;base64,$base64"
                            } else null
                        } catch (e: Exception) { null }
                    }
                } else null
                
                val historySnapshot = _messages.value.takeLast(6).map {
                    mapOf("role" to it.role, "content" to it.content)
                }
                
                responseText = try {
                    withTimeout(30_000L) {
                        groqClient.chat(
                            message = message,
                            extraContext = extraContext,
                            history = historySnapshot,
                            imageUrl = imageDataUrl
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
                }
            }
            else {
                when (val outcome = agentKernel.tryDeviceCommand(message, username)) {

                    is AgentKernel.RouterOutcome.Matched -> {
                        usedMode = "device_control"
                        usedPluginId = outcome.result.pluginId
                        logger.d("ChatSkill", "Routed to AgentKernel local router: '$message' -> plugin=${outcome.result.pluginId}")
                        responseText = when (val result = outcome.result.result) {
                            is PluginResult.Success -> {
                                val data = result.data as? Map<*, *>
                                data?.get("message") as? String ?: "✅ Đã thực hiện"
                            }
                            is PluginResult.Failure -> result.error
                            is PluginResult.NeedMoreInfo -> result.question
                        }
                    }

                    is AgentKernel.RouterOutcome.NotACommand,
                    is AgentKernel.RouterOutcome.RouterFailed -> {
                        val routerFailed = outcome is AgentKernel.RouterOutcome.RouterFailed
                        usedMode = _chatMode.value.name
                        if (routerFailed) usedPluginId = "router_error"
                        val currentMode = _chatMode.value
                        val historySnapshot = _messages.value.takeLast(6).map {
                            mapOf("role" to it.role, "content" to it.content)
                        }

                        val qaContext = buildQAContext(message, username)
                        val guard = if (routerFailed) {
                            ANTI_HALLUCINATION_GUARD + "\n" +
                                routerFailedNotice((outcome as AgentKernel.RouterOutcome.RouterFailed).reason)
                        } else {
                            ANTI_HALLUCINATION_GUARD
                        }
                        val fullContext = buildString {
                            append(guard)
                            if (qaContext.isNotEmpty()) append("\n\n$qaContext")
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                        }

                        responseText = try {
                            withTimeout(30_000L) {
                                when (currentMode) {
                                    ChatMode.QA -> {
                                        val qaResult = trainingSkill.fuzzyMatchQuestion(message, username, 0.6f)
                                        when (qaResult) {
                                            is PluginResult.Success -> {
                                                @Suppress("UNCHECKED_CAST")
                                                val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                                                val qa = matches.firstOrNull()?.get("qa") as? com.aichatvn.agent.data.model.QAEntity
                                                qa?.answer ?: "Không tìm thấy câu trả lời phù hợp."
                                            }
                                            is PluginResult.Failure -> {
                                                "Xin lỗi, tôi không tìm thấy câu trả lời cho câu hỏi này."
                                            }
                                            else -> "Không thể xử lý"
                                        }
                                    }
                                    ChatMode.COMBINED, ChatMode.GROQ -> {
                                        groqClient.chat(
                                            message = message,
                                            extraContext = fullContext,
                                            history = historySnapshot,
                                            imageUrl = null
                                        )
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
                        }
                    }
                }
            }

            val assistantMessageId = UUID.randomUUID().toString()
            val assistantMessage = ChatMessageEntity(
                id = assistantMessageId,
                sessionToken = "session_$username",
                username = username,
                content = responseText,
                role = "assistant",
                type = "text",
                timestamp = System.currentTimeMillis(),
                sourcePlugin = usedPluginId
            )
            withContext(Dispatchers.IO) {
                database.chatMessageDao().insertMessage(assistantMessage)
            }

            messagesMutex.withLock {
                _messages.value = _messages.value + assistantMessage
            }

            PluginResult.Success(
                mapOf(
                    "response" to responseText,
                    "messageId" to assistantMessageId,
                    "mode" to usedMode
                )
            )

        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Failed to process query")
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

    suspend fun getChatHistory(username: String, limit: Int = 100): PluginResult {
        return try {
            val history = withContext(Dispatchers.IO) {
                database.chatMessageDao().getMessages(username, limit)
            }
            PluginResult.Success(history)
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Lỗi khi lấy lịch sử chat")
        }
    }
}