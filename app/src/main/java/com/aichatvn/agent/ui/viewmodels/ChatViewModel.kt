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

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentRouter: AgentRouter,
    @ApplicationContext private val context: Context
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
            
            // FIXED: Sử dụng context đã inject thay vì LocalContext.current
            imageUri?.let { uri ->
                try {
                    val contentResolver = context.contentResolver
                    val inputStream = contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "chat_img_${UUID.randomUUID()}.jpg")
                    FileOutputStream(tempFile).use { output ->
                        inputStream?.copyTo(output)
                    }
                    fileUrl = tempFile.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.CHAT_QUERY,
                    payload = mapOf(
                        "message" to message,
                        "context" to "",
                        "fileUrl" to (fileUrl ?: "")
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