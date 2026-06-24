package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wrapper cho Android SpeechRecognizer.
 *
 * RULE CỨNG của Android: SpeechRecognizer phải được CREATE, gọi startListening(),
 * và destroy() trên CÙNG MỘT THREAD — và thread đó phải có Looper (Main thread).
 * Vi phạm → silently fail: không throw, không crash, chỉ không nhận giọng nói.
 *
 * Fix: tất cả thao tác với SpeechRecognizer đều chạy qua mainHandler.post().
 * Nhưng KHÔNG dùng post() cho destroy() nếu sau đó ngay lập tức có post() tạo mới —
 * vì 2 post() chạy tuần tự: post(destroy) → post(create+start), tránh race condition.
 */
class SpeechRecognizerHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Guard tránh startListening() chồng lên nhau khi được gọi rapid
    @Volatile private var pendingStart: Runnable? = null

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // Hủy pending start cũ (nếu có) trước khi schedule cái mới
        pendingStart?.let { mainHandler.removeCallbacks(it) }

        val task = Runnable {
            pendingStart = null

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition không khả dụng trên thiết bị này")
                return@Runnable
            }

            // Destroy recognizer cũ ĐỒNG BỘ trước khi tạo mới — cùng trong 1 post()
            // nên không có race condition với post() khác
            recognizer?.destroy()
            recognizer = null

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) onResult(text) else onError("Không nhận được nội dung")
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
                // startListening() ngay sau setRecognitionListener() — cùng post(), cùng thread
                startListening(intent)
            }
        }

        pendingStart = task
        mainHandler.post(task)
    }

    fun destroy() {
        // Hủy pending start trước — tránh create sau destroy
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null

        // Post destroy để đảm bảo chạy cùng Main thread với mọi thao tác khác
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}