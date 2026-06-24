package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer

/**
 * VoiceAssistantManager
 *
 * Loop hands-free không bao giờ chết:
 * - Có kết quả STT → onTextRecognized() → ViewModel gọi speakResponse(aiReply)
 *   → TTS đọc xong → Manager tự startListening() lại.
 *   Vòng lặp được đóng kín bên trong Manager, KHÔNG phụ thuộc ViewModel
 *   nhớ gọi lại startListening() thủ công (đây là nguyên nhân kẹt của bản cũ).
 * - Im lặng thật (lỗi mềm: NO_MATCH/TIMEOUT) → nghe lại NGAY, không delay
 * - Lỗi thật (mạng, mic, busy...) → backoff tăng dần, có giới hạn
 * - Đang có partial result (người dùng còn nói) → watchdog được reset, không bị ngắt giữa câu
 * - Nếu ViewModel KHÔNG gọi speakResponse() (ví dụ lỗi AI), safety timer tự
 *   restart sau RESULT_NO_TTS_TIMEOUT_MS để không bao giờ bị kẹt vĩnh viễn.
 *
 * Luồng chuẩn:
 *   startListening()
 *     → onResult → onTextRecognized(text)     [ViewModel xử lý AI]
 *       → ViewModel gọi speakResponse(reply)  [Manager phát TTS]
 *         → TTS onDone → startListening()     [tự lặp lại]
 *
 * Chỉ dừng khi stopListening() / destroy() được gọi.
 */
class VoiceAssistantManager(
    context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {},
    private val onPartialTranscript: (String) -> Unit = {},
    private val onListenError: (String) -> Unit = {}
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    // Watchdog: lưới an toàn khi recognizer "treo" không bắn callback nào
    private var watchdogTimer: CountDownTimer? = null

    // Safety timer: restart listening nếu ViewModel quên gọi speakResponse()
    private var safetyRestartHandler: android.os.Handler? = null
    private var safetyRestartRunnable: Runnable? = null

    @Volatile private var destroyed = false
    @Volatile private var hardErrorRetryCount = 0

    // Guard chống startListening() chạy đồng thời 2 lần
    @Volatile private var isListening = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed || isListening) return
        isListening = true
        onListeningStateChange(true)

        sttHelper.startListening(
            onPartialResult = { partialText ->
                restartWatchdog()
                onPartialTranscript(partialText)
            },
            onResult = { text ->
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening = false
                onListeningStateChange(false)

                // Báo ViewModel xử lý AI. ViewModel nên gọi speakResponse() để khép vòng.
                // Nếu quên gọi, safety timer sẽ tự restart sau RESULT_NO_TTS_TIMEOUT_MS.
                onTextRecognized(text)
                scheduleSafetyRestart()
            },
            onSoftError = {
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening = false
                onListeningStateChange(false)
                onSilence()
                if (!destroyed) startListening()
            },
            onHardError = { reason ->
                stopWatchdog()
                isListening = false
                onListeningStateChange(false)
                onListenError(reason)
                hardErrorRetryCount = (hardErrorRetryCount + 1).coerceAtMost(MAX_HARD_ERROR_STEPS)
                val delay = HARD_ERROR_BASE_DELAY_MS * hardErrorRetryCount
                scheduleRestart(delayMs = delay)
            }
        )

        startWatchdog()
    }

    /**
     * ViewModel gọi hàm này để phát TTS sau khi AI trả lời.
     * Sau khi TTS đọc xong, Manager tự startListening() lại — khép vòng lặp.
     *
     * Gọi hàm này sẽ huỷ safety timer (tránh startListening() bị gọi 2 lần).
     */
    fun speakResponse(text: String) {
        cancelSafetyRestart()       // TTS tự lo restart, safety timer không cần nữa
        ttsHelper.stop()            // huỷ TTS đang phát dở (nếu có)
        if (destroyed) return

        if (text.isBlank()) {
            scheduleRestart(delayMs = 300L)
            return
        }

        ttsHelper.speak(text) {
            if (!destroyed) scheduleRestart(delayMs = 300L)
        }
    }

    fun stopListening() {
        cancelSafetyRestart()
        stopWatchdog()
        isListening = false
        sttHelper.cancelListening()
        onListeningStateChange(false)
    }

    fun destroy() {
        destroyed = true
        cancelSafetyRestart()
        stopWatchdog()
        isListening = false
        sttHelper.destroy()
        ttsHelper.shutdown()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = object : CountDownTimer(WATCHDOG_TIMEOUT_MS, WATCHDOG_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                // Recognizer treo, không bắn callback nào → tự restart
                isListening = false
                onListeningStateChange(false)
                onSilence()
                scheduleRestart(delayMs = 200L)
            }
        }.start()
    }

    private fun restartWatchdog() = startWatchdog()

    private fun stopWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = null
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!destroyed) startListening()
        }, delayMs)
    }

    /**
     * Safety timer: nếu sau RESULT_NO_TTS_TIMEOUT_MS mà speakResponse() chưa được gọi
     * (ViewModel bị lỗi, hoặc quên gọi), tự restart để không kẹt vĩnh viễn.
     */
    private fun scheduleSafetyRestart() {
        cancelSafetyRestart()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = Runnable {
            if (!destroyed && !isListening) startListening()
        }
        safetyRestartHandler = handler
        safetyRestartRunnable = runnable
        handler.postDelayed(runnable, RESULT_NO_TTS_TIMEOUT_MS)
    }

    private fun cancelSafetyRestart() {
        safetyRestartRunnable?.let { safetyRestartHandler?.removeCallbacks(it) }
        safetyRestartHandler = null
        safetyRestartRunnable = null
    }

    companion object {
        private const val WATCHDOG_TIMEOUT_MS = 25_000L
        private const val HARD_ERROR_BASE_DELAY_MS = 800L
        private const val MAX_HARD_ERROR_STEPS = 5   // backoff tối đa 800ms × 5 = 4s

        // Nếu sau thời gian này mà speakResponse() vẫn chưa được gọi → tự restart
        private const val RESULT_NO_TTS_TIMEOUT_MS = 15_000L
    }
}