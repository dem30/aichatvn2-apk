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

    private val _voiceModeActive = MutableStateFlow(true)
    val voiceModeActive: StateFlow<Boolean> = _voiceModeActive.asStateFlow()

    private val _pausedDueToError = MutableStateFlow(false)
    val pausedDueToError: StateFlow<Boolean> = _pausedDueToError.asStateFlow()

    // Luồng quan sát trạng thái khóa cứng điều khiển phục vụ hiển thị nhãn lên UI
    private val _lockedPluginName = MutableStateFlow<String?>(null)
    val lockedPluginName: StateFlow<String?> = _lockedPluginName.asStateFlow()

    private var consecutiveRouterFailures = 0
    private val isProcessingQuery = AtomicBoolean(false)

    // ✅ ĐÃ THÊM: Luồng danh sách Hộp thư đến hiển thị ngoài InboxScreen
    val latestChatThreads: Flow<List<ChatMessageEntity>> = database.chatMessageDao().getLatestChatThreadsFlow()

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

    init {
        viewModelScope.launch {
            voiceManager.reactivate() // Phòng hờ: gỡ khóa nếu destroy() từng bị gọi ở lần trước
            
            // ✅ CẬP NHẬT: Khởi tạo nạp tin nhắn cũ cho ID khách hàng hiện tại
            chatSkill.processQuery(message = "", username = username)
            
            loadBotSmartModeStatus() // ✅ ĐÃ THÊM: Tải cấu hình gạt nút cướp quyền của khách
            
            observeVoiceManagerFlows()
            observeAndSpeak()
            startVoiceSession()
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

                // Bỏ qua nếu tin nhắn đến từ cuộc thoại giọng nói tự động hoặc các plugin khác đã tự nói
                if (last.sourcePlugin == "vision" || 
                    last.sourcePlugin == "learn" || 
                    last.sourcePlugin == "device_control" ||
                    last.sourcePlugin == "voice_assistant") { // Chặn lặp tiếng/khựng tiếng tại đây
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
        voiceManager.stopListening()
        voiceManager.ttsHelper.stop()
    }
}