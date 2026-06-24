package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SpeechRecognizerHelper(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isReadyForSpeech = false

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition không khả dụng trên thiết bị này")
            return
        }

        // ✅ FIX: Reset ready flag mỗi lần tạo STT mới
        isReadyForSpeech = false

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                // ✅ FIX: Đợi onReadyForSpeech trước khi thực sự bắt đầu ghi âm
                override fun onReadyForSpeech(params: Bundle?) {
                    isReadyForSpeech = true
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) onResult(text)
                    else onError("Không nhận được nội dung")
                }
                override fun onError(errorCode: Int) {
                    onError("Lỗi nhận diện giọng nói: $errorCode")
                }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        // ✅ FIX: Đợi 200ms để STT hoàn toàn sẵn sàng trước khi gọi startListening()
        // Timing này bắt được onReadyForSpeech callback và đảm bảo mic sẵn sàng nghe
        CoroutineScope(Dispatchers.Main).launch {
            delay(200)
            speechRecognizer?.startListening(intent)
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isReadyForSpeech = false
    }
}
