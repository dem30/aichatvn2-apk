package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper

class SpeechRecognizerHelper(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    // ✅ FIX ROOT CAUSE: SpeechRecognizer PHẢI được tạo và dùng trên Main thread.
    // Dùng Handler(mainLooper) thay vì CoroutineScope để đảm bảo điều này tuyệt đối,
    // không phụ thuộc vào Dispatcher nào đang gọi hàm này từ bên ngoài.
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // Đảm bảo tất cả logic SpeechRecognizer chạy trên Main thread
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition không khả dụng trên thiết bị này")
                return@post
            }

            // Destroy recognizer cũ trước khi tạo mới — phải trên Main thread
            speechRecognizer?.destroy()
            speechRecognizer = null

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
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
                // ✅ startListening() gọi ngay sau setRecognitionListener() trong cùng 1
                // post() — cùng Main thread, không cần delay nhân tạo nữa.
                startListening(intent)
            }
        }
    }

    fun destroy() {
        // Destroy cũng phải trên Main thread
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}