package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer
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

    // ✅ FIX #3: Trước đây scheduleRestart() dùng Handler.postDelayed() rời, không lưu lại
    // Runnable nên không thể hủy. Hậu quả: STT lỗi -> lên lịch restart sau 2s -> người chăm
    // sóc bấm "Tắt mic" trong 2s đó (stopListening() chạy) -> nhưng sau 2s Handler vẫn chạy,
    // mic tự bật lại dù vừa bị tắt thủ công. Giờ lưu Runnable + Handler dùng chung để
    // stopListening()/destroy() có thể removeCallbacks() hủy đúng restart đang chờ.
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    @Volatile private var destroyed = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        // ✅ FIX: SpeechRecognizer yêu cầu Main thread tuyệt đối. Nếu caller đang ở
        // thread khác (vd: TTS onDone callback), post về Main để đảm bảo an toàn.
        // Nếu đã ở Main thread rồi, mainHandler.post() vẫn hoạt động đúng (chỉ queue
        // thêm 1 frame, không gây vấn đề gì).
        if (Looper.myLooper() != Looper.getMainLooper()) {
            restartHandler.post { startListening() }
            return
        }
        cancelPendingRestart()
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
        // ✅ FIX: Giảm timeout từ 15 giây → 8 giây
        // Lý do:
        // - Người nói thường mất 2-5s cho 1 câu
        // - 8s đủ chịu được speech chậm (người già, nước ngoài...)
        // - Giảm latency: nếu im 2 giây → kết thúc sớm hơn, không chờ timeout
        // - Cải thiện UX: feedback nhanh hơn → người dùng tưởng app đang nghe
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
     *
     * ✅ FIX #3: lưu lại Runnable vào pendingRestart để stopListening()/destroy() có thể
     * hủy đúng restart đang chờ — tránh mic tự bật lại sau khi đã bị tắt thủ công.
     */
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
        // ✅ FIX: Giảm từ 15_000L → 8_000L (8 giây)
        // Giải thích chi tiết xem ở startTimeoutTimer()
        private const val LISTEN_TIMEOUT_MS = 8_000L
    }
}