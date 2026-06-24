package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper

/**
 * VoiceAssistantManager
 *
 * Loop hands-free không bao giờ chết:
 * - Có kết quả STT → onTextRecognized() → AI xử lý → TTS đọc → startListening() lại
 * - Im lặng hết timeout → onSilence() → startListening() lại ngay
 * - Lỗi STT (mất mạng, mic bị chiếm...) → onError → startListening() lại sau 2s
 *
 * Chỉ dừng khi toggleVoiceMode() tắt hoặc destroy().
 *
 * THREAD SAFETY: startListening() và stopListening() có thể được gọi từ bất kỳ
 * thread nào — tất cả thao tác SpeechRecognizer được SpeechRecognizerHelper tự
 * dispatch về Main thread.
 */
class VoiceAssistantManager(
    context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {}
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    private var listeningTimer: CountDownTimer? = null

    private val restartHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        cancelPendingRestart()
        onListeningStateChange(true)

        sttHelper.startListening(
            onResult = { text ->
                stopTimer()
                onListeningStateChange(false)
                onTextRecognized(text)
                // Không startListening() ở đây — ViewModel gọi lại sau khi TTS đọc xong
            },
            onError = { _ ->
                stopTimer()
                onListeningStateChange(false)
                scheduleRestart(delayMs = 2_000L)
            }
        )

        startTimeoutTimer()
    }

    fun stopListening() {
        cancelPendingRestart()
        stopTimer()
        sttHelper.destroy()
        onListeningStateChange(false)
    }

    fun destroy() {
        destroyed = true
        stopListening()
        ttsHelper.shutdown()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startTimeoutTimer() {
        listeningTimer?.cancel()
        listeningTimer = object : CountDownTimer(LISTEN_TIMEOUT_MS, LISTEN_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                onListeningStateChange(false)
                onSilence()
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
        private const val LISTEN_TIMEOUT_MS = 8_000L
    }
}