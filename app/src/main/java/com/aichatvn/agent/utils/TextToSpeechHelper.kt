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

    // Hẹn giờ bảo vệ phòng lỗi treo callback của OS Android
    private var watchdogRunnable: Runnable? = null

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
        cancelWatchdog()
        val callback = synchronized(callbacks) { callbacks.remove(utteranceId) }
        _isSpeaking.set(false)
        callback?.let {
            mainHandler.post {
                it.invoke()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        cancelWatchdog()
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
        } else {
            // Lên lịch kích hoạt watchdog đề phòng treo âm thanh
            scheduleWatchdog(utteranceId, text)
        }
    }

    private fun scheduleWatchdog(utteranceId: String, text: String) {
        cancelWatchdog()
        // Tốc độ nói ước tính: 150ms/ký tự tiếng Việt + 4000ms sai số an toàn
        val estimatedDurationMs = (text.length * 150L) + 4000L
        val runnable = Runnable {
            Log.w("TextToSpeechHelper", "TTS Watchdog được kích hoạt cho $utteranceId do quá thời gian phản hồi.")
            completeCallback(utteranceId)
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, estimatedDurationMs)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    fun stop() {
        cancelWatchdog()
        _isSpeaking.set(false)
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Bỏ qua
        }
        synchronized(callbacks) { callbacks.clear() }
    }

    fun shutdown() {
        cancelWatchdog()
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