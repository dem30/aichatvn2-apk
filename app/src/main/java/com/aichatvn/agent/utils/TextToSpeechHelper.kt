package com.aichatvn.agent.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    var isReady = false
        private set

    // ✅ FIX #4: Trước đây mọi speak() dùng CÙNG utteranceId "ai_speak" và set lại
    // UtteranceProgressListener mỗi lần gọi. Nếu 2 lệnh speak() xảy ra gần nhau (vd: lời
    // chào đang đọc thì observeAndSpeak() phát hiện tin nhắn assistant khác và speak() lần
    // nữa), QUEUE_FLUSH sẽ ngắt utterance đầu nhưng onDone của lượt đầu có thể KHÔNG được
    // gọi → đứt vòng lặp hands-free (không tự startListening() lại được).
    // Giờ: listener chỉ được đăng ký 1 LẦN DUY NHẤT trong onInit(), mỗi speak() dùng
    // utteranceId riêng biệt, map callback theo id. QUEUE_FLUSH đảm bảo bất kỳ utterance nào
    // trước đó đã bị huỷ — nên ta chủ động clear callback cũ (sẽ không bao giờ nhận được
    // onDone) ngay khi đăng ký utterance mới, để không giữ tham chiếu treo.
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceCounter = 0

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("vi", "VN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    completeCallback(utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    completeCallback(utteranceId)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    completeCallback(utteranceId)
                }
            })
            isReady = true
        }
    }

    private fun completeCallback(utteranceId: String?) {
        if (utteranceId == null) return
        val callback = synchronized(callbacks) { callbacks.remove(utteranceId) }
        callback?.invoke()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null || text.isBlank()) {
            onDone?.invoke()
            return
        }
        val utteranceId = "ai_speak_${utteranceCounter++}"
        synchronized(callbacks) {
            // QUEUE_FLUSH huỷ mọi utterance đang chờ/đang đọc trước đó — callback cũ
            // (nếu có) sẽ không bao giờ nhận được onDone, nên dọn luôn để không treo.
            callbacks.clear()
            if (onDone != null) callbacks[utteranceId] = onDone
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        synchronized(callbacks) { callbacks.clear() }
    }

    fun shutdown() {
        tts?.shutdown()
        isReady = false
        synchronized(callbacks) { callbacks.clear() }
    }
}