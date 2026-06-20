package com.aichatvn.agent.core
import android.net.Uri
import android.content.Context
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import org.nehuatl.llamacpp.LlamaHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRouterEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {

    companion object {
        private const val MODEL_FILENAME =
            "SmolLM2-135M-Instruct-Q4_K_M.gguf"

        private const val MODEL_URL =
            "https://github.com/dem30/aichatvn2-apk/releases/download/models/SmolLM2-135M-Instruct-Q4_K_M.gguf"

        private const val SAFE_FALLBACK =
            """{"plugin":"chat","action":"none","params":{}}"""

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RETRY = 2

        private const val MODEL_LOAD_TIMEOUT_MS = 20_000L
        private const val INFERENCE_TIMEOUT_MS = 12_000L
        private const val CONTEXT_LENGTH = 2048
    }

    /** Trạng thái tải model, UI có thể quan sát để hiện progress bar nếu muốn. */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int) : DownloadState()
        object Downloaded : DownloadState()
        data class Failed(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val downloadMutex = Mutex()

    @Volatile
    private var downloaded = false

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    // ===== INFERENCE ENGINE (llamacpp-kotlin) =====

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val llmEvents = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = context.contentResolver,
            scope = engineScope,
            sharedFlow = llmEvents
        )
    }

    private val modelLoadMutex = Mutex()

    @Volatile
    private var modelLoaded = false

    /**
     * Gọi MỘT LẦN từ AgentApplication.onCreate() để bắt đầu tải model NGAY KHI MỞ APP.
     * - Chạy trên CoroutineScope của Application (sống cùng vòng đời app), không gắn với
     *   bất kỳ Activity/Composable nào -> không bị huỷ khi xoay màn hình hoặc đổi Composable.
     * - Không chặn UI, không chặn lượt chat đầu tiên (predictIntent không tự tải nữa).
     * - An toàn khi gọi nhiều lần: Mutex + cờ `downloaded` đảm bảo chỉ có một lượt tải chạy thật.
     */
    fun prefetchModelAsync(scope: CoroutineScope) {
        scope.launch {
            ensureModelDownloaded()
        }
    }

    private suspend fun ensureModelDownloaded() {
        if (downloaded && modelFile.exists()) {
            _downloadState.value = DownloadState.Downloaded
            return
        }

        downloadMutex.withLock {
            // Re-check sau khi có lock, đề phòng một lượt gọi khác đã tải xong trong lúc chờ.
            if (downloaded && modelFile.exists()) {
                _downloadState.value = DownloadState.Downloaded
                return@withLock
            }
            if (modelFile.exists() && modelFile.length() > 0L) {
                downloaded = true
                _downloadState.value = DownloadState.Downloaded
                logger.d("LocalRouterEngine", "Model đã có sẵn trên máy, không tải lại.")
                return@withLock
            }

            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (attempt in 1..MAX_RETRY) {
                    try {
                        logger.d("LocalRouterEngine", "Bắt đầu tải model (lần $attempt)...")
                        downloadToFile()
                        downloaded = true
                        _downloadState.value = DownloadState.Downloaded
                        logger.d("LocalRouterEngine", "Model downloaded: ${modelFile.absolutePath}")
                        return@withContext
                    } catch (e: Exception) {
                        lastError = e
                        logger.e("LocalRouterEngine", "Tải model lần $attempt thất bại: ${e.message}", e)
                    }
                }
                _downloadState.value = DownloadState.Failed(lastError?.message ?: "Lỗi tải model không xác định")
            }
        }
    }

    /**
     * Tải vào file TẠM (.tmp) trước, chỉ rename sang tên thật khi đã tải ĐỦ dung lượng.
     * Tránh trường hợp app bị kill nửa đường -> để lại file .gguf dở dang nhưng vẫn
     * "tồn tại" -> lần mở sau lầm tưởng đã tải xong và dùng model hỏng.
     */
    private fun downloadToFile() {
        val tmpFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} khi tải model")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPercent = -1

            connection.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                _downloadState.value = DownloadState.Downloading(percent)
                            }
                        }
                    }
                }
            }

            if (totalBytes > 0 && tmpFile.length() != totalBytes) {
                throw IOException("File tải về thiếu dữ liệu (${tmpFile.length()}/$totalBytes bytes)")
            }

            if (!tmpFile.renameTo(modelFile)) {
                throw IOException("Không thể đổi tên file tạm sang $MODEL_FILENAME")
            }
        } finally {
            connection.disconnect()
            if (tmpFile.exists()) tmpFile.delete() // dọn file tạm nếu rename thất bại / lỗi giữa đường
        }
    }

    /**
     * Load model .gguf vào llama.cpp context (chỉ chạy 1 lần, các lần gọi sau dùng lại context cũ).
     * Mutex đảm bảo không load song song khi nhiều coroutine gọi predictIntent() cùng lúc.
     */
    private suspend fun ensureModelLoaded(): Boolean {
        if (modelLoaded) return true
        modelLoadMutex.withLock {
            if (modelLoaded) return true

            val loadedDeferred = CompletableDeferred<Boolean>()
            try {
                llamaHelper.load(
    path = Uri.fromFile(modelFile).toString(),
    contextLength = CONTEXT_LENGTH
)
              {
                    // callback: context id trả về khi load xong
                    loadedDeferred.complete(true)
                }
            } catch (e: Exception) {
                logger.e("LocalRouterEngine", "Load model lỗi: ${e.message}", e)
                if (!loadedDeferred.isCompleted) loadedDeferred.complete(false)
            }

            modelLoaded = withTimeoutOrNull(MODEL_LOAD_TIMEOUT_MS) { loadedDeferred.await() } ?: false
            if (!modelLoaded) {
                logger.e("LocalRouterEngine", "Load model timeout/thất bại sau ${MODEL_LOAD_TIMEOUT_MS}ms")
            }
        }
        return modelLoaded
    }

    /**
     * Trả về intent JSON dự đoán bởi model local (SmolLM2, chạy qua llamacpp-kotlin / llama.cpp).
     *
     * - Nếu model chưa tải xong hoặc load context thất bại -> trả SAFE_FALLBACK ngay,
     *   không làm chậm lượt chat của user.
     * - Có timeout tổng INFERENCE_TIMEOUT_MS để tránh treo UI nếu model chạy quá lâu trên máy yếu.
     */
    suspend fun predictIntent(prompt: String): String {
        return try {
            if (!downloaded || !modelFile.exists()) {
                logger.d(
                    "LocalRouterEngine",
                    "Model chưa sẵn sàng (state=${_downloadState.value}) -> fallback chat"
                )
                return SAFE_FALLBACK
            }

            if (!ensureModelLoaded()) {
                logger.e("LocalRouterEngine", "Model không load được -> fallback chat")
                return SAFE_FALLBACK
            }

            val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) { runInference(prompt) }
            result?.takeIf { it.isNotBlank() } ?: SAFE_FALLBACK
        } catch (e: Exception) {
            logger.e("LocalRouterEngine", e.message ?: "predictIntent error", e)
            SAFE_FALLBACK
        }
    }

    /**
     * Chạy 1 lượt generate qua llamaHelper.predict() và gom token (event.word) lại thành chuỗi.
     * Dừng sớm khi đã thấy 1 object JSON cân bằng dấu { } (đỡ tốn token sinh thừa sau khi
     * model đã trả xong JSON) hoặc khi model tự phát Done/Error.
     *
     * ⚠️ Lib io.github.ljcamargo:llamacpp-kotlin đang ở bản early-alpha (0.2.0), API có thể đổi.
     * Tên field `event.word` và hành vi dừng giữa chừng của `stopPrediction()` được suy ra từ
     * README, chưa đối chiếu trực tiếp với source — nên build thử + log thật trước khi tin tưởng
     * 100%, hoặc mở source LlamaHelper.kt/LlamaContext.kt trong repo để xác nhận lại.
     */
    private suspend fun runInference(prompt: String): String {
        val builder = StringBuilder()
        val done = CompletableDeferred<Unit>()
        var openBraces = 0
        var sawFirstBrace = false

        val collectorJob = engineScope.launch {
            llmEvents.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        val token = event.word ?: ""
                        builder.append(token)
                        token.forEach { ch ->
                            if (ch == '{') {
                                openBraces++
                                sawFirstBrace = true
                            }
                            if (ch == '}') openBraces--
                        }
                        if (sawFirstBrace && openBraces <= 0 && !done.isCompleted) {
                            llamaHelper.stopPrediction()
                            done.complete(Unit)
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        logger.e("LocalRouterEngine", "LLMEvent.Error trong lúc generate")
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    else -> { /* Started hoặc event khác: bỏ qua */ }
                }
            }
        }

        llamaHelper.predict(prompt)
        done.await()
        collectorJob.cancel()

        return builder.toString()
    }
}