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

// ✅ Số lần lỗi router LIÊN TIẾP tối đa trước khi tự tạm dừng hands-free.
// 3 lần: đủ chịu được vài lần mạng chập chờn ngắn, nhưng không để loop chạy
// vô tận khi mạng/server lỗi kéo dài — đúng yêu cầu "tier 1 lỗi thì phải dừng".
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

    // true = chế độ hands-free đang bật (loop liên tục)
    // false = đã tắt thủ công (người chăm sóc tắt hộ) HOẶC bị tự tạm dừng do lỗi liên tiếp
    private val _voiceModeActive = MutableStateFlow(true)
    val voiceModeActive: StateFlow<Boolean> = _voiceModeActive.asStateFlow()

    // ✅ MỚI: true khi hands-free bị TỰ ĐỘNG tạm dừng do lỗi router liên tiếp (không phải
    // người chăm sóc tắt thủ công) — UI dùng cờ này để hiện thông báo rõ ràng, khác với
    // trạng thái "tắt thủ công" bình thường.
    private val _pausedDueToError = MutableStateFlow(false)
    val pausedDueToError: StateFlow<Boolean> = _pausedDueToError.asStateFlow()

    private var consecutiveRouterFailures = 0

    val voiceManager: VoiceAssistantManager by lazy {
        VoiceAssistantManager(
            context = context,
            onListeningStateChange = { listening -> _isListening.value = listening },
            onTextRecognized = { text -> sendMessage(text) }
        )
    }

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
        // ✅ FIX #1: Trước đây observeAndSpeak() được gọi NGOÀI viewModelScope.launch, chạy
        // song song không đảm bảo thứ tự với chatSkill.initialize(). Nếu collector của
        // observeAndSpeak() bắt đầu lắng nghe TRƯỚC khi initialize() set _messages.value =
        // lịch sử cũ từ DB, thì emission "lịch sử cũ" sẽ KHÔNG bị drop(1) nuốt (vì lúc đó nó
        // không phải là emission đầu tiên nữa) → app có thể tự TTS đọc lại tin nhắn assistant
        // CŨ và tự startListening() ngay khi vừa mở app, dù người dùng chưa nói gì.
        // Giờ gọi observeAndSpeak() NGAY SAU khi initialize() hoàn tất, trong cùng 1 coroutine
        // tuần tự → đảm bảo emission đầu tiên collector thấy luôn là lịch sử đã load, và
        // drop(1) sẽ nuốt đúng nó một cách nhất quán (deterministic), không còn phụ thuộc
        // vào tốc độ dispatcher.
        viewModelScope.launch {
            chatSkill.initialize()
            observeAndSpeak()
            startVoiceSession()   // Khởi động voice ngay sau khi chat ready
        }
    }

    /**
     * Khởi động phiên voice:
     * 1. Chờ TTS sẵn sàng (tối đa 3 giây)
     * 2. Đọc lời chào
     * 3. Bắt đầu lắng nghe lần đầu
     */
    private fun startVoiceSession() {
        // ✅ FIX: Dùng Dispatchers.Main để đảm bảo SpeechRecognizer được tạo trên đúng
        // Main thread — Android yêu cầu điều này tuyệt đối, vi phạm = silently fail
        // (app hiện "Đang nghe" nhưng mic thật ra không mở, không có âm thanh "tút").
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
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

                // ✅ MỚI: đếm lỗi router liên tiếp (sourcePlugin == "router_error" do
                // ChatSkill gắn khi AgentKernel.RouterOutcome.RouterFailed — xem GroqClientTool
                // .routeIntent() đã throw GroqRoutingException thay vì nuốt lỗi như trước).
                // Theo đúng yêu cầu: "tier 1 lỗi thì phải dừng" — lỗi 1-2 lần có thể chỉ là
                // chập chờn tạm thời (không dừng vội làm phiền người dùng), nhưng từ lần thứ
                // MAX_CONSECUTIVE_ROUTER_FAILURES liên tiếp trở đi gần như chắc chắn là mất
                // mạng/server lỗi kéo dài → chủ động dừng hands-free, đọc to lý do, và để
                // banner báo người chăm sóc biết cần kiểm tra mạng + bật lại mic.
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
                    // Chủ động KHÔNG startListening() lại — đây là điểm dừng có chủ đích,
                    // khác hẳn các restart tự động khác trong VoiceAssistantManager.
                    return@collect
                }

                tts.speak(last.content) {
                    // TTS onDone callback có thể chạy trên bất kỳ thread nào.
                    // ✅ FIX: Dùng Dispatchers.Main để startListening() luôn chạy trên
                    // Main thread — đây là điều kiện bắt buộc của Android SpeechRecognizer.
                    if (_voiceModeActive.value) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            delay(300)
                            if (_voiceModeActive.value) {
                                voiceManager.startListening()
                            }
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
        // ✅ Bật lại hands-free (thủ công, sau khi bị tự tạm dừng do lỗi) → xoá cờ báo lỗi
        // và reset bộ đếm, cho app một cơ hội mới hoàn toàn sạch.
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

    override fun onCleared() {
        super.onCleared()
        // ✅ FIX: Xoá kiểm tra `if (::voiceManager.isInitialized)` vì:
        // 1. voiceManager dùng `by lazy`, không phải `lateinit var`
        // 2. `::voiceManager.isInitialized` chỉ hoạt động với lateinit, gây lỗi compile
        // 3. Gọi destroy() trực tiếp hoàn toàn an toàn:
        //    - Nếu lazy chưa khởi tạo → khởi tạo + ngay lập tức set destroyed=true + cleanup
        //    - Nếu lazy đã khởi tạo → destroy bình thường
        //    - VoiceAssistantManager có flag destroyed guard tất cả methods
        voiceManager.destroy()
    }
}