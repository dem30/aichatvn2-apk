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

/**
 * Vòng đời của luồng thoại. Thay thế cho cờ boolean `isListeningActive` rời rạc
 * trước đây — mọi chuyển trạng thái đi qua transitionTo() để một callback (STT/TTS/
 * timeout) không thể "giẫm" lên trạng thái do callback khác vừa thiết lập.
 *
 *   IDLE ── startListening() ──▶ LISTENING
 *   LISTENING ── có kết quả ──▶ PROCESSING ── có phản hồi AI ──▶ SPEAKING
 *   SPEAKING ── TTS xong ──▶ RESTARTING ──▶ LISTENING
 *   LISTENING ── lỗi/timeout ──▶ RESTARTING ──▶ LISTENING (hoặc IDLE nếu lỗi liên tiếp)
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

    private var listeningTimer: CountDownTimer? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState = _voiceState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _recognizedText = MutableSharedFlow<String>(replay = 0)
    val recognizedText = _recognizedText.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    val partialText = _partialText.asStateFlow()

    private val _aiResponseText = MutableSharedFlow<String>(replay = 0)
    val aiResponseText = _aiResponseText.asSharedFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private var consecutiveSttFailures = 0
    private val MAX_CONSECUTIVE_STT_FAILURES = 5

    // ---------- Quản lý trạng thái tập trung ----------

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

    // Best-effort: một số thiết bị/OEM phát tiếng "tút" khởi động STT qua STREAM_MUSIC,
    // nhưng không đảm bảo — nhiều Recognition Service phát qua STREAM_SYSTEM hoặc
    // ngoài AudioManager hoàn toàn. KHÔNG coi đây là cơ chế triệt tiếng chính thức.
    private fun muteStartupBeep(mute: Boolean) {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } catch (e: Exception) {
            // Bỏ qua — một số thiết bị không cho chỉnh mute theo cách này
        }
    }

    fun startListening() {
        if (!canStartListening()) {
            Log.d("VoiceAssistantManager", "Bỏ qua startListening(): đang ở trạng thái ${_voiceState.value}")
            return
        }

        transitionTo(VoiceState.LISTENING)
        cancelPendingRestart()
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
                muteStartupBeep(false)
                abandonAudioFocus()

                Log.w("VoiceAssistantManager", "STT lỗi (code=$errorCode): $errorMsg")

                val isFinalizeFailure = errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (isFinalizeFailure && lastPartial.isNotBlank()) {
                    // Máy đã nghe được nội dung qua partial nhưng bước chốt kết quả bị lỗi
                    // → dùng tạm partial cuối làm kết quả thay vì bỏ lệnh của người dùng.
                    Log.w("VoiceAssistantManager", "Dùng partial cuối làm kết quả: \"$lastPartial\"")
                    consecutiveSttFailures = 0
                    handleRecognizedText(lastPartial)
                } else {
                    consecutiveSttFailures++
                    if (consecutiveSttFailures >= MAX_CONSECUTIVE_STT_FAILURES) {
                        consecutiveSttFailures = 0
                        Log.e("VoiceAssistantManager", "Lỗi Mic liên tiếp: $errorMsg")
                        transitionTo(VoiceState.IDLE)
                    } else {
                        transitionTo(VoiceState.RESTARTING)
                        val delay = when (errorCode) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 200L
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                            else -> 1500L
                        }
                        scheduleRestart(delayMs = delay)
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
                // Chỉ báo "đang nghe" khi máy THỰC SỰ sẵn sàng nhận audio
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
        abandonAudioFocus()

        // Xử lý TUẦN TỰ trong cùng một coroutine (thay vì 2 launch song song như trước)
        // để đảm bảo thứ tự emit là tất định: user text -> AI trả lời -> TTS.
        // Không dùng delay() để "đoán" thời gian đồng bộ.
        scope.launch {
            _recognizedText.emit(text)
            try {
                val result = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = text,
                        chatMode = "COMBINED"
                    )
                )
                _aiResponseText.emit(result.responseText)
                speak(result.responseText) {
                    transitionTo(VoiceState.RESTARTING)
                    startListening()
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
        // Chỉ hủy phiên nghe hiện tại, GIỮ instance SpeechRecognizer để tái sử dụng.
        // Không destroy()/create() liên tục — tránh ERROR_RECOGNIZER_BUSY và chi phí bind service.
        sttHelper.stopListening()
        _isListening.value = false
        _partialText.value = ""
        muteStartupBeep(false)
        abandonAudioFocus()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        stopListening()
        transitionTo(VoiceState.SPEAKING)
        ttsHelper.speak(text, onDone)
    }

    fun destroy() {
        destroyed = true
        transitionTo(VoiceState.IDLE)
        cancelPendingRestart()
        stopTimer()
        sttHelper.destroy() // Giải phóng thật sự — chỉ khi Manager bị hủy hẳn
        ttsHelper.shutdown()
    }

    private fun startTimeoutTimer() {
        listeningTimer?.cancel()
        listeningTimer = object : CountDownTimer(LISTEN_TIMEOUT_MS, LISTEN_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                _isListening.value = false
                abandonAudioFocus()
                transitionTo(VoiceState.RESTARTING)
                sttHelper.stopListening() // Không destroy — giữ instance để tái sử dụng
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