package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer

/**
 * VoiceAssistantManager
 *
 * Loop hands-free không bao giờ chết:
 * - Có kết quả STT → onTextRecognized() → AI xử lý → TTS đọc → startListening() lại
 * - Im lặng hết timeout → onSilence() → startListening() lại ngay
 * - Lỗi STT (mất mạng, mic bị chiếm...) → onError → startListening() lại sau 2s
 *
 * Chỉ dừng khi toggleVoiceMode() tắt hoặc destroy().
 */
class VoiceAssistantManager(
    context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {}   // callback tuỳ chọn: UI biết đang chờ tiếp
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    private var listeningTimer: CountDownTimer? = null
    @Volatile private var destroyed = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        onListeningStateChange(true)

        sttHelper.startListening(
            onResult = { text ->
                stopTimer()
                onListeningStateChange(false)
                onTextRecognized(text)
                // Không startListening() ở đây — ViewModel sẽ gọi lại sau khi TTS đọc xong
            },
            onError = { _ ->
                // Lỗi STT: dừng, báo UI, rồi tự restart sau 2 giây
                stopTimer()
                onListeningStateChange(false)
                scheduleRestart(delayMs = 2_000L)
            }
        )

        startTimeoutTimer()
    }

    fun stopListening() {
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
                // Hết timeout mà không có tiếng nói → restart loop ngay
                onListeningStateChange(false)
                onSilence()
                scheduleRestart(delayMs = 300L)
            }
        }.start()
    }

    /**
     * Dùng Handler thay vì coroutine để không phụ thuộc vào scope bên ngoài.
     * Đảm bảo restart kể cả khi ViewModel bận xử lý coroutine khác.
     */
    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!destroyed) startListening()
        }, delayMs)
    }

    private fun stopTimer() {
        listeningTimer?.cancel()
        listeningTimer = null
    }

    companion object {
        private const val LISTEN_TIMEOUT_MS = 15_000L  // 15 giây — đủ cho người nói chậm
    }
}