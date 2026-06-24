package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer

/**
 * VoiceAssistantManager
 *
 * Loop hands-free không bao giờ chết:
 * - Có kết quả STT → onTextRecognized() → AI xử lý → TTS đọc → startListening() lại
 * - Im lặng thật (lỗi mềm: NO_MATCH/TIMEOUT) → nghe lại NGAY, không delay
 *   (đây là điểm khác biệt chính so với bản cũ — trước đây mọi lỗi đều
 *   chờ 2s, khiến câu nói tiếp theo của người dùng bị hớt mất phần đầu)
 * - Lỗi thật (mạng, mic, busy...) → backoff tăng dần, có giới hạn, để
 *   không spin loop liên tục khi có sự cố thật
 * - Đang có partial result (người dùng còn nói) → watchdog được "đánh thức"
 *   lại, không bị app tự ngắt giữa câu
 *
 * Chỉ dừng khi toggleVoiceMode() tắt hoặc destroy().
 */
class VoiceAssistantManager(
    context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {},            // callback tuỳ chọn: UI biết đang chờ tiếp
    private val onPartialTranscript: (String) -> Unit = {}, // MỚI: hiển thị chữ real-time khi đang nói
    private val onListenError: (String) -> Unit = {}    // MỚI: UI có thể hiện toast khi lỗi thật xảy ra
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    // Watchdog là lưới an toàn, KHÔNG phải cơ chế chính để cắt câu — việc cắt câu
    // do im lặng đã giao cho ngưỡng EXTRA_SPEECH_INPUT_* bên trong recognizer lo.
    // Watchdog chỉ đề phòng trường hợp recognizer "treo", không bắn bất kỳ callback nào.
    private var watchdogTimer: CountDownTimer? = null

    @Volatile private var destroyed = false
    @Volatile private var hardErrorRetryCount = 0

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        onListeningStateChange(true)

        sttHelper.startListening(
            onPartialResult = { partialText ->
                // Người dùng đang nói thật -> reset watchdog để không bị app
                // tự ý ngắt phiên giữa câu, và đẩy lên UI để hiện real-time.
                restartWatchdog()
                onPartialTranscript(partialText)
            },
            onResult = { text ->
                stopWatchdog()
                hardErrorRetryCount = 0
                onListeningStateChange(false)
                onTextRecognized(text)
                // Không startListening() ở đây — ViewModel sẽ gọi lại sau khi TTS đọc xong
            },
            onSoftError = {
                // Chỉ là im lặng / không khớp -> recognizer vẫn khỏe mạnh.
                // Nghe lại NGAY để không bỏ lỡ câu kế tiếp của người dùng.
                stopWatchdog()
                hardErrorRetryCount = 0
                onListeningStateChange(false)
                onSilence()
                if (!destroyed) startListening()
            },
            onHardError = { reason ->
                // Lỗi thật: mạng, mic bị chiếm, recognizer busy...
                // Backoff tăng dần (tối đa ~4s) để tránh quay vòng liên tục khi
                // sự cố vẫn còn, nhưng vẫn tự hồi phục khi hết lỗi.
                stopWatchdog()
                onListeningStateChange(false)
                onListenError(reason)
                hardErrorRetryCount = (hardErrorRetryCount + 1).coerceAtMost(MAX_HARD_ERROR_STEPS)
                val delay = HARD_ERROR_BASE_DELAY_MS * hardErrorRetryCount
                scheduleRestart(delayMs = delay)
            }
        )

        startWatchdog()
    }

    fun stopListening() {
        stopWatchdog()
        // cancelListening() giữ recognizer "nóng" để lần startListening() kế tiếp
        // không phải bind lại service -> nhanh hơn nhiều so với destroy()+create().
        sttHelper.cancelListening()
        onListeningStateChange(false)
    }

    fun destroy() {
        destroyed = true
        stopWatchdog()
        sttHelper.destroy()
        ttsHelper.shutdown()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = object : CountDownTimer(WATCHDOG_TIMEOUT_MS, WATCHDOG_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                // Recognizer không bắn bất kỳ callback nào trong suốt thời gian dài
                // bất thường -> coi như treo, tự khởi động lại loop.
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

    companion object {
        // Lưới an toàn tuyệt đối — không phải cơ chế cắt câu chính (đó là việc của
        // EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS trong SpeechRecognizerHelper).
        private const val WATCHDOG_TIMEOUT_MS = 25_000L

        private const val HARD_ERROR_BASE_DELAY_MS = 800L
        private const val MAX_HARD_ERROR_STEPS = 5 // backoff tối đa ~4s (800ms * 5)
    }
}