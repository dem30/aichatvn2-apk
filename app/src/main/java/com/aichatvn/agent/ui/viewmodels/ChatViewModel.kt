package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentRequest
import com.aichatvn.agent.core.AgentRouter
import com.aichatvn.agent.core.IntentType
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.skills.ChatSkill
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
import com.aichatvn.agent.utils.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentRouter: AgentRouter,
    @ApplicationContext private val context: Context
    , private val logger: Logger
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            chatSkill.initialize()
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
                    val contentResolver = context.contentResolver
                    val inputStream = contentResolver.openInputStream(uri)
                    val imageBytes = inputStream?.readBytes()
                    if (imageBytes != null) {
                        // Lưu file local để lưu vào message
                        val tempFile = File(context.cacheDir, "chat_img_${UUID.randomUUID()}.jpg")
                        FileOutputStream(tempFile).use { output ->
                            output.write(imageBytes)
                        }
                        fileUrl = tempFile.absolutePath
                        
                        // Chuyển sang base64 để gửi API
                        base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    }
                    inputStream?.close()
                } catch (e: Exception) {
                    logger.e("ChatViewModel", "Lỗi xử lý ảnh: ${e.message}", e)
                }
            }
            
            // Tạo user message với ảnh
            val userMessageContent = if (base64Image != null) {
                if (message.isNotBlank()) "[Hình ảnh] $message" else "[Hình ảnh]"
            } else {
                message
            }
            
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.CHAT_QUERY,
                    payload = mapOf(
                        "message" to userMessageContent,
                        "context" to "",
                        "fileUrl" to (fileUrl ?: ""),
                        "imageBase64" to (base64Image ?: "")
                    ),
                    username = "default_user"
                )
            )
            _isLoading.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatSkill.clearHistory("default_user")
        }
    }
}