package com.aichatvn.agent.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TextToSpeechHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

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
            onDone?.invoke()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone?.invoke() }
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