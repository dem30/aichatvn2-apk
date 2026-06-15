package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.core.plugin.AgentCore
import com.aichatvn.agent.core.plugin.PluginContext
import com.aichatvn.agent.core.plugin.PluginRegistry
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentCore: AgentCore,
    private val pluginRegistry: PluginRegistry,
    private val pluginContext: PluginContext,
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            chatSkill.initialize()
            pluginRegistry.initializeAll(pluginContext)
        }
    }

    fun setChatMode(mode: ChatMode) {
        chatSkill.setChatMode(mode)
    }

    fun sendMessage(message: String) {
        sendMessageWithImage(message, null)
    }

    fun sendMessageWithImage(message: String, imageUri: Uri?) {
        viewModelScope.launch {
            _isLoading.value = true

            var fileUrl: String? = null
            var base64Image: String? = null

            imageUri?.let { uri ->
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val imageBytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (imageBytes != null) {
                        val tempFile = File(context.cacheDir, "chat_img_${UUID.randomUUID()}.jpg")
                        FileOutputStream(tempFile).use { it.write(imageBytes) }
                        fileUrl = tempFile.absolutePath
                        base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    logger.e("ChatViewModel", "Lỗi xử lý ảnh: ${e.message}", e)
                }
            }

            val userMessageContent = when {
                base64Image != null && message.isNotBlank() -> "[Hình ảnh] $message"
                base64Image != null -> "[Hình ảnh]"
                else -> message
            }

            val response: AgentResponse = if (base64Image != null) {
                // Có ảnh → vẫn dùng ChatSkill
                chatSkill.processQuery(
                    message = userMessageContent,
                    username = "default_user",
                    fileUrl = fileUrl,
                    imageBase64 = base64Image
                )
            } else {
                // Dùng AgentCore để xử lý plugin
                agentCore.process(userMessageContent, "default_user")
            }
            
            if (response.success && response.data is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val dataMap = response.data as? Map<String, Any>
                val responseText = dataMap?.get("response") as? String
                if (responseText != null && responseText.isNotBlank()) {
                    logger.d("ChatViewModel", "Response: $responseText")
                }
            } else if (!response.success) {
                logger.e("ChatViewModel", "Error: ${response.error}")
            }
            
            _isLoading.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatSkill.clearHistory("default_user")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            pluginRegistry.shutdownAll()
        }
    }
}