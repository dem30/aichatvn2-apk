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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAssistantManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentKernel: AgentKernel,
    private val logger: Logger
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    
    val ttsHelper = TextToSpeechHelper(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var listeningTimer: CountDownTimer? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false
    @Volatile private var isListeningActive = false

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _recognizedText = MutableSharedFlow<String>(replay = 0)
    val recognizedText = _recognizedText.asSharedFlow()

    private val _aiResponseText = MutableSharedFlow<String>(replay = 0)
    val aiResponseText = _aiResponseText.asSharedFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private var consecutiveSttFailures = 0
    private val MAX_CONSECUTIVE_STT_FAILURES = 5

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

    fun startListening() {
        if (destroyed) return

        if (ttsHelper.isSpeaking) {
            Log.d("VoiceAssistantManager", "Bỏ qua startListening() vì thiết bị đang trong tiến trình TTS đọc.")
            return
        }

        if (isListeningActive) return
        isListeningActive = true

        cancelPendingRestart()
        requestAudioFocus()
        _isListening.value = true

        sttHelper.startListening(
            onResult = { text ->
                isListeningActive = false
                consecutiveSttFailures = 0
                stopTimer()
                _isListening.value = false
                abandonAudioFocus()

                scope.launch { _recognizedText.emit(text) }

                scope.launch {
                    try {
                        val result = agentKernel.chat(
                            com.aichatvn.agent.core.ChatRequest(
                                message = text,
                                chatMode = "COMBINED"
                            )
                        )
                        _aiResponseText.emit(result.responseText)

                        speak(result.responseText)
                    } catch (e: Exception) {
                        logger.e("VoiceAssistantManager", "Lỗi xử lý luồng giọng nói", e)
                        speak("Xin lỗi, hệ thống gặp sự cố khi xử lý câu lệnh.")
                    }
                }
            },
            onError = { errorCode, errorMsg ->
                isListeningActive = false
                stopTimer()
                _isListening.value = false
                abandonAudioFocus()

                consecutiveSttFailures++
                if (consecutiveSttFailures >= MAX_CONSECUTIVE_STT_FAILURES) {
                    consecutiveSttFailures = 0
                    Log.e("VoiceAssistantManager", "Lỗi Mic liên tiếp: $errorMsg")
                } else {
                    // Rút ngắn thời gian khởi động lại cho lỗi im lặng/timeout để tăng độ nhạy phản hồi
                    val delay = when (errorCode) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 200L
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                        else -> 1500L
                    }
                    scheduleRestart(delayMs = delay)
                }
            },
            onSpeechStarted = {
                Log.d("VoiceAssistantManager", "User bắt đầu nói, reset timeout timer.")
                resetTimeoutTimer()
            },
            onEndOfSpeech = {
                Log.d("VoiceAssistantManager", "User ngừng nói, hủy timer chờ kết quả STT.")
                stopTimer()
            }
        )

        startTimeoutTimer()
    }

    fun stopListening() {
        isListeningActive = false
        cancelPendingRestart()
        stopTimer()
        sttHelper.destroy()
        _isListening.value = false
        abandonAudioFocus()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        stopListening()
        ttsHelper.speak(text, onDone)
    }

    fun destroy() {
        destroyed = true
        stopListening()
        ttsHelper.shutdown()
    }

    private fun startTimeoutTimer() {
        listeningTimer?.cancel()
        listeningTimer = object : CountDownTimer(LISTEN_TIMEOUT_MS, LISTEN_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                isListeningActive = false
                _isListening.value = false
                abandonAudioFocus()
                sttHelper.destroy()
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