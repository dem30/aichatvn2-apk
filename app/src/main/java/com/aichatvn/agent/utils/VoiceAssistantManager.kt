package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VoiceAssistantManager
 *
 * Loop hands-free không bao giờ chết:
 * - Có kết quả STT → onTextRecognized() → ViewModel gọi speakResponse(aiReply)
 *   → TTS đọc xong (trên main thread) → tự startListening() lại.
 * - Im lặng (NO_MATCH/TIMEOUT) → nghe lại NGAY
 * - Lỗi thật → backoff tăng dần
 * - Watchdog: nếu recognizer treo → tự restart
 * - Safety timer: nếu ViewModel quên gọi speakResponse() → tự restart sau 15s
 *
 * Fix so với bản trước:
 * - isListening dùng AtomicBoolean thay vì @Volatile để đảm bảo thread-safe
 *   khi đọc+ghi đồng thời từ main thread và coroutine dispatcher.
 * - Mọi scheduleRestart() đều chạy trên main thread (Handler mainLooper).
 *   TextToSpeechHelper đã đảm bảo onDone về main thread — nhưng thêm
 *   mainHandler.post() ở đây làm lớp bảo vệ thứ 2, tránh SpeechRecognizer
 *   bị gọi từ wrong thread.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogTimer: CountDownTimer? = null

    @Volatile private var destroyed = false
    @Volatile private var hardErrorRetryCount = 0

    // AtomicBoolean: compareAndSet() đảm bảo chỉ 1 thread thắng race, không bao giờ double-start
    private val isListening = AtomicBoolean(false)

    // Safety timer
    private var safetyRunnable: Runnable? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        // compareAndSet(false, true): chỉ tiếp tục nếu isListening đang là false
        // Nếu đang true (đã nghe) → return ngay, không double-start
        if (!isListening.compareAndSet(false, true)) return

        onListeningStateChange(true)

        sttHelper.startListening(
            onPartialResult = { partialText ->
                restartWatchdog()
                onPartialTranscript(partialText)
            },
            onResult = { text ->
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening.set(false)
                onListeningStateChange(false)
                // Báo ViewModel. ViewModel gọi speakResponse() để khép vòng.
                // Safety timer đề phòng ViewModel quên gọi.
                onTextRecognized(text)
                scheduleSafetyRestart()
            },
            onSoftError = {
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening.set(false)
                onListeningStateChange(false)
                onSilence()
                if (!destroyed) startListening()
            },
            onHardError = { reason ->
                stopWatchdog()
                isListening.set(false)
                onListeningStateChange(false)
                onListenError(reason)
                hardErrorRetryCount = (hardErrorRetryCount + 1).coerceAtMost(MAX_HARD_ERROR_STEPS)
                scheduleRestart(HARD_ERROR_BASE_DELAY_MS * hardErrorRetryCount)
            }
        )

        startWatchdog()
    }

    /**
     * ViewModel gọi sau khi có câu trả lời AI.
     * Manager phát TTS → onDone (main thread) → startListening() tự động.
     */
    fun speakResponse(text: String) {
        cancelSafetyRestart()
        ttsHelper.stop()
        if (destroyed) return

        if (text.isBlank()) {
            scheduleRestart(300L)
            return
        }

        ttsHelper.speak(text) {
            // TextToSpeechHelper đảm bảo callback này chạy trên main thread
            if (!destroyed) scheduleRestart(300L)
        }
    }

    fun stopListening() {
        cancelSafetyRestart()
        stopWatchdog()
        isListening.set(false)
        sttHelper.cancelListening()
        onListeningStateChange(false)
    }

    fun destroy() {
        destroyed = true
        cancelSafetyRestart()
        stopWatchdog()
        isListening.set(false)
        sttHelper.destroy()
        ttsHelper.shutdown()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = object : CountDownTimer(WATCHDOG_TIMEOUT_MS, WATCHDOG_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                isListening.set(false)
                onListeningStateChange(false)
                onSilence()
                scheduleRestart(200L)
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
        mainHandler.postDelayed({
            if (!destroyed) startListening()
        }, delayMs)
    }

    private fun scheduleSafetyRestart() {
        cancelSafetyRestart()
        val r = Runnable { if (!destroyed && !isListening.get()) startListening() }
        safetyRunnable = r
        mainHandler.postDelayed(r, RESULT_NO_TTS_TIMEOUT_MS)
    }

    private fun cancelSafetyRestart() {
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyRunnable = null
    }

    companion object {
        private const val WATCHDOG_TIMEOUT_MS        = 25_000L
        private const val HARD_ERROR_BASE_DELAY_MS   = 800L
        private const val MAX_HARD_ERROR_STEPS       = 5
        private const val RESULT_NO_TTS_TIMEOUT_MS   = 15_000L
    }
}