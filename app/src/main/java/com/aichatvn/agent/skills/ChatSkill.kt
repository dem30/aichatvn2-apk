package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.tools.ai.GroqClientTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val logger: Logger
) : BaseAgentSkill {

    override val skillName = "ChatSkill"

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.GROQ)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private val username = "default_user"

    override suspend fun initialize() {
        _messages.value = database.chatMessageDao().getMessages(username, 500)
    }

    override suspend fun shutdown() {}

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    suspend fun processQuery(
        message: String,
        extraContext: String = "",
        username: String,
        fileUrl: String? = null,
        imageBase64: String? = null
    ): AgentResponse {
        return try {
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

            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(userMessage)
            _messages.value = currentMessages

            val currentMode = _chatMode.value
            val response: String

            // Tạo dataUrl cho ảnh — lọc chuỗi rỗng bằng isNullOrEmpty()
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
                } catch (e: Exception) {
                    null
                }
            } else null

            when (currentMode) {
                ChatMode.QA -> {
                    val qaResult = trainingSkill.fuzzyMatchQuestion(message, username, 0.6f)
                    if (qaResult.success && qaResult.data != null) {
                        @Suppress("UNCHECKED_CAST")
                        val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                        val qa = matches.firstOrNull()?.get("qa") as? QAEntity
                        response = qa?.answer ?: "Không tìm thấy câu trả lời phù hợp."
                    } else {
                        response = "Xin lỗi, tôi không tìm thấy câu trả lời cho câu hỏi này trong cơ sở dữ liệu Q&A."
                    }
                }

                ChatMode.COMBINED -> {
                    var qaContext = ""
                    var foundQA = false

                    val qaResult = trainingSkill.fuzzyMatchQuestion(message, username, 0.6f)
                    if (qaResult.success && qaResult.data != null) {
                        @Suppress("UNCHECKED_CAST")
                        val matches = qaResult.data as? List<Map<String, Any>> ?: emptyList()
                        val qa = matches.firstOrNull()?.get("qa") as? QAEntity
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

                    response = groqClient.chat(
                        message = groqMessage,
                        extraContext = extraContext,
                        history = _messages.value.takeLast(10).map {
                            mapOf("role" to it.role, "content" to it.content)
                        },
                        imageUrl = imageDataUrl
                    )
                }

                ChatMode.GROQ -> {
                    response = groqClient.chat(
                        message = message,
                        extraContext = extraContext,
                        history = _messages.value.takeLast(10).map {
                            mapOf("role" to it.role, "content" to it.content)
                        },
                        imageUrl = imageDataUrl
                    )
                }
            }

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

            currentMessages.add(assistantMessage)
            _messages.value = currentMessages

            AgentResponse(
                success = true,
                data = mapOf("response" to response, "messageId" to assistantMessageId, "mode" to currentMode.name)
            )

        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message ?: "Failed to process query")
        }
    }

    suspend fun clearHistory(username: String): AgentResponse {
        return try {
            database.chatMessageDao().clearMessages(username)
            _messages.value = emptyList()
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