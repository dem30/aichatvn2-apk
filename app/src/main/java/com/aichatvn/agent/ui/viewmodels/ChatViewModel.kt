package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentKernel: AgentKernel,
    private val groqClient: GroqClientTool,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val logger: Logger
) : ViewModel() {

    val username: String = savedStateHandle.get<String>("username") ?: "default_user"

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages
    val chatMode: StateFlow<ChatMode> = chatSkill.chatMode
    val groqRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.rateLimitInfo
    val groqRouterRateLimit: StateFlow<GroqRateLimitInfo?> = groqClient.routerRateLimitInfo

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isVoiceOverlayOpen = MutableStateFlow(false)
    val isVoiceOverlayOpen: StateFlow<Boolean> = _isVoiceOverlayOpen.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val _lockedPluginName = MutableStateFlow<String?>(null)
    val lockedPluginName: StateFlow<String?> = _lockedPluginName.asStateFlow()

    private val isProcessingQuery = AtomicBoolean(false)

    val latestChatThreads: Flow<List<ChatMessageEntity>> = database.chatMessageDao().getLatestChatThreadsFlow()

    val unreadCounts: Flow<List<com.aichatvn.agent.data.ThreadUnreadCount>> =
        database.chatMessageDao().getUnreadCountsFlow()

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

    init {
        activateThread()
        viewModelScope.launch {
            loadBotSmartModeStatus()
            updateLockedPluginStatus()
        }
    }

    fun activateThread() {
        viewModelScope.launch {
            database.chatMessageDao().markThreadAsRead(username)
            chatSkill.openThread(username)
        }
    }

    private fun loadBotSmartModeStatus() {
        val rawId = username.substringAfter("_")
        viewModelScope.launch(Dispatchers.IO) {
            val setting = database.cameraDao().getCustomerSetting(rawId)
            _isBotEnabled.value = setting?.smartMode != 0
        }
    }

    /**
     * ✅ CẬP NHẬT (Bước 2): Ghi nhận sự kiện cướp quyền (Takeover) có cấu trúc JSON vào WorldState & EventLog
     */



     
    // ... [imports và các phần khởi tạo giữ nguyên] ...

    /**
     * ✅ SỬA LỖI NHỎ (Bước 2): Tự động chuyển đổi trạng thái và reset unread_count về 0 khi Admin gạt tiếp quản (Takeover)
     */
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

            // Đồng bộ trạng thái tiếp quản phiên chat của người trực vào Bản sao số (World State)
            val existingState = database.worldStateDao().getState("chat", targetUsername)
            val json = existingState?.let {
                try { org.json.JSONObject(it.attributesJson) } catch (e: Exception) { org.json.JSONObject() }
            } ?: org.json.JSONObject()

            val newStatus = if (isBotEnabled) "waiting_agent" else "agent_handling"
            json.put("session_status", newStatus)
            if (!isBotEnabled) {
                json.put("unread_count", 0) // Reset ngay về 0 khi Admin trực tiếp cướp quyền trực chat
            }
            val updatedPayload = json.toString()

            database.worldStateDao().upsertState(
                com.aichatvn.agent.data.model.WorldStateEntity(
                    id = "chat:$targetUsername",
                    source = "chat",
                    sourceId = targetUsername,
                    attributesJson = updatedPayload,
                    updatedAt = System.currentTimeMillis()
                )
            )

            // Lưu nhật ký sự kiện có cấu trúc
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "chat",
                    sourceId = targetUsername,
                    eventType = "chat_session_state_change",
                    value = updatedPayload,
                    summary = if (isBotEnabled) {
                        "🤖 Đã bật lại Bot tự động trả lời cho khách $targetUsername."
                    } else {
                        "👤 Quản trị viên đã tiếp quản, cướp quyền trực chat và reset tin chưa đọc về 0 cho khách $targetUsername."
                    }
                )
            )

            logger.i("ChatViewModel", "👤 Đã cập nhật chế độ Bot cho khách $targetUsername thành: $isBotEnabled")
        }
    }



    

    fun setChatMode(mode: ChatMode) {
        chatSkill.setChatMode(mode)
    }

    fun openVoiceSearch() {
        _isVoiceOverlayOpen.value = true
        _voiceError.value = null
        _partialText.value = ""
        _rmsDb.value = 0f
        startSpeechRecognition()
    }

    fun closeVoiceSearch() {
        stopSpeechRecognition()
        _isVoiceOverlayOpen.value = false
        _voiceError.value = null
        _partialText.value = ""
        _rmsDb.value = 0f
    }

    private fun startSpeechRecognition() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                _voiceError.value = null
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                _isListening.value = true
                                _partialText.value = "Đang lắng nghe giọng nói..."
                            }

                            override fun onBeginningOfSpeech() {
                                _partialText.value = ""
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                _rmsDb.value = rmsdB
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}
                            
                            override fun onEndOfSpeech() {
                                _isListening.value = false
                            }

                            override fun onError(error: Int) {
                                _isListening.value = false
                                _rmsDb.value = 0f
                                val errorMsg = when (error) {
                                    SpeechRecognizer.ERROR_AUDIO -> "Lỗi thiết bị thu âm"
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền ghi âm"
                                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi kết nối mạng"
                                    SpeechRecognizer.ERROR_NO_MATCH -> "Tôi không nghe rõ. Vui lòng thử lại"
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Bạn chưa nói gì cả"
                                    else -> "Không thể nhận diện giọng nói"
                                }
                                _voiceError.value = errorMsg
                                logger.w("ChatViewModel", "STT Error: $errorMsg ($error)")
                            }

                            override fun onResults(results: Bundle?) {
                                _isListening.value = false
                                _rmsDb.value = 0f
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull() ?: ""
                                if (text.isNotBlank()) {
                                    _isVoiceOverlayOpen.value = false
                                    _partialText.value = ""
                                    sendMessage(text)
                                } else {
                                    _voiceError.value = "Tôi không nghe rõ. Vui lòng thử lại"
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val currentText = matches?.firstOrNull() ?: ""
                                if (currentText.isNotBlank()) {
                                    _partialText.value = currentText
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                logger.e("ChatViewModel", "Lỗi khởi tạo STT: ${e.message}")
                _isListening.value = false
                _voiceError.value = "Thiết bị không hỗ trợ Google Speech Services"
            }
        }
    }

    private fun stopSpeechRecognition() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            _isListening.value = false
            _rmsDb.value = 0f
        }
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

                val response = chatSkill.processQuery(
                    message = userMessageContent,
                    username = username,
                    fileUrl = fileUrl,
                    imageBase64 = base64Image,
                    isManual = true
                )

                if (response is PluginResult.Failure) {
                    logger.e("ChatViewModel", "Error: ${response.error}")
                }
            } finally {
                _isLoading.value = false
                isProcessingQuery.set(false)
                updateLockedPluginStatus()
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
            chatSkill.clearHistory(username)
            updateLockedPluginStatus()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                logger.e("ChatViewModel", "Lỗi giải phóng bộ nhận diện giọng nói: ${e.message}")
            }
        }
    }
}