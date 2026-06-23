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
import com.aichatvn.agent.utils.VoiceAssistantManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import android.util.Base64

data class QuickCommand(val label: String, val text: String)
data class QuickCommandGroup(val tabLabel: String, val commands: List<QuickCommand>)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentKernel: AgentKernel,
    private val groqClient: GroqClientTool,
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode
    val groqRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.rateLimitInfo
    val groqRouterRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.routerRateLimitInfo

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Voice ────────────────────────────────────────────────────────────────

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // true = chế độ hands-free đang bật (loop liên tục)
    // false = đã tắt thủ công (người chăm sóc tắt hộ)
    private val _voiceModeActive = MutableStateFlow(true)
    val voiceModeActive: StateFlow<Boolean> = _voiceModeActive.asStateFlow()

    // Tách delegate ra để kiểm tra isInitialized() đúng cách trong onCleared()
    private val _voiceManagerDelegate = lazy {
        VoiceAssistantManager(
            context = context,
            onListeningStateChange = { listening -> _isListening.value = listening },
            onTextRecognized = { text -> sendMessage(text) }
        )
    }
    val voiceManager: VoiceAssistantManager by _voiceManagerDelegate

    // ── Quick commands ────────────────────────────────────────────────────────

    val quickCommandGroups: List<QuickCommandGroup> =
        agentKernel.getAvailablePluginsForUI().map { plugin ->
            val commands = plugin.getActions().map { action ->
                val requiredParams = action.parameters.filter { it.required }
                val text = if (requiredParams.isEmpty()) action.description
                else action.description + " (" +
                    requiredParams.joinToString(", ") { "<${it.name}>" } + ")"
                QuickCommand(label = action.name, text = text)
            }
            QuickCommandGroup(tabLabel = plugin.name, commands = commands)
        }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            chatSkill.initialize()
            startVoiceSession()   // Khởi động voice ngay sau khi chat ready
        }
        observeAndSpeak()
    }

    /**
     * Khởi động phiên voice:
     * 1. Chờ TTS sẵn sàng (tối đa 3 giây)
     * 2. Đọc lời chào
     * 3. Bắt đầu lắng nghe lần đầu
     */
    private fun startVoiceSession() {
        viewModelScope.launch {
            // Chờ TTS init xong — dùng while thay vì repeat để thoát đúng
            var waited = 0
            while (!voiceManager.ttsHelper.isReady && waited < 3000) {
                delay(100)
                waited += 100
            }
            if (!_voiceModeActive.value) return@launch

            if (voiceManager.ttsHelper.isReady) {
                voiceManager.ttsHelper.speak("Xin chào, tôi đang nghe. Bạn cần gì?") {
                    if (_voiceModeActive.value) voiceManager.startListening()
                }
            } else {
                // TTS chưa sẵn sàng sau 3 giây → bắt đầu nghe luôn, không chào
                voiceManager.startListening()
            }
        }
    }

    /**
     * Observe messages: khi AI trả lời xong → TTS đọc to → startListening() lại.
     * Đây là vòng lặp hands-free chính.
     *
     * drop(1): bỏ qua emit đầu tiên (tin nhắn cũ load từ DB).
     */
    private fun observeAndSpeak() {
        viewModelScope.launch {
            messages.drop(1).collect { msgs ->
                val last = msgs.lastOrNull() ?: return@collect
                if (last.role != "assistant") return@collect
                if (!_voiceModeActive.value) return@collect

                val tts = voiceManager.ttsHelper
                if (!tts.isReady) return@collect

                tts.speak(last.content) {
                    // TTS đọc xong → startListening() lại để tiếp tục loop
                    // VoiceAssistantManager tự xử lý silence/error restart
                    // nên ở đây chỉ cần gọi khi voiceMode còn bật
                    if (_voiceModeActive.value) {
                        viewModelScope.launch {
                            delay(300)
                            voiceManager.startListening()
                        }
                    }
                }
            }
        }
    }

    // ── Voice controls (cho nút toggle trên UI, người chăm sóc dùng) ─────────

    fun toggleVoiceMode() {
        val next = !_voiceModeActive.value
        _voiceModeActive.value = next
        if (next) {
            voiceManager.startListening()
        } else {
            voiceManager.stopListening()
            voiceManager.ttsHelper.stop()
        }
    }

    // ── Chat actions ──────────────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        if (_voiceManagerDelegate.isInitialized()) {
            voiceManager.destroy()
        }
    }
}
