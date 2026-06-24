package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognizerHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pendingStart: Runnable? = null

    fun startListening(
        onResult: (String) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit // Truyền errorCode để phân loại lỗi
    ) {
        pendingStart?.let { mainHandler.removeCallbacks(it) }

        val task = Runnable {
            pendingStart = null

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError(-1, "Speech recognition không khả dụng trên thiết bị này")
                return@Runnable
            }

            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Bỏ qua lỗi dọn dẹp cũ
            }
            recognizer = null

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.trim() ?: ""
                            if (text.isNotBlank()) onResult(text) else onError(-2, "Không nhận được nội dung")
                        }
                        override fun onError(errorCode: Int) {
                            onError(errorCode, "Lỗi nhận diện giọng nói: $errorCode")
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(intent)
                }
            } catch (e: Exception) {
                onError(-3, "Không thể khởi tạo SpeechRecognizer: ${e.message}")
            }
        }

        pendingStart = task
        mainHandler.post(task)
    }

    fun destroy() {
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null

        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Bỏ qua ngoại lệ dọn dẹp
            } finally {
                recognizer = null
            }
        }
    }
}