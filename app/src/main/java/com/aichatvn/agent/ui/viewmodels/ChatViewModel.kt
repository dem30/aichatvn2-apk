package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.SavedStateHandle // ✅ Đmax THÊM: Đọc tham số chuyển màn hình động
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
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
import kotlinx.coroutines.flow.Flow
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
    private val database: AppDatabase,
    val voiceManager: VoiceAssistantManager, // ✅ ĐÃ KHÔI PHỤC: Giữ nguyên tham số gốc của bạn
    @ApplicationContext private val context: Context, // ✅ ĐÃ KHÔI PHỤC: Giữ nguyên tham số gốc của bạn
    private val savedStateHandle: SavedStateHandle, // ✅ ĐÃ THÊM: SavedStateHandle tự lấy tham số từ NavController
    private val logger: Logger
) : ViewModel() {

    // ✅ TỰ ĐỘNG NHẬN DIỆN: Đọc tên người dùng từ tham số truyền sang (mặc định là default_user)
    val username: String = savedStateHandle.get<String>("username") ?: "default_user"

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode
    val groqRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.rateLimitInfo
    val groqRouterRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.routerRateLimitInfo

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    val partialText: StateFlow<String> = voiceManager.partialText

    // ✅ ĐÃ SỬA: Trước đây là MutableStateFlow(true) riêng của ViewModel — luôn mặc định "bật"
    // mỗi khi ViewModel được tạo mới, hoàn toàn không biết trạng thái mic THẬT (do notification/
    // Service điều khiển qua VoiceAssistantManager). Giờ đọc thẳng từ nguồn sự thật duy nhất
    // (voiceManager.micEnabled, đã lưu bền) — banner "Hands-free bật/tắt" trên ChatScreen sẽ luôn
    // khớp với trạng thái thật, dù bật/tắt từ notification, từ ChatScreen, hay sau khi app/Service
    // khởi động lại. Với thread của khách ngoại kênh (không phải personal chat), luôn hiện "tắt"
    // vì banner này chỉ có ý nghĩa với chat cá nhân của chủ app.
    val voiceModeActive: StateFlow<Boolean> =
        if (username == "default_user") voiceManager.micEnabled
        else MutableStateFlow(false).asStateFlow()

    private val _pausedDueToError = MutableStateFlow(false)
    val pausedDueToError: StateFlow<Boolean> = _pausedDueToError.asStateFlow()

    // Luồng quan sát trạng thái khóa cứng điều khiển phục vụ hiển thị nhãn lên UI
    private val _lockedPluginName = MutableStateFlow<String?>(null)
    val lockedPluginName: StateFlow<String?> = _lockedPluginName.asStateFlow()

    private var consecutiveRouterFailures = 0
    private val isProcessingQuery = AtomicBoolean(false)

    // ✅ ĐÃ THÊM: Luồng danh sách Hộp thư đến hiển thị ngoài InboxScreen
    val latestChatThreads: Flow<List<ChatMessageEntity>> = database.chatMessageDao().getLatestChatThreadsFlow()

    // ✅ MỚI: Luồng số tin nhắn chưa đọc theo từng thread — InboxScreen dùng để vẽ badge đỏ
    // và in đậm dòng có tin chưa đọc.
    val unreadCounts: Flow<List<com.aichatvn.agent.data.ThreadUnreadCount>> =
        database.chatMessageDao().getUnreadCountsFlow()

    // ✅ ĐÃ THÊM: Quản lý biến gạt nút Cướp quyền ngầm (smartMode) của ID khách hiện tại
    private val _isBotEnabled = MutableStateFlow(true)
    val isBotEnabled: StateFlow<Boolean> = _isBotEnabled.asStateFlow()

    val quickCommandGroups: List<QuickCommandGroup> = agentKernel.getAvailablePluginsForUI().map { plugin ->
        QuickCommandGroup(
            tabLabel = plugin.manifest.name,
            commands = plugin.manifest.actions.map { action ->
                val requiredParams = action.parameters.filter { it.required }
                val text = if (requiredParams.isEmpty()) action.description
                else action.description + " (" + requiredParams.joinToString(", ") { "<${it.name}>" } + ")"
                QuickCommand(label = action.name, text = text)
            }
        )
    }

    @Volatile private var isInForeground = true

    // ✅ ĐÃ THÊM: mic/TTS tự động chỉ có ý nghĩa với chat cá nhân của chủ app (default_user).
    // Khi ChatScreen được mở chỉ để XEM/trả lời hội thoại của 1 khách ngoại kênh
    // (Facebook/Telegram/Website), instance ViewModel này không được đụng gì tới voiceManager.
    private val isPersonalChat: Boolean = (username == "default_user")

    init {
        // ✅ ĐÃ XÓA: trước đây gán tay "_voiceModeActive.value = false" ở đây cho thread khách
        // ngoại kênh — không cần nữa vì voiceModeActive giờ đã tự tính theo username (xem khai
        // báo property phía trên), không phải biến mutable riêng nữa.

        viewModelScope.launch {
            // ✅ MỚI: Mở ChatScreen của 1 khách = coi như Admin đã xem thread này —
            // đánh dấu hết các tin nhắn khách CHƯA ĐỌC của username này thành đã đọc.
            // Với default_user thì đây là no-op (không có tin nào bị đánh dấu unread).
            database.chatMessageDao().markThreadAsRead(username)

            // ✅ ĐÃ SỬA: Trước đây gọi chatSkill.processQuery(message = "", username = username)
            // để "nạp lịch sử" — nhưng processQuery() cũng là hàm mà Webhook/SSE dùng để xử lý
            // tin nhắn tự động của MỌI khách khác ở nền, và trước đây cả hai trường hợp cùng
            // chạy chung 1 đoạn code đổi currentUsername/nạp lại _messages -> gây lẫn lộn tin
            // nhắn giữa các khách (xem ChatSkill.openThread()). Giờ dùng hẳn 1 hàm riêng
            // openThread() chỉ có ý nghĩa "đây là thread Admin đang thực sự mở lên xem".
            chatSkill.openThread(username)

            loadBotSmartModeStatus() // ✅ ĐÃ THÊM: Tải cấu hình gạt nút cướp quyền của khách

            if (isPersonalChat) {
                // ✅ ĐÃ SỬA: Vòng lặp hands-free (nghe -> AI -> nói -> nghe lại) nay được
                // VoiceAssistantService chạy ngầm ĐỘC LẬP với vòng đời ChatScreen/ViewModel
                // này (xem VoiceAssistantService.kt, khởi chạy từ MainApplication.kt).
                // ChatViewModel chỉ còn nhiệm vụ ĐỒNG BỘ HIỂN THỊ trạng thái mic hiện có lên
                // UI và phát tiếng cho các câu trả lời phát sinh từ tin nhắn gõ tay khi màn
                // hình đang mở — không tự khởi động/tắt vòng lặp nghe nữa.
                observeVoiceManagerFlows()
                observeAndSpeak()
            }

            updateLockedPluginStatus() // Khởi tạo nhãn điều khiển lúc khởi chạy
        }
    }

    // ✅ ĐÃ THÊM: Tải trạng thái Cướp quyền hiện tại của khách từ SQLite
    private fun loadBotSmartModeStatus() {
        val rawId = username.substringAfter("_")
        viewModelScope.launch(Dispatchers.IO) {
            val setting = database.cameraDao().getCustomerSetting(rawId)
            // smartMode == 1: Bật Bot (mặc định), smartMode == 0: Người trực (Cướp quyền)
            _isBotEnabled.value = setting?.smartMode != 0
        }
    }

    // ✅ ĐÃ THÊM: Lưu trạng thái bật/tắt Bot gạt Switch của khách vào SQLite
    fun toggleBotSmartMode(targetUsername: String, isBotEnabled: Boolean) {
        val rawId = targetUsername.substringAfter("_")
        _isBotEnabled.value = isBotEnabled
        viewModelScope.launch(Dispatchers.IO) {
            val setting = database.cameraDao().getCustomerSetting(rawId)
            if (setting == null) {
                database.cameraDao().insertCustomerSetting(
                    CustomerSettingEntity(
                        customerId = rawId,
                        smartMode = if (isBotEnabled) 1 else 0,
                        isActive = 1,
                        updatedAt = System.currentTimeMillis(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                database.cameraDao().updateSmartMode(rawId, isBotEnabled, System.currentTimeMillis())
            }
            logger.i("ChatViewModel", "👤 Đã cập nhật chế độ Bot cho khách $targetUsername thành: $isBotEnabled")
        }
    }

    private fun observeVoiceManagerFlows() {
        // 1. Đồng bộ trạng thái bật/tắt Micro lên UI Chat
        viewModelScope.launch {
            voiceManager.isListening.collect { listening ->
                _isListening.value = listening
            }
        }

        // 2. Đồng bộ văn bản nhận diện được từ giọng nói vào DB để hiển thị lên khung Chat (bỏ qua chuỗi rỗng)
        viewModelScope.launch {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    saveMessageToHistory(text, "user")
                }
            }
        }

        // 3. Đồng bộ câu trả lời AI của cuộc thoại giọng nói vào DB (gắn nhãn sourcePlugin = "voice_assistant")
        viewModelScope.launch {
            voiceManager.aiResponseText.collect { reply ->
                if (reply.isNotBlank()) {
                    saveMessageToHistory(reply, "assistant", sourcePlugin = "voice_assistant")
                }
            }
        }
    }

    // Ghi nhật ký cuộc thoại giọng nói xuống SQLite và kích hoạt cập nhật lại UI Flow
    private fun saveMessageToHistory(content: String, role: String, sourcePlugin: String? = null) {
        viewModelScope.launch {
            val msg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionToken = "session_$username", // ✅ CẬP NHẬT: Gắn theo username động
                username = username,                 // ✅ CẬP NHẬT: Gắn theo username động
                content = content,
                role = role,
                type = "text",
                timestamp = System.currentTimeMillis(),
                sourcePlugin = sourcePlugin
            )
            withContext(Dispatchers.IO) {
                database.chatMessageDao().insertMessage(msg)
            }
            chatSkill.initialize()
            updateLockedPluginStatus() // Cập nhật nhãn điều khiển sau phiên thoại giọng nói
        }
    }

    // ✅ ĐÃ XÓA startVoiceSession(): việc chào mừng + khởi động nghe lần đầu nay do
    // VoiceAssistantService thực hiện khi app khởi chạy, độc lập với ChatScreen có mở hay không.

    private fun observeAndSpeak() {
        viewModelScope.launch(Dispatchers.Main) {
            messages.drop(1).collect { msgs ->
                val last = msgs.lastOrNull() ?: return@collect
                if (last.role != "assistant") return@collect
                if (!voiceManager.micEnabled.value) return@collect

                // Bỏ qua nếu tin nhắn đến từ cuộc thoại giọng nói tự động hoặc các plugin khác đã tự nói
                if (last.sourcePlugin == "vision" || 
                    last.sourcePlugin == "learn" || 
                    last.sourcePlugin == "device_control" ||
                    last.sourcePlugin == "voice_assistant") { // Chặn lặp tiếng/khựng tiếng tại đây
                    return@collect
                }

                if (!voiceManager.ttsHelper.isReady) {
                    logger.w("ChatViewModel", "TTS chưa sẵn sàng, kích hoạt nghe lại nhằm duy trì vòng lặp.")
                    if (voiceManager.micEnabled.value && isInForeground) {
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
                    // ✅ ĐÃ SỬA: dùng setMicEnabled(false) thay vì gán cờ nội bộ — để trạng thái
                    // "đã tắt do lỗi liên tiếp" cũng được lưu bền và đồng bộ ra notification luôn,
                    // thay vì chỉ ẩn ở mỗi ViewModel này.
                    voiceManager.setMicEnabled(false)
                    _pausedDueToError.value = true
                    voiceManager.speak(
                        "Tôi đang gặp lỗi kết nối mạng nhiều lần liên tiếp. " +
                            "Tôi sẽ tạm dừng nghe để tránh làm phiền bạn. " +
                            "Nhờ người chăm sóc kiểm tra mạng và bật mic lại khi sẵn sàng."
                    )
                    return@collect
                }

                voiceManager.speak(last.content) {
                    if (voiceManager.micEnabled.value && isInForeground) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (voiceManager.micEnabled.value && isInForeground) {
                                voiceManager.startListening()
                            }
                        }, 300L)
                    }
                }
            }
        }
    }

    fun toggleVoiceMode() {
        if (!isPersonalChat) return // ✅ ĐÃ THÊM: không cho bật/tắt mic cá nhân khi đang xem thread khách ngoại kênh
        val next = !voiceManager.micEnabled.value
        if (next) {
            _pausedDueToError.value = false
            consecutiveRouterFailures = 0
        }
        // ✅ ĐÃ SỬA: đi qua setMicEnabled() duy nhất — cùng 1 đường với nút bấm trên notification,
        // đảm bảo bật/tắt từ ChatScreen hay từ notification luôn nhất quán và được lưu bền.
        voiceManager.setMicEnabled(next)
        if (!next) {
            voiceManager.ttsHelper.stop()
        }
    }

    fun setChatMode(mode: ChatMode) {
        chatSkill.setChatMode(mode)
    }

    fun updateLockedPluginStatus() {
        viewModelScope.launch {
            _lockedPluginName.value = agentKernel.getLockedPluginName()
        }
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

                // ✅ CẬP NHẬT: Thêm tham số isManual = true báo hiệu người dùng gõ tay thủ công
                val response = chatSkill.processQuery(
                    message = userMessageContent,
                    username = username,
                    fileUrl = fileUrl,
                    imageBase64 = base64Image,
                    isManual = true // ✅ Báo hiệu cho ChatSkill
                )

                if (response is PluginResult.Failure) {
                    logger.e("ChatViewModel", "Error: ${response.error}")
                }
            } finally {
                _isLoading.value = false
                isProcessingQuery.set(false)
                updateLockedPluginStatus() // Đồng bộ lại nhãn điều khiển sau khi nhận được phản hồi của Agent
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
            chatSkill.clearHistory(username) // ✅ CẬP NHẬT: Gắn theo username động
            updateLockedPluginStatus()
        }
    }

    fun onForeground() {
        // ✅ ĐÃ SỬA: Không còn start/stop vòng lặp mic ở đây — VoiceAssistantService chạy
        // vòng lặp hands-free độc lập với vòng đời màn hình. onForeground chỉ còn dùng để
        // gạt cờ isInForeground, quyết định có phát tiếng trả lời cho tin nhắn gõ tay hay không.
        isInForeground = true
    }

    fun onBackground() {
        // ✅ ĐÃ SỬA: Rời màn hình KHÔNG được tắt mic nữa — người dùng hands-free cần ra lệnh
        // thoại được cả khi tắt màn hình/rời app. Vòng lặp nghe do VoiceAssistantService quản lý,
        // không phụ thuộc ChatScreen có đang mở hay không.
        isInForeground = false
    }

    override fun onCleared() {
        super.onCleared()
        // ✅ ĐÃ SỬA: Không gọi voiceManager.stopListening()/ttsHelper.stop() ở đây nữa —
        // vòng lặp voice sống độc lập trong VoiceAssistantService, ViewModel bị clear
        // (rời màn hình) không còn ảnh hưởng tới nó.
    }
}