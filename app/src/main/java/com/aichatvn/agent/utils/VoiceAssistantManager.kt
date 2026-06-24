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
    
    // Đề xuất 1: Thêm cờ bảo vệ chống mở nhiều phiên nghe trùng lặp
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
                // Đề xuất 3: Sử dụng AUDIOFOCUS_GAIN_TRANSIENT nâng cao độ tương thích thiết bị
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
        if (isListeningActive) return // Đề xuất 1: Từ chối yêu cầu nghe mới nếu phiên cũ đang hoạt động
        isListeningActive = true

        cancelPendingRestart()
        requestAudioFocus()
        onListeningStateChange(true)

        sttHelper.startListening(
            onResult = { text ->
                isListeningActive = false // Reset cờ trạng thái nghe
                consecutiveSttFailures = 0
                stopTimer()
                onListeningStateChange(false)
                abandonAudioFocus()
                onTextRecognized(text)
            },
            onError = { errorCode, errorMsg ->
                isListeningActive = false // Reset cờ trạng thái nghe
                stopTimer()
                onListeningStateChange(false)
                abandonAudioFocus()
                
                consecutiveSttFailures++
                if (consecutiveSttFailures >= MAX_CONSECUTIVE_STT_FAILURES) {
                    consecutiveSttFailures = 0
                    onMaxSTTFailuresReached(errorMsg)
                } else {
                    // Đề xuất 5: Gặp lỗi BUSY thì kích hoạt thử lại cực nhanh (300ms) thay vì 2000ms
                    val delay = if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 300L else 2000L
                    scheduleRestart(delayMs = delay)
                }
            }
        )

        startTimeoutTimer()
    }

    fun stopListening() {
        isListeningActive = false // Đề xuất 1: Đưa cờ về false khi chủ động tắt nghe
        cancelPendingRestart()
        stopTimer()
        sttHelper.destroy()
        onListeningStateChange(false)
        abandonAudioFocus()
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
                isListeningActive = false // Reset cờ trạng thái nghe
                onListeningStateChange(false)
                abandonAudioFocus()
                onSilence()
                
                sttHelper.destroy()
                scheduleRestart(delayMs = 300L)
            }
        }.start()
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
        // Đề xuất 2: Tăng thời gian chờ lên 12 giây tối ưu cho các phản hồi chậm của người dùng
        private const val LISTEN_TIMEOUT_MS = 12_000L
    }
}