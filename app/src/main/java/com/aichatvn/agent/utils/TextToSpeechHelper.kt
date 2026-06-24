package com.aichatvn.agent.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TextToSpeechHelper
 *
 * Fix quan trọng: UtteranceProgressListener.onDone() / onError() được Android
 * gọi trên **background thread**, KHÔNG phải main thread.
 * SpeechRecognizer (và nhiều Android API khác) yêu cầu được gọi từ main thread —
 * nếu gọi từ background thread sẽ bị ignore âm thầm hoặc crash không có log rõ ràng.
 *
 * → Tất cả callback onDone đều được dispatch về main thread trước khi invoke.
 */
class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isReady = false
        private set

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("vi", "VN")
            isReady = true
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null || text.isBlank()) {
            // Gọi ngay trên main thread (hàm này luôn được gọi từ main thread)
            mainHandler.post { onDone?.invoke() }
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // ⚠️ Đây là background thread — phải post về main thread
                mainHandler.post { onDone?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { onDone?.invoke() }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        isReady = false
    }
}