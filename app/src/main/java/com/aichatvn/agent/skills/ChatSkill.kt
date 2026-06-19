// ChatSkill.kt

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val agentKernel: AgentKernel,
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger), Plugin {

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.GROQ)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private var currentUsername: String = "default_user"
    private val messagesMutex = Mutex()

    override suspend fun initialize() {
        val loaded = database.chatMessageDao().getMessages(currentUsername, 500)
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

    /**
     * ✅ PHÁT HIỆN LỆNH ĐIỀU KHIỂN
     * 
     * Nếu là lệnh điều khiển → gọi AgentKernel
     * AgentKernel sẽ tự search QA context
     */
    private fun isDeviceControlIntent(message: String): Boolean {
        val lower = message.lowercase()

        val actionVerbs = listOf("bật", "tắt", "khởi động lại", "ngưng", "dừng hẳn", "reset", "gửi")
        val systemObjects = listOf("camera", "đèn", "light", "đèn led", "chế độ thông minh", "smart mode", "giám sát")
        val sendCommands = listOf("gửi mail", "gửi email", "gửi thông báo", "send email", "send mail")
        val queryCommands = listOf("trạng thái", "status", "bao nhiêu", "danh sách camera", "xem log", "xem alert")

        // ✅ Lệnh cấu hình camera (mới)
        val configCommands = listOf(
            "đặt từ khoá", "cập nhật từ khoá", "thêm từ khoá", "đổi từ khoá",
            "đặt prompt", "cập nhật prompt", "thay prompt", "đổi prompt",
            "cập nhật url", "thay url", "đổi url", "url camera",
            "cấu hình camera", "cập nhật camera", "thiết lập camera",
            "từ khoá cảnh báo", "từ khoá bình thường",
            "đặt vị trí", "cập nhật vị trí"
        )

        val hasAction = actionVerbs.any { lower.contains(it) }
        val hasObject = systemObjects.any { lower.contains(it) }
        val hasSendCommand = sendCommands.any { lower.contains(it) }
        val hasQueryCommand = queryCommands.any { lower.contains(it) }
        val hasConfigCommand = configCommands.any { lower.contains(it) }

        return (hasAction && hasObject) || hasSendCommand || hasQueryCommand || hasConfigCommand
    }

    /**
     * ✅ HÀM PHỐI HỢP VỚI TRAININGSKILL
     * 
     * Build QA context cho Groq chat (không phải lệnh điều khiển)
     */
    private suspend fun buildQAContext(message: String, username: String): String {
        val result = trainingSkill.fuzzyMatchQuestion(message, username, 0.5f)
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
        database.chatMessageDao().insertMessage(assistantMessage)
        
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
            if (currentUsername != username) {
                currentUsername = username
                val loaded = database.chatMessageDao().getMessages(username, 500)
                _messages.value = loaded
                logger.d("ChatSkill", "Username changed to '$username', reloaded ${loaded.size} messages")
            }

            // Lưu user message
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
            database.chatMessageDao().insertMessage(userMessage)

            messagesMutex.withLock {
                _messages.value = _messages.value + userMessage
            }

            // ============================================================
            // ROUTING:
            // 1. Lệnh học → TrainingSkill
            // 2. Có ảnh → Groq vision
            // 3. Lệnh điều khiển → AgentKernel (tự search QA)
            // 4. Chat thuần → Groq (có QA context)
            // ============================================================
            
            val responseText: String
            val usedMode: String
            
            // ✅ 1. LỆNH HỌC
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
                } else {
                    responseText = "❌ Cú pháp: Học: câu hỏi → câu trả lời"
                    usedMode = "learn_error"
                }
            }
            // ✅ 2. CÓ ẢNH
            else if (imageBase64 != null || fileUrl != null) {
                usedMode = "vision"
                val imageDataUrl = if (!imageBase64.isNullOrEmpty()) {
                    "data:image/jpeg;base64,$imageBase64"
                } else if (!fileUrl.isNullOrEmpty()) {
                    try {
                        val file = java.io.File(fileUrl)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            "data:image/jpeg;base64,$base64"
                        } else null
                    } catch (e: Exception) { null }
                } else null
                
                val historySnapshot = _messages.value.takeLast(10).map {
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
            // ✅ 3. LỆNH ĐIỀU KHIỂN → AGENTKERNEL (TỰ SEARCH QA)
            else if (isDeviceControlIntent(message)) {
                usedMode = "device_control"
                logger.d("ChatSkill", "Routing to AgentKernel for device control: '$message'")
                
                val kernelResult = agentKernel.process(message)
                responseText = when (kernelResult) {
                    is PluginResult.Success -> {
                        val data = kernelResult.data as? Map<*, *>
                        data?.get("message") as? String ?: "✅ Đã thực hiện"
                    }
                    is PluginResult.Failure -> kernelResult.error
                    else -> "Không thể xử lý lệnh"
                }
            }
            // ✅ 4. CHAT THUẦN → GROQ (CÓ QA CONTEXT)
            else {
                usedMode = _chatMode.value.name
                val currentMode = _chatMode.value
                val historySnapshot = _messages.value.takeLast(10).map {
                    mapOf("role" to it.role, "content" to it.content)
                }
                
                // ✅ Lấy QA context cho chat
                val qaContext = buildQAContext(message, username)
                val fullContext = if (qaContext.isNotEmpty()) {
                    "$qaContext\n\n$extraContext"
                } else {
                    extraContext
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

            // Lưu assistant message
            val assistantMessageId = UUID.randomUUID().toString()
            val assistantMessage = ChatMessageEntity(
                id = assistantMessageId,
                sessionToken = "session_$username",
                username = username,
                content = responseText,
                role = "assistant",
                type = "text",
                timestamp = System.currentTimeMillis()
            )
            database.chatMessageDao().insertMessage(assistantMessage)

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
            database.chatMessageDao().clearMessages(username)
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
            val history = database.chatMessageDao().getMessages(username, limit)
            PluginResult.Success(history)
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Lỗi khi lấy lịch sử chat")
        }
    }
}