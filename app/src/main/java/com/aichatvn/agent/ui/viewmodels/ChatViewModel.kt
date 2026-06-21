package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.ai.GroqRateLimitInfo
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
import android.util.Base64

/**
 * ✅ UI model cho 1 chip lệnh gợi ý — text là chuỗi sẽ điền vào ô nhập khi bấm chip.
 */
data class QuickCommand(val label: String, val text: String)

/**
 * ✅ UI model cho 1 tab (= 1 plugin) trong thanh gợi ý lệnh nhanh.
 */
data class QuickCommandGroup(val tabLabel: String, val commands: List<QuickCommand>)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentKernel: AgentKernel, // ✅ để build chip lệnh động từ danh sách plugin
    private val groqClient: GroqClientTool, // ✅ để hiện label rate-limit token/cooldown
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode

    /** ✅ rate-limit Groq của model CHAT (token còn lại, cooldown) — null khi chưa gọi Groq lần nào. */
    val groqRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.rateLimitInfo

    /** ✅ MỚI: rate-limit Groq của model ROUTER (phân loại lệnh) — cập nhật ở MỌI tin nhắn,
     *  kể cả khi tin nhắn đó cuối cùng là lệnh thiết bị (không gọi tới model chat). */
    val groqRouterRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.routerRateLimitInfo

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * ✅ MỚI: Danh sách chip lệnh gợi ý, build TỰ ĐỘNG từ Plugin.getActions() của mỗi
     * plugin đang visibleInQuickBar (ChatSkill tự ẩn vì visibleInQuickBar=false).
     *
     * Không hardcode tên plugin/action ở đây -> thêm plugin mới ở AppModule là tự
     * xuất hiện 1 tab mới, không cần sửa UI.
     *
     * Plugin list cố định theo vòng đời Singleton của Hilt (không đổi lúc runtime),
     * nên build 1 lần là đủ, không cần StateFlow.
     */
    val quickCommandGroups: List<QuickCommandGroup> =
        agentKernel.getAvailablePluginsForUI().map { plugin ->
            val commands = plugin.getActions().map { action ->
                val requiredParams = action.parameters.filter { it.required }
                val text = if (requiredParams.isEmpty()) {
                    action.description
                } else {
                    // Gợi ý placeholder cho tham số bắt buộc, người dùng tự điền giá trị
                    // thật trước khi gửi. Ví dụ: "Bật hoặc tắt camera (<customerId>, <active>)"
                    action.description + " (" +
                        requiredParams.joinToString(", ") { "<${it.name}>" } + ")"
                }
                QuickCommand(label = action.name, text = text)
            }
            QuickCommandGroup(tabLabel = plugin.name, commands = commands)
        }

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
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val imageBytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (imageBytes != null) {
                        val tempFile = File(context.cacheDir, "chat_img_${UUID.randomUUID()}.jpg")
                        FileOutputStream(tempFile).use { it.write(imageBytes) }
                        fileUrl = tempFile.absolutePath
                        base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
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

            // ✅ LUÔN gọi ChatSkill - ChatSkill tự routing
            val response = chatSkill.processQuery(
                message = userMessageContent,
                username = "default_user",
                fileUrl = fileUrl,
                imageBase64 = base64Image
            )

            if (response is PluginResult.Failure) {
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
}