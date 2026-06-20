package com.aichatvn.agent.core

import android.content.Context
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ljcamargo.llamacpp.LlamaHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Chạy SmolLM2-135M-Instruct (định dạng GGUF) NGAY TRÊN MÁY bằng thư viện
 * kotlinllamacpp (https://github.com/ljcamargo/kotlinllamacpp) - binding Kotlin cho
 * llama.cpp, chỉ hỗ trợ thiết bị arm64-v8a.
 *
 * ⚠️ Lưu ý quan trọng:
 * - Thư viện này còn ở bản ALPHA SỚM (tác giả ghi rõ "API may change in the future").
 *   Đoạn nối dưới đây dựa theo README hiện tại của repo; nếu bạn update lên version mới
 *   và build báo lỗi (vd. tên class LLMEvent đổi), hãy đối chiếu lại với app demo trong
 *   thư mục /app của repo đó.
 * - MỌI lỗi/timeout (model chưa nạp xong, chạy trên emulator x86_64, hết giờ chờ...) đều
 *   trả về AN TOÀN {"plugin":"chat","action":"none"} -> AgentKernel hiểu là "không khớp
 *   lệnh thiết bị" và để ChatSkill xử lý như chat thường. Vì vậy lỡ tích hợp sai cũng
 *   không khiến app tự ý bật/tắt thiết bị ngoài ý muốn.
 */
@Singleton
class LocalRouterEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        // Đặt đúng tên file GGUF bạn bỏ vào app/src/main/assets/.
        // Tải tại: https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct-GGUF (chọn bản Q4_K_M hoặc Q8_0)
        private const val MODEL_FILENAME = "smollm2-135m-instruct-q4_k_m.gguf"
        private const val CONTEXT_LENGTH = 1024
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val INFERENCE_TIMEOUT_MS = 8_000L
        private const val SAFE_FALLBACK = """{"plugin": "chat", "action": "none", "params": {}}"""
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 64)

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = context.contentResolver,
            scope = scope,
            sharedFlow = llmFlow
        )
    }

    @Volatile private var modelReady = false
    @Volatile private var modelLoading = false

    /**
     * Copy model từ assets ra bộ nhớ trong (nếu chưa có) rồi nạp vào llama.cpp.
     * An toàn khi gọi nhiều lần - chỉ thực sự nạp một lần.
     */
    private suspend fun ensureModelLoaded(): Boolean {
        if (modelReady) return true
        if (modelLoading) return false // đang nạp dở ở lượt gọi khác -> lượt này coi như chưa sẵn sàng

        modelLoading = true
        try {
            val modelFile = File(context.filesDir, MODEL_FILENAME)
            if (!modelFile.exists()) {
                context.assets.open(MODEL_FILENAME).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        llamaHelper.load(path = modelFile.absolutePath, contextLength = CONTEXT_LENGTH) {
                            if (cont.isActive) cont.resume(true)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } ?: false

            modelReady = loaded
            logger.d(
                "LocalRouterEngine",
                if (loaded) "✅ Model SmolLM2 đã nạp xong" else "❌ Nạp model timeout/thất bại (có thể do thiết bị không phải arm64-v8a, hoặc thiếu file assets)"
            )
            return loaded
        } catch (e: Exception) {
            logger.e("LocalRouterEngine", "Lỗi nạp model local: ${e.message}", e)
            return false
        } finally {
            modelLoading = false
        }
    }

    /**
     * Trả về JSON thuần dạng {"plugin": "...", "action": "...", "params": {...}}.
     * Trả về SAFE_FALLBACK khi model chưa sẵn sàng hoặc có bất kỳ lỗi/timeout nào.
     */
    suspend fun predictIntent(prompt: String): String {
        if (!ensureModelLoaded()) return SAFE_FALLBACK

        val resultText = StringBuilder()
        return try {
            val generated = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                coroutineScope {
                    // ✅ Subscribe vào sharedFlow TRƯỚC khi gọi predict(), vì MutableSharedFlow
                    // không replay token đã bắn ra trước khi có collector.
                    val collector = async {
                        llmFlow
                            .onEach { event ->
                                if (event is LlamaHelper.LLMEvent.Ongoing) {
                                    resultText.append(event.word ?: "")
                                }
                            }
                            .firstOrNull { it is LlamaHelper.LLMEvent.Done || it is LlamaHelper.LLMEvent.Error }
                    }
                    yield() // nhường CPU một nhịp để collector kịp subscribe
                    llamaHelper.predict(prompt)
                    collector.await()
                }
                resultText.toString().trim()
            }

            try { llamaHelper.stopPrediction() } catch (_: Exception) {}

            generated?.takeIf { it.isNotBlank() } ?: SAFE_FALLBACK
        } catch (e: Exception) {
            logger.e("LocalRouterEngine", "Inference error: ${e.message}", e)
            SAFE_FALLBACK
        }
    }
}