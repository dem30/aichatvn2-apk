package com.aichatvn.agent.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    var isReady = false
        private set

    // Trạng thái đang phát âm thanh (thread-safe)
    private val _isSpeaking = AtomicBoolean(false)
    val isSpeaking: Boolean
        get() = _isSpeaking.get() || (isReady && tts?.isSpeaking == true)

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
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.set(true)
                }
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
        _isSpeaking.set(false)
        callback?.let {
            // Đảm bảo callback chạy trên Main Thread để gọi startListening() an toàn
            mainHandler.post {
                it.invoke()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null || text.isBlank()) {
            _isSpeaking.set(false)
            onDone?.invoke()
            return
        }
        
        _isSpeaking.set(true)
        val utteranceId = "ai_speak_${utteranceCounter.getAndIncrement()}"
        synchronized(callbacks) {
            callbacks.clear()
            callbacks[utteranceId] = {
                _isSpeaking.set(false)
                onDone?.invoke()
            }
        }
        
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _isSpeaking.set(false)
            synchronized(callbacks) { callbacks.remove(utteranceId) }
            onDone?.invoke()
        }
    }

    fun stop() {
        _isSpeaking.set(false)
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Bỏ qua
        }
        synchronized(callbacks) { callbacks.clear() }
    }

    fun shutdown() {
        _isSpeaking.set(false)
        isReady = false
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Bỏ qua
        }
        synchronized(callbacks) { callbacks.clear() }
    }
}