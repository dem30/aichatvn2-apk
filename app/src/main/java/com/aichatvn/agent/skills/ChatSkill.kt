// ChatSkill.kt - THÊM khả năng lưu message từ bên ngoài

package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.core.plugin.AgentCore
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.tools.ai.GroqClientTool
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
    private val agentCore: AgentCore,  // THÊM: để gọi khi cần điều khiển
    private val logger: Logger
) : BaseAgentSkill {

    override val skillName = "ChatSkill"

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

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    // THÊM: Phát hiện lệnh điều khiển thiết bị
    private fun isDeviceControlIntent(message: String): Boolean {
        val lower = message.lowercase()

        // FIX: Bỏ điều kiện "length < 50" — nguyên nhân gốc rễ khiến "hi", "hello",
        // "xin chào" và mọi câu ngắn bị route sang AgentCore thay vì Groq chat.
        // Log: "Routing to AgentCore for device control: 'hi'" là bug này.
        //
        // Chỉ route sang AgentCore khi message chứa từ khóa điều khiển RÕ RÀNG.
        // Phân nhóm để dễ maintain:

        // Động từ điều khiển thiết bị (phải đi kèm đối tượng, nhưng check đơn giản trước)
        val actionVerbs = listOf("bật", "tắt", "khởi động lại", "ngưng", "dừng hẳn", "reset")

        // Đối tượng cụ thể trong hệ thống này
        val systemObjects = listOf(
            "camera", "đèn", "light", "đèn led",
            "chế độ thông minh", "smart mode", "giám sát"
        )

        // Lệnh gửi — phải có từ "gửi" hoặc "send" đi kèm
        val sendCommands = listOf("gửi mail", "gửi email", "gửi thông báo", "send email", "send mail")

        // Lệnh truy vấn hệ thống rõ ràng
        val queryCommands = listOf(
            "trạng thái camera", "camera status",
            "bao nhiêu camera", "danh sách camera",
            "xem log", "xem alert", "xem cảnh báo"
        )

        val hasAction = actionVerbs.any { lower.contains(it) }
        val hasObject = systemObjects.any { lower.contains(it) }
        val hasSendCommand = sendCommands.any { lower.contains(it) }
        val hasQueryCommand = queryCommands.any { lower.contains(it) }

        // Route sang AgentCore chỉ khi:
        // - Có động từ điều khiển VÀ đối tượng hệ thống ("bật camera", "tắt đèn")
        // - Hoặc lệnh gửi rõ ràng ("gửi email cho khách hàng")
        // - Hoặc query hệ thống rõ ràng ("trạng thái camera")
        return (hasAction && hasObject) || hasSendCommand || hasQueryCommand
    }

    // THÊM: Lưu message từ AgentCore response
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
    ): AgentResponse {
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

            // QUYẾT ĐỊNH ROUTING:
            // 1. Có ảnh -> ChatSkill (hỏi về ảnh)
            // 2. Lệnh điều khiển -> AgentCore
            // 3. Còn lại -> ChatSkill
            
            val hasImage = imageBase64 != null || fileUrl != null
            val isControl = !hasImage && isDeviceControlIntent(message)
            
            val responseText: String
            val usedMode: String
            
            if (hasImage) {
                // Có ảnh -> dùng ChatSkill với vision
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
            } else if (isControl) {
                // Lệnh điều khiển -> AgentCore
                usedMode = "device_control"
                logger.d("ChatSkill", "Routing to AgentCore for device control: '$message'")
                val agentResponse = agentCore.process(message, username)
                responseText = if (agentResponse.success) {
                    (agentResponse.data as? Map<*, *>)?.get("response") as? String 
                        ?: "✅ Đã thực hiện"
                } else {
                    agentResponse.error ?: "Không thể thực hiện"
                }
            } else {
                // Chat thuần -> ChatSkill với mode hiện tại
                usedMode = _chatMode.value.name
                val currentMode = _chatMode.value
                val historySnapshot = _messages.value.takeLast(10).map {
                    mapOf("role" to it.role, "content" to it.content)
                }
                
                responseText = try {
                    withTimeout(30_000L) {
                        when (currentMode) {
                            ChatMode.QA -> {
                                val qaResult = trainingSkill.fuzzyMatchQuestion(message, username, 0.6f)
                                if (qaResult.success && qaResult.data != null) {
                                    @Suppress("UNCHECKED_CAST")
                                    val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                                    val qa = matches.firstOrNull()?.get("qa") as? com.aichatvn.agent.data.model.QAEntity
                                    qa?.answer ?: "Không tìm thấy câu trả lời phù hợp."
                                } else {
                                    "Xin lỗi, tôi không tìm thấy câu trả lời cho câu hỏi này trong cơ sở dữ liệu Q&A."
                                }
                            }
                            ChatMode.COMBINED -> {
                                var qaContext = ""
                                var foundQA = false
                                val qaResult = trainingSkill.fuzzyMatchQuestion(message, username, 0.6f)
                                if (qaResult.success && qaResult.data != null) {
                                    @Suppress("UNCHECKED_CAST")
                                    val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                                    val qa = matches.firstOrNull()?.get("qa") as? com.aichatvn.agent.data.model.QAEntity
                                    if (qa != null) {
                                        foundQA = true
                                        qaContext = "Dữ liệu từ hệ thống Q&A:\nCÂU HỎI: ${qa.question}\nCÂU TRẢ LỜI THAM KHẢO: ${qa.answer}\n\n"
                                    }
                                }
                                val groqMessage = if (foundQA) {
                                    "$qaContext\nCâu hỏi của người dùng: $message\nHãy sử dụng thông tin từ Q&A làm cơ sở để trả lời, bổ sung thêm nếu cần."
                                } else {
                                    message
                                }
                                groqClient.chat(
                                    message = groqMessage,
                                    extraContext = extraContext,
                                    history = historySnapshot,
                                    imageUrl = null
                                )
                            }
                            ChatMode.GROQ -> {
                                groqClient.chat(
                                    message = message,
                                    extraContext = extraContext,
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

            // Lưu assistant message (QUAN TRỌNG: cho CẢ 3 trường hợp)
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

            AgentResponse(
                success = true,
                data = mapOf(
                    "response" to responseText,
                    "messageId" to assistantMessageId,
                    "mode" to usedMode
                )
            )

        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message ?: "Failed to process query")
        }
    }

    suspend fun clearHistory(username: String): AgentResponse {
        return try {
            database.chatMessageDao().clearMessages(username)
            messagesMutex.withLock {
                _messages.value = emptyList()
            }
            AgentResponse(success = true, data = "History cleared")
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }

    suspend fun getChatHistory(username: String, limit: Int = 100): AgentResponse {
        return try {
            val history = database.chatMessageDao().getMessages(username, limit)
            AgentResponse(success = true, data = history)
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
}