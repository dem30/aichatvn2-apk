package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pendingStart: Runnable? = null

    private val recognizerIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    init {
        // Đảm bảo khởi tạo thực thể sẵn sàng trên Main Thread để tăng tốc độ kích hoạt
        mainHandler.post {
            initRecognizer()
        }
    }

    private fun initRecognizer() {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } catch (e: Exception) {
                Log.e("SpeechRecognizerHelper", "Không thể khởi tạo SpeechRecognizer: ${e.message}")
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit,
        onSpeechStarted: (() -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onPartialResult: ((String) -> Unit)? = null,
        onReadyForSpeech: (() -> Unit)? = null
    ) {
        pendingStart?.let { mainHandler.removeCallbacks(it) }

        val task = Runnable {
            pendingStart = null

            initRecognizer()

            val rec = recognizer
            if (rec == null) {
                onError(-1, "Speech recognition không khả dụng trên thiết bị này")
                return@Runnable
            }

            // Hủy các phiên nghe dở dang trước đó để dọn dẹp luồng thay vì destroy đối tượng
            try {
                rec.cancel()
            } catch (e: Exception) {
                // Bỏ qua lỗi dọn dẹp tạm thời
            }

            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onReadyForSpeech?.invoke()
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) onResult(text) else onError(-2, "Không nhận được nội dung")
                }

                override fun onError(errorCode: Int) {
                    onError(errorCode, "Lỗi nhận diện giọng nói: $errorCode")
                }

                override fun onBeginningOfSpeech() {
                    onSpeechStarted?.invoke()
                }

                override fun onEndOfSpeech() {
                    onEndOfSpeech?.invoke()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) onPartialResult?.invoke(text)
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            try {
                rec.startListening(recognizerIntent)
            } catch (e: Exception) {
                onError(-3, "Không thể khởi chạy lắng nghe: ${e.message}")
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