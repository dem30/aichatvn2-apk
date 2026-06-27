package com.aichatvn.agent.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var useGoogleEngine = true

    @Volatile
    var isReady = false
        private set

    private val _isSpeaking = AtomicBoolean(false)
    val isSpeaking: Boolean
        get() = _isSpeaking.get() || (isReady && tts?.isSpeaking == true)

    private val callbacks = mutableMapOf<String, () -> Unit>()
    private val utteranceCounter = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingSpeak: Runnable? = null

    init {
        initTts()
    }

    private fun initTts() {
        try {
            if (useGoogleEngine) {
                tts = TextToSpeech(context.applicationContext, this, "com.google.android.tts")
            } else {
                tts = TextToSpeech(context.applicationContext, this)
            }
        } catch (e: Exception) {
            if (useGoogleEngine) {
                useGoogleEngine = false
                initTts()
            } else {
                Log.e("TextToSpeechHelper", "Không thể khởi tạo TTS: ${e.message}")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("vi", "VN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }

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

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    completeCallback(utteranceId)
                }
            })
            isReady = true

            val pending = pendingSpeak
            pendingSpeak = null
            if (pending != null) {
                // Đảm bảo chạy tác vụ phát giọng nói còn chờ trên Main Thread một cách an toàn nhất
                mainHandler.post(pending)
            }
        } else {
            if (useGoogleEngine) {
                useGoogleEngine = false
                try {
                    tts?.shutdown()
                } catch (_: Exception) {}
                initTts()
            }
        }
    }

    private fun completeCallback(utteranceId: String?) {
        if (utteranceId == null) return
        val callback = synchronized(callbacks) { callbacks.remove(utteranceId) }
        _isSpeaking.set(false)
        callback?.let {
            mainHandler.post {
                it.invoke()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            _isSpeaking.set(false)
            onDone?.invoke()
            return
        }

        if (!isReady || tts == null) {
            pendingSpeak = Runnable {
                speak(text, onDone)
            }
            return
        }

        pendingSpeak = null
        _isSpeaking.set(true)
        val utteranceId = "ai_speak_${utteranceCounter.getAndIncrement()}"
        synchronized(callbacks) {
            callbacks.clear()
            callbacks[utteranceId] = {
                _isSpeaking.set(false)
                onDone?.invoke()
            }
        }

        // KHẮC PHỤC CRASH: Bao bọc cuộc gọi tts?.speak bằng try-catch đề phòng thiết bị lỗi hỏng dịch vụ TTS Engine ngầm
        val result = try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e("TextToSpeechHelper", "Lỗi phát âm thanh TTS: ${e.message}", e)
            TextToSpeech.ERROR
        }

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