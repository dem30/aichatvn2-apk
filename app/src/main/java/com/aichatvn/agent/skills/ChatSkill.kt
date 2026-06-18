// ChatSkill.kt

package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseSkill
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
    private val agentKernel: AgentKernel,
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger) {

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
        val queryCommands = listOf("trạng thái", "status", "bao nhiêu", "danh sách", "xem log", "xem alert")

        val hasAction = actionVerbs.any { lower.contains(it) }
        val hasObject = systemObjects.any { lower.contains(it) }
        val hasSendCommand = sendCommands.any { lower.contains(it) }
        val hasQueryCommand = queryCommands.any { lower.contains(it) }

        return (hasAction && hasObject) || hasSendCommand || hasQueryCommand
    }

    /**
     * ✅ HÀM PHỐI HỢP VỚI TRAININGSKILL
     * 
     * Build QA context cho Groq chat (không phải lệnh điều khiển)
     */
    private suspend fun buildQAContext(message: String, username: String): String {
        val result = trainingSkill.fuzzyMatchQuestion(message, username, 0.5f)
        if (!result.success || result.data == null) return ""
        
        @Suppress("UNCHECKED_CAST")
        val matches = result.data as? List<Map<String, Any>> ?: return ""
        if (matches.isEmpty()) return ""
        
        return matches.joinToString("\n") { match ->
            val qa = match["qa"] as? com.aichatvn.agent.data.model.QAEntity
            val similarity = match["similarity"] as? Float ?: 0f
            if (qa != null) {
                "📚 Q: ${qa.question}\n   A: ${qa.answer} (độ tương tự: ${String.format("%.2f", similarity)})"
            } else ""
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
                    trainingSkill.addQA(key, value, "general", username)
                    responseText = "✅ Đã học: $key → $value"
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
                
                val kernelResult = agentKernel.process(message)  // ← AgentKernel tự search QA
                responseText = when (kernelResult) {
                    is AgentKernel.PluginResult.Success -> {
                        val data = kernelResult.data as? Map<*, *>
                        data?.get("message") as? String ?: "✅ Đã thực hiện"
                    }
                    is AgentKernel.PluginResult.Failure -> kernelResult.error
                    is AgentKernel.PluginResult.NeedMoreInfo -> kernelResult.question
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
                                if (qaResult.success && qaResult.data != null) {
                                    @Suppress("UNCHECKED_CAST")
                                    val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                                    val qa = matches.firstOrNull()?.get("qa") as? com.aichatvn.agent.data.model.QAEntity
                                    qa?.answer ?: "Không tìm thấy câu trả lời phù hợp."
                                } else {
                                    "Xin lỗi, tôi không tìm thấy câu trả lời cho câu hỏi này."
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