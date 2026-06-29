package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase // Import thêm DB để lưu vết giọng nói
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.ai.GroqRateLimitInfo
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.VoiceAssistantManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class QuickCommand(val label: String, val text: String)
data class QuickCommandGroup(val tabLabel: String, val commands: List<QuickCommand>)

private const val MAX_CONSECUTIVE_ROUTER_FAILURES = 3

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentKernel: AgentKernel,
    private val groqClient: GroqClientTool,
    private val database: AppDatabase, // Tiêm DB phục vụ ghi nhật ký voice tự động
    val voiceManager: VoiceAssistantManager, // ✅ ĐÃ TIÊM: Sử dụng Singleton từ Hilt thay vì khởi tạo thủ công
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

    private val _voiceModeActive = MutableStateFlow(true)
    val voiceModeActive: StateFlow<Boolean> = _voiceModeActive.asStateFlow()

    private val _pausedDueToError = MutableStateFlow(false)
    val pausedDueToError: StateFlow<Boolean> = _pausedDueToError.asStateFlow()

    private var consecutiveRouterFailures = 0

    // Ngăn chặn gửi nhiều câu hỏi đồng thời lên AI nhờ cờ nguyên tử
    private val isProcessingQuery = AtomicBoolean(false)

    // ── Quick commands ────────────────────────────────────────────────────────

    val quickCommandGroups: List<QuickCommandGroup> = agentKernel.getAvailablePluginsForUI().map { plugin ->
        QuickCommandGroup(
            tabLabel = plugin.name,
            commands = plugin.getActions().map { action ->
                val requiredParams = action.parameters.filter { it.required }
                val text = if (requiredParams.isEmpty()) action.description
                else action.description + " (" + requiredParams.joinToString(", ") { "<${it.name}>" } + ")"
                QuickCommand(label = action.name, text = text)
            }
        )
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Volatile private var isInForeground = true

    init {
        viewModelScope.launch {
            chatSkill.initialize()
            observeVoiceManagerFlows() // ✅ ĐÃ SỬA: Lắng nghe trạng thái và dữ liệu Reactive Flow của Mic
            observeAndSpeak()
            startVoiceSession()
        }
    }

    // Lắng nghe dòng dữ liệu Reactive phát ra từ Singleton VoiceAssistantManager
    private fun observeVoiceManagerFlows() {
        // 1. Đồng bộ trạng thái bật/tắt Micro lên UI Chat
        viewModelScope.launch {
            voiceManager.isListening.collect { listening ->
                _isListening.value = listening
            }
        }

        // 2. Đồng bộ văn bản nhận diện được từ giọng nói vào DB để hiển thị lên khung Chat
        viewModelScope.launch {
            voiceManager.recognizedText.collect { text ->
                saveMessageToHistory(text, "user")
            }
        }

        // 3. Đồng bộ câu trả lời AI của cuộc thoại giọng nói vào DB để hiển thị lên khung Chat
        viewModelScope.launch {
            voiceManager.aiResponseText.collect { reply ->
                saveMessageToHistory(reply, "assistant")
            }
        }
    }

    // Ghi nhật ký cuộc thoại giọng nói xuống SQLite và kích hoạt cập nhật lại UI Flow
    private fun saveMessageToHistory(content: String, role: String) {
        viewModelScope.launch {
            val msg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionToken = "session_default_user",
                username = "default_user",
                content = content,
                role = role,
                type = "text",
                timestamp = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) {
                database.chatMessageDao().insertMessage(msg)
            }
            // Gọi nạp lại lịch sử hiển thị của ChatSkill
            chatSkill.initialize()
        }
    }

    private fun startVoiceSession() {
        viewModelScope.launch(Dispatchers.Main) {
            if (!_voiceModeActive.value || !isInForeground) return@launch

            var waited = 0
            while (!voiceManager.ttsHelper.isReady && waited < 5_000) {
                delay(100)
                waited += 100
                if (!isInForeground || !_voiceModeActive.value) return@launch
            }

            if (!_voiceModeActive.value || !isInForeground) return@launch

            if (voiceManager.ttsHelper.isReady) {
                voiceManager.speak("Xin chào, tôi đang nghe. Bạn cần gì?") {
                    if (_voiceModeActive.value && isInForeground) {
                        voiceManager.startListening()
                    }
                }
            } else {
                voiceManager.startListening()
            }
        }
    }

    private fun observeAndSpeak() {
        viewModelScope.launch(Dispatchers.Main) {
            messages.drop(1).collect { msgs ->
                val last = msgs.lastOrNull() ?: return@collect
                if (last.role != "assistant") return@collect
                if (!_voiceModeActive.value) return@collect

                // Nếu tin nhắn đến từ cuộc thoại giọng nói tự động thì bỏ qua, tránh đọc lặp lại hai lần
                if (last.sourcePlugin == "vision" || last.sourcePlugin == "learn" || last.sourcePlugin == "device_control") {
                    return@collect
                }

                if (!voiceManager.ttsHelper.isReady) {
                    logger.w("ChatViewModel", "TTS chưa sẵn sàng, kích hoạt nghe lại nhằm duy trì vòng lặp.")
                    if (_voiceModeActive.value && isInForeground) {
                        voiceManager.startListening()
                    }
                    return@collect
                }

                if (last.sourcePlugin == "router_error") {
                    consecutiveRouterFailures++
                } else {
                    consecutiveRouterFailures = 0
                }

                if (consecutiveRouterFailures >= MAX_CONSECUTIVE_ROUTER_FAILURES) {
                    consecutiveRouterFailures = 0
                    _voiceModeActive.value = false
                    _pausedDueToError.value = true
                    voiceManager.speak(
                        "Tôi đang gặp lỗi kết nối mạng nhiều lần liên tiếp. " +
                            "Tôi sẽ tạm dừng nghe để tránh làm phiền bạn. " +
                            "Nhờ người chăm sóc kiểm tra mạng và bật mic lại khi sẵn sàng."
                    )
                    return@collect
                }

                voiceManager.speak(last.content) {
                    if (_voiceModeActive.value && isInForeground) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (_voiceModeActive.value && isInForeground) {
                                voiceManager.startListening()
                            }
                        }, 300L)
                    }
                }
            }
        }
    }

    // ── Voice controls ────────────────────────────────────────────────────────

    fun toggleVoiceMode() {
        val next = !_voiceModeActive.value
        _voiceModeActive.value = next
        if (next) {
            _pausedDueToError.value = false
            consecutiveRouterFailures = 0
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
        if (!isProcessingQuery.compareAndSet(false, true)) {
            logger.w("ChatViewModel", "Đang xử lý yêu cầu cũ, bỏ qua yêu cầu trùng lặp.")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true

            var fileUrl: String? = null
            var base64Image: String? = null

            try {
                imageUri?.let { uri ->
                    try {
                        val imageBytes = withContext(Dispatchers.IO) {
                            getDownscaledImageBytes(context, uri)
                        }

                        if (imageBytes != null) {
                            withContext(Dispatchers.IO) {
                                val tempFile = File(context.cacheDir, "chat_img_${UUID.randomUUID()}.jpg")
                                FileOutputStream(tempFile).use { it.write(imageBytes) }
                                fileUrl = tempFile.absolutePath
                                base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            }
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
            } {
                _isLoading.value = false
                isProcessingQuery.set(false)
            }
        }
    }

    private fun getDownscaledImageBytes(context: Context, uri: Uri, maxDimension: Int = 1024): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= maxDimension && halfWidth / inSampleSize >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }

            val bitmap = context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            bitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatSkill.clearHistory("default_user")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onForeground() {
        if (isInForeground) return
        isInForeground = true
        if (!_voiceModeActive.value) return

        voiceManager.stopListening()
        viewModelScope.launch(Dispatchers.Main) {
            delay(400)
            if (_voiceModeActive.value && isInForeground) {
                voiceManager.startListening()
            }
        }
    }

    fun onBackground() {
        isInForeground = false
        voiceManager.stopListening()
        voiceManager.ttsHelper.stop()
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}