package com.aichatvn.agent.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    var isReady = false
        private set

    private val callbacks = mutableMapOf<String, () -> Unit>()
    private val utteranceCounter = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext, this)
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
        callback?.let {
            // Đảm bảo callback chạy trên Main Thread để gọi startListening() an toàn
            mainHandler.post {
                it.invoke()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null || text.isBlank()) {
            onDone?.invoke()
            return
        }
        val utteranceId = "ai_speak_${utteranceCounter.getAndIncrement()}"
        synchronized(callbacks) {
            callbacks.clear()
            if (onDone != null) callbacks[utteranceId] = onDone
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Bỏ qua
        }
        synchronized(callbacks) { callbacks.clear() }
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Bỏ qua
        }
        isReady = false
        synchronized(callbacks) { callbacks.clear() }
    }
}