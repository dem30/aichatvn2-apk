package com.aichatvn.agent.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.ChatRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vòng đời của luồng thoại.
 */
enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    RESTARTING
}

@Singleton
class VoiceAssistantManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentKernel: AgentKernel,
    private val logger: Logger
) {

    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    private var processingJob: Job? = null
    private var listeningTimer: CountDownTimer? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.IDLE)
    val voiceState = _voiceState.asStateFlow()

    private val _isListening = MutableStateFlow<Boolean>(false)
    val isListening = _isListening.asStateFlow()

    private val _recognizedText = MutableSharedFlow<String>(replay = 0)
    val recognizedText = _recognizedText.asSharedFlow()

    private val _partialText = MutableStateFlow<String>("")
    val partialText = _partialText.asStateFlow()

    private val _aiResponseText = MutableSharedFlow<String>(replay = 0)
    val aiResponseText = _aiResponseText.asSharedFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private var consecutiveSttFailures = 0
    private val MAX_CONSECUTIVE_STT_FAILURES = 5

    @Synchronized
    private fun transitionTo(newState: VoiceState) {
        val old = _voiceState.value
        if (old == newState) return
        _voiceState.value = newState
        Log.d("VoiceAssistantManager", "State: $old -> $newState")
    }

    @Synchronized
    private fun canStartListening(): Boolean {
        return !destroyed && _voiceState.value in setOf(VoiceState.IDLE, VoiceState.RESTARTING)
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            // Bỏ qua
        }
    }

    private fun muteStartupBeep(mute: Boolean) {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } catch (e: Exception) {
            // Bỏ qua
        }
    }

    fun startListening() {
        if (destroyed) {
            Log.d("VoiceAssistantManager", "Phát hiện trạng thái destroyed cũ từ Singleton sống ngầm -> Tự động khôi phục")
            reactivate()
        }

        if (!canStartListening()) {
            Log.d("VoiceAssistantManager", "Bỏ qua startListening(): đang ở trạng thái ${_voiceState.value}")
            return
        }

        transitionTo(VoiceState.LISTENING)
        cancelPendingRestart()
        
        processingJob?.cancel()
        processingJob = null

        requestAudioFocus()
        muteStartupBeep(true)
        _partialText.value = ""

        sttHelper.startListening(
            onResult = { text ->
                handleRecognizedText(text)
            },
            onError = { errorCode, errorMsg ->
                val lastPartial = _partialText.value
                stopTimer()
                _isListening.value = false
                _partialText.value = ""

                Log.w("VoiceAssistantManager", "STT lỗi (code=$errorCode): $errorMsg")

                if (_voiceState.value == VoiceState.IDLE) {
                    Log.d("VoiceAssistantManager", "Bỏ qua lỗi STT phản hồi muộn sau khi người dùng tắt mic.")
                    abandonAudioFocus()
                } else {
                    val isFinalizeFailure = errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                    if (isFinalizeFailure && lastPartial.isNotBlank()) {
                        consecutiveSttFailures = 0
                        handleRecognizedText(lastPartial)
                    } else {
                        abandonAudioFocus()
                        consecutiveSttFailures++
                        if (consecutiveSttFailures >= MAX_CONSECUTIVE_STT_FAILURES) {
                            consecutiveSttFailures = 0
                            Log.e("VoiceAssistantManager", "Lỗi Mic liên tiếp: $errorMsg")
                            transitionTo(VoiceState.IDLE)
                        } else {
                            transitionTo(VoiceState.RESTARTING)

                            
                            // ĐOẠN CODE MỚI:
val delay = when (errorCode) {
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L // ✅ Tăng lên 1.2 giây để tránh dồn ép phần cứng
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1000L  // ✅ Tăng lên 1 giây để chu kỳ mượt mà hơn
    else -> 1500L
}


                            
                            scheduleRestart(delayMs = delay)
                        }
                    }
                }
            },
            onSpeechStarted = {
                Log.d("VoiceAssistantManager", "User bắt đầu nói, reset timeout timer.")
                resetTimeoutTimer()
            },
            onEndOfSpeech = {
                Log.d("VoiceAssistantManager", "User ngừng nói, hủy timer chờ kết quả STT.")
                stopTimer()
            },
            onPartialResult = { text ->
                _partialText.value = text
            },
            onReadyForSpeech = {
                _isListening.value = true
                muteStartupBeep(false)
            }
        )

        startTimeoutTimer()
    }

    private fun handleRecognizedText(text: String) {
        transitionTo(VoiceState.PROCESSING)
        consecutiveSttFailures = 0
        stopTimer()
        _isListening.value = false
        _partialText.value = ""
        
        processingJob?.cancel()
        processingJob = scope.launch {
            _recognizedText.emit(text)
            try {
                val result = agentKernel.chat(
                    ChatRequest(
                        message = text,
                        chatMode = "COMBINED"
                    )
                )
                _aiResponseText.emit(result.responseText)
               
              // ĐOẠN CODE MỚI:
speak(result.responseText) {
    transitionTo(VoiceState.RESTARTING)
    scheduleRestart(delayMs = 800L) // ✅ Chờ 800ms để loa ngoài xả hết âm thanh rồi mới bật lại Mic
}


                
            } catch (e: Exception) {
                logger.e("VoiceAssistantManager", "Lỗi xử lý luồng giọng nói", e)
                speak("Xin lỗi, hệ thống gặp sự cố khi xử lý câu lệnh.") {
                    transitionTo(VoiceState.RESTARTING)
                    startListening()
                }
            }
        }
    }

    fun stopListening() {
        transitionTo(VoiceState.IDLE)
        cancelPendingRestart()
        stopTimer()
        
        processingJob?.cancel()
        processingJob = null

        sttHelper.stopListening()
        _isListening.value = false
        _partialText.value = ""
        muteStartupBeep(false)
        abandonAudioFocus()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (destroyed) return
        stopListening()
        transitionTo(VoiceState.SPEAKING)
        requestAudioFocus()
        ttsHelper.speak(text, onDone)
    }

    fun destroy() {
        destroyed = true
        transitionTo(VoiceState.IDLE)
        cancelPendingRestart()
        stopTimer()
        
        processingJob?.cancel()
        processingJob = null

        sttHelper.destroy()
        ttsHelper.shutdown()
        abandonAudioFocus()
    }

    fun reactivate() {
        destroyed = false
        transitionTo(VoiceState.IDLE)
    }

    private fun startTimeoutTimer() {
        listeningTimer?.cancel()
        listeningTimer = object : CountDownTimer(LISTEN_TIMEOUT_MS, LISTEN_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                _isListening.value = false
                abandonAudioFocus()
                transitionTo(VoiceState.RESTARTING)
                sttHelper.stopListening()
                scheduleRestart(delayMs = 300L)
            }
        }.start()
    }

    private fun resetTimeoutTimer() {
        listeningTimer?.cancel()
        startTimeoutTimer()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        cancelPendingRestart()
        val runnable = Runnable {
            pendingRestart = null
            if (!destroyed) startListening()
        }
        pendingRestart = runnable
        restartHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { restartHandler.removeCallbacks(it) }
        pendingRestart = null
    }

    private fun stopTimer() {
        listeningTimer?.cancel()
        listeningTimer = null
    }

    companion object {
        private const val LISTEN_TIMEOUT_MS = 12_000L
    }
}