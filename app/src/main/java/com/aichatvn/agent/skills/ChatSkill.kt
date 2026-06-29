package com.aichatvn.agent.skills

import android.content.Context
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentKernel: AgentKernel, // Độc tôn điều phối trung tâm
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger), Plugin {

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(), // Năng lực cơ bản mặc định
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
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
    )

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

            // 1. Ghi nhận và hiển thị tức thời tin nhắn người dùng lên UI
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

            // 2. Chuyển giao toàn bộ việc điều phối quyết định cho AgentKernel duy nhất
            val response = agentKernel.chat(
                AgentKernel.ChatRequest(
                    message = message,
                    username = username,
                    imageBase64 = imageBase64,
                    fileUrl = fileUrl,
                    extraContext = extraContext,
                    chatMode = _chatMode.value.name
                )
            )

            // 3. Ghi nhận phản hồi và hiển thị tức thời lên UI
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

            PluginResult.Success(
                mapOf(
                    "response" to response.responseText,
                    "messageId" to assistantMessageId,
                    "mode" to response.usedMode
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