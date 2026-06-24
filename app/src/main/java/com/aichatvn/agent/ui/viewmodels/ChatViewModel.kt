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
import kotlinx.coroutines.Dispatchers
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

    // ✅ FIX: Khởi tạo voiceManager NGAY (không lazy) để TTS bắt đầu init sớm nhất có thể.
    // Trước đây dùng `by lazy` → TTS chỉ bắt đầu init khi voiceManager được truy cập lần đầu,
    // thường là trong startVoiceSession() → check isReady ngay lập tức → luôn false vì TTS
    // init là async (callback TextToSpeech.OnInitListener) chưa kịp chạy → bỏ qua lời chào,
    // hoặc gọi startListening() trong khi TTS chưa sẵn sàng gây xung đột audio session.
    val voiceManager: VoiceAssistantManager = VoiceAssistantManager(
        context = context,
        onListeningStateChange = { listening -> _isListening.value = listening },
        onTextRecognized = { text -> sendMessage(text) }
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

    init {
        isInForeground = true   // app khởi động = đang ở foreground
        viewModelScope.launch {
            chatSkill.initialize()
            observeAndSpeak()
            startVoiceSession()
        }
    }

    /**
     * Khởi động phiên voice:
     * 1. Chờ TTS sẵn sàng — poll với timeout 5 giây (TTS init thường < 1 giây)
     * 2. Đọc lời chào
     * 3. Bắt đầu lắng nghe trong callback onDone của lời chào
     *
     * ✅ FIX: Chạy trên Dispatchers.Main để SpeechRecognizer.createSpeechRecognizer()
     * và startListening() luôn được gọi trên Main thread — yêu cầu bắt buộc của Android.
     * Vi phạm → silently fail (không throw, không crash, chỉ không nhận giọng nói).
     */
    private fun startVoiceSession() {
        viewModelScope.launch(Dispatchers.Main) {
            if (!_voiceModeActive.value) return@launch

            // ✅ FIX: Poll isReady với timeout 5 giây thay vì 3 giây.
            // TTS.OnInitListener chạy trên Main thread — nếu ta đang block Main thread
            // bằng delay() thì Dispatchers.Main scheduler vẫn xử lý được vì delay() suspend
            // (không block thread thật), cho phép TTS callback chạy xen vào giữa các delay.
            var waited = 0
            while (!voiceManager.ttsHelper.isReady && waited < 5_000) {
                delay(100)
                waited += 100
            }

            if (!_voiceModeActive.value) return@launch

            if (voiceManager.ttsHelper.isReady) {
                // ✅ startListening() gọi trong onDone callback — KHÔNG gọi trực tiếp ở đây.
                // onDone chạy sau khi TTS đọc xong → đảm bảo mic không tranh audio session với TTS.
                voiceManager.ttsHelper.speak("Xin chào, tôi đang nghe. Bạn cần gì?") {
                    if (_voiceModeActive.value) voiceManager.startListening()
                }
            } else {
                // TTS không sẵn sàng sau 5 giây (thiết bị không có TTS engine?) → nghe luôn
                voiceManager.startListening()
            }
        }
    }

    /**
     * Observe messages: khi AI trả lời xong → TTS đọc to → startListening() lại.
     * Đây là vòng lặp hands-free chính.
     *
     * drop(1): bỏ qua emit đầu tiên (lịch sử cũ load từ DB).
     */
    private fun observeAndSpeak() {
        // ✅ FIX: Dùng Dispatchers.Main để startListening() trong TTS onDone callback
        // luôn được dispatch đúng thread — TTS onDone có thể gọi từ thread bất kỳ.
        viewModelScope.launch(Dispatchers.Main) {
            messages.drop(1).collect { msgs ->
                val last = msgs.lastOrNull() ?: return@collect
                if (last.role != "assistant") return@collect
                if (!_voiceModeActive.value) return@collect

                val tts = voiceManager.ttsHelper
                if (!tts.isReady) return@collect

                if (last.sourcePlugin == "router_error") {
                    consecutiveRouterFailures++
                } else {
                    consecutiveRouterFailures = 0
                }

                if (consecutiveRouterFailures >= MAX_CONSECUTIVE_ROUTER_FAILURES) {
                    consecutiveRouterFailures = 0
                    _voiceModeActive.value = false
                    _pausedDueToError.value = true
                    voiceManager.stopListening()
                    tts.speak(
                        "Tôi đang gặp lỗi kết nối mạng nhiều lần liên tiếp. " +
                            "Tôi sẽ tạm dừng nghe để tránh làm phiền bạn. " +
                            "Nhờ người chăm sóc kiểm tra mạng và bật mic lại khi sẵn sàng."
                    )
                    return@collect
                }

                tts.speak(last.content) {
                    if (_voiceModeActive.value) {
                        // onDone callback có thể ở thread bất kỳ — post về Main qua Handler
                        // thay vì launch coroutine để tránh phụ thuộc vào scope availability.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (_voiceModeActive.value) {
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

    // true = app đang ở foreground; false = đã vào background.
    // Guard này ngăn onForeground() restart recognizer đang hoạt động bình thường.
    @Volatile private var isInForeground = false

    /**
     * Gọi từ ChatScreen khi app vào foreground (ON_START).
     * ON_START chỉ bắn khi thật sự quay lại từ background — không bắn khi
     * dialog/keyboard xuất hiện, khác với ON_RESUME bắn liên tục.
     */
    fun onForeground() {
        if (isInForeground) return   // tránh double-call
        isInForeground = true
        if (!_voiceModeActive.value) return

        // Destroy recognizer cũ (có thể đã bị Android kill âm thầm khi background)
        // rồi tạo session mới hoàn toàn với audio focus mới.
        voiceManager.stopListening()
        viewModelScope.launch(Dispatchers.Main) {
            delay(400)  // chờ audio system release sau khi app trở lại foreground
            if (_voiceModeActive.value && isInForeground) voiceManager.startListening()
        }
    }

    /**
     * Gọi từ ChatScreen khi app vào background (ON_STOP).
     * Dừng mic + TTS chủ động — tránh nhận/phát âm khi user đang ở app khác.
     */
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