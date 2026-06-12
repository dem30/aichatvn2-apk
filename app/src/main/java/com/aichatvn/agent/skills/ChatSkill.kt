package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groqClient: GroqClientTool
) : BaseAgentSkill {
    
    override val skillName = "ChatSkill"
    
    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    override suspend fun initialize() {
        // Load messages from database
        _messages.value = database.chatMessageDao().getMessages("default_user", 500)
    }
    
    override suspend fun shutdown() {
        // Cleanup if needed
    }
    
    suspend fun processQuery(
        message: String,
        context: String,
        username: String,
        fileUrl: String? = null
    ): AgentResponse {
        return try {
            // Save user message
            val userMessageId = UUID.randomUUID().toString()
            val userMessage = ChatMessageEntity(
                id = userMessageId,
                sessionToken = "session_$username",
                username = username,
                content = message,
                role = "user",
                type = if (fileUrl != null) "image" else "text",
                fileUrl = fileUrl,
                timestamp = System.currentTimeMillis()
            )
            database.chatMessageDao().insertMessage(userMessage)
            
            // Update state
            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(userMessage)
            _messages.value = currentMessages
            
            // Call Groq API
            val response = groqClient.chat(
                message = message,
                context = context,
                history = _messages.value.takeLast(10).map { 
                    mapOf("role" to it.role, "content" to it.content) 
                },
                imageUrl = fileUrl
            )
            
            // Save assistant message
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
            
            // Update state
            currentMessages.add(assistantMessage)
            _messages.value = currentMessages
            
            AgentResponse(
                success = true,
                data = mapOf("response" to response, "messageId" to assistantMessageId)
            )
            
        } catch (e: Exception) {
            AgentResponse(
                success = false,
                error = e.message ?: "Failed to process query"
            )
        }
    }
    
    suspend fun clearHistory(username: String): AgentResponse {
        return try {
            database.chatMessageDao().clearMessages(username)
            _messages.value = emptyList()
            AgentResponse(success = true, data = "History cleared")
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun getChatHistory(username: String, limit: Int = 100): AgentResponse {
        return try {
            val history = database.chatMessageDao().getMessages(username, limit)
            AgentResponse(success = true, data = history)
        } catch (e: Exception) {
            AgentResponse(success = false, error = e.message)
        }
    }
}