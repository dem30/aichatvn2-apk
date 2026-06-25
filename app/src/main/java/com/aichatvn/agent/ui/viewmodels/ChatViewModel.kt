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

    // Điểm tối ưu lớn nhất: Ngăn chặn gửi nhiều câu hỏi đồng thời lên AI nhờ cờ nguyên tử
    private val isProcessingQuery = AtomicBoolean(false)

    val voiceManager: VoiceAssistantManager = VoiceAssistantManager(
        context = context,
        onListeningStateChange = { listening -> _isListening.value = listening },
        onTextRecognized = { text -> sendMessage(text) },
        onMaxSTTFailuresReached = { errorMsg ->
            logger.e("ChatViewModel", "Ngắt luồng voice do lỗi Mic liên tiếp: $errorMsg")
            viewModelScope.launch(Dispatchers.Main) {
                _voiceModeActive.value = false
                _pausedDueToError.value = true
                // ✅ SỬ DỤNG: voiceManager.speak thay vì trực tiếp ttsHelper
                voiceManager.speak("Đã tạm dừng nhận diện giọng nói do thiết bị lỗi microphone liên tiếp. Vui lòng kiểm tra quyền truy cập hoặc micro.")
            }
        }
    )

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

    @Volatile private var isInForeground = true

    init {
        viewModelScope.launch {
            chatSkill.initialize()
            observeAndSpeak()
            startVoiceSession()
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
                // ✅ SỬ DỤNG: voiceManager.speak
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
                    // ✅ SỬ DỤNG: voiceManager.speak dập mic tự động
                    voiceManager.speak(
                        "Tôi đang gặp lỗi kết nối mạng nhiều lần liên tiếp. " +
                            "Tôi sẽ tạm dừng nghe để tránh làm phiền bạn. " +
                            "Nhờ người chăm sóc kiểm tra mạng và bật mic lại khi sẵn sàng."
                    )
                    return@collect
                }

                // ✅ SỬ DỤNG: voiceManager.speak thay vì tts.speak
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
        // Tối ưu Agent: Khóa luồng yêu cầu ngay lập tức để tránh các yêu cầu trùng lặp
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
            } finally {
                _isLoading.value = false
                isProcessingQuery.set(false) // Giải phóng khóa xử lý sau khi hoàn tất
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