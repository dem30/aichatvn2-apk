package com.aichatvn.agent.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * SpeechRecognizerHelper
 *
 * Nâng cấp realtime so với bản cũ:
 * - KHÔNG destroy()/create() lại SpeechRecognizer mỗi lần startListening().
 *   Tạo 1 lần, các lần sau dùng cancel() + startListening(intent) lại trên
 *   cùng instance -> bỏ được khoảng trống bind service (vài trăm ms) vốn là
 *   nguyên nhân chính gây mất chữ đầu câu khi loop lại.
 * - Bật và sử dụng onPartialResults thật sự: bắn partial text lên trên để
 *   (a) UI hiển thị real-time, (b) VoiceAssistantManager dùng làm tín hiệu
 *   "đang có người nói" để không restart giữa câu.
 * - Tinh chỉnh ngưỡng im lặng của recognizer (EXTRA_SPEECH_INPUT_*) để nó
 *   không tự cắt câu quá sớm khi người dùng dừng lấy hơi.
 * - Phân loại lỗi: lỗi "mềm" (im lặng / không khớp) khác lỗi "cứng"
 *   (mạng, mic bị chiếm, busy...) để VoiceAssistantManager quyết định
 *   restart ngay hay có backoff.
 */
class SpeechRecognizerHelper(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    // Chặn callback "trễ" bay tới sau khi đã cancel()/destroy() chủ động
    @Volatile private var generation = 0

    fun startListening(
        onPartialResult: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onSoftError: () -> Unit,           // im lặng / không nghe ra -> recognizer vẫn khỏe, nên nghe lại NGAY
        onHardError: (String) -> Unit       // lỗi thật (mạng, mic, busy...) -> nên có chút backoff
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onHardError("Speech recognition không khả dụng trên thiết bị này")
            return
        }

        val myGen = ++generation

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            speechRecognizer = it
            it.setRecognitionListener(buildListener(onPartialResult, onResult, onSoftError, onHardError) { myGen })
        }

        // Nếu vì lý do nào đó vẫn đang chạy phiên cũ, hủy gọn trước khi mở phiên mới
        // trên CHÍNH instance này (rẻ hơn nhiều so với destroy + create lại).
        recognizer.cancel()
        recognizer.startListening(buildIntent())
    }

    /** Dùng khi muốn dừng nghe nhưng vẫn giữ recognizer "nóng" cho lần sau (vd: đang phát TTS). */
    fun cancelListening() {
        generation++ // vô hiệu hóa callback của phiên đang treo (nếu có)
        speechRecognizer?.cancel()
    }

    fun destroy() {
        generation++
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) // chỉ cần kết quả tốt nhất -> nhẹ hơn, nhanh hơn
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

        // Cho phép nhận diện ngoại tuyến nếu máy có sẵn gói tiếng Việt offline.
        // Nếu không có, hệ thống tự fallback online -> không hại gì khi thêm.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        // Tinh chỉnh ngưỡng im lặng: đây là nguyên nhân hay gặp khiến recognizer
        // tự "chốt câu" quá sớm khi người dùng chỉ dừng lấy hơi giữa câu.
        // - COMPLETE: im lặng bao lâu thì coi là NÓI XONG (chốt kết quả cuối)
        // - POSSIBLY_COMPLETE: im lặng bao lâu thì coi là CÓ THỂ xong (để báo onPartial sớm hơn)
        // - MINIMUM_LENGTH: tối thiểu phải nghe bao lâu trước khi được phép coi là "xong"
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
    }

    private fun buildListener(
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onSoftError: () -> Unit,
        onHardError: (String) -> Unit,
        myGen: () -> Int
    ) = object : RecognitionListener {

        override fun onResults(results: Bundle?) {
            if (myGen() != generation) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) onResult(text) else onSoftError()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (myGen() != generation) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) onPartialResult(text)
        }

        override fun onError(errorCode: Int) {
            if (myGen() != generation) return
            when (errorCode) {
                // "Mềm": recognizer hoạt động bình thường, chỉ là không có/không khớp tiếng nói.
                // -> KHÔNG nên backoff, nghe lại ngay để không hớt mất câu kế tiếp.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onSoftError()

                // "Cứng": cần thật sự báo lỗi + có khoảng nghỉ trước khi thử lại.
                else -> onHardError(errorName(errorCode))
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Lỗi audio"
        SpeechRecognizer.ERROR_CLIENT -> "Lỗi client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền micro"
        SpeechRecognizer.ERROR_NETWORK -> "Lỗi mạng"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Mạng quá chậm/timeout"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer đang bận"
        SpeechRecognizer.ERROR_SERVER -> "Lỗi server nhận diện"
        else -> "Lỗi nhận diện giọng nói: $code"
    }
}