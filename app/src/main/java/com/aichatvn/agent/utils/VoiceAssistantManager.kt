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

class VoiceAssistantManager(
    private val context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {},
    private val onMaxSTTFailuresReached: (String) -> Unit = {}
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    private var listeningTimer: CountDownTimer? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false
    @Volatile private var isListeningActive = false

    // Quản lý Audio Focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    // Quản lý số lần lỗi Mic liên tiếp
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
            // Bỏ qua lỗi phát sinh
        }
    }

    fun startListening() {
        if (destroyed) return

        // CHỐNG ECHO: Tuyệt đối không bật microphone nếu TTS đang phát âm thanh
        if (ttsHelper.isSpeaking) {
            Log.d("VoiceAssistantManager", "Bỏ qua startListening() vì thiết bị đang trong tiến trình TTS đọc.")
            return
        }

        if (isListeningActive) return
        isListeningActive = true

        cancelPendingRestart()
        requestAudioFocus()
        onListeningStateChange(true)

        sttHelper.startListening(
            onResult = { text ->
                isListeningActive = false
                consecutiveSttFailures = 0
                stopTimer()
                onListeningStateChange(false)
                abandonAudioFocus()
                onTextRecognized(text)
            },
            onError = { errorCode, errorMsg ->
                isListeningActive = false
                stopTimer()
                onListeningStateChange(false)
                abandonAudioFocus()

                consecutiveSttFailures++
                if (consecutiveSttFailures >= MAX_CONSECUTIVE_STT_FAILURES) {
                    consecutiveSttFailures = 0
                    onMaxSTTFailuresReached(errorMsg)
                } else {
                    val delay = if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 300L else 2000L
                    scheduleRestart(delayMs = delay)
                }
            },
            // ✅ FIX: User bắt đầu nói → reset timeout từ đầu, tránh bị cắt giữa chừng
            onSpeechStarted = {
                Log.d("VoiceAssistantManager", "User bắt đầu nói, reset timeout timer.")
                resetTimeoutTimer()
            },
            // ✅ FIX: User ngừng nói → hủy timer ngay, chờ kết quả STT (không đợi hết 12s)
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
        onListeningStateChange(false)
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
                // Chỉ chạy vào đây nếu user im lặng hoàn toàn suốt LISTEN_TIMEOUT_MS
                isListeningActive = false
                onListeningStateChange(false)
                abandonAudioFocus()
                onSilence()
                sttHelper.destroy()
                scheduleRestart(delayMs = 300L)
            }
        }.start()
    }

    // ✅ FIX: Reset timer từ đầu khi user bắt đầu nói
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
        // Timeout im lặng: 12s — chỉ kích hoạt khi user KHÔNG nói gì
        // Nếu user đang nói thì timer sẽ được reset liên tục qua onSpeechStarted
        private const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
