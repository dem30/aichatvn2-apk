package com.aichatvn.agent.core

import android.content.Context
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    }

    /** ✅ MỚI: trạng thái tải model, UI có thể quan sát để hiện progress bar nếu muốn. */
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

    /**
     * ✅ MỚI: Gọi MỘT LẦN từ AgentApplication.onCreate() để bắt đầu tải model NGAY KHI MỞ APP.
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
     * ✅ Tải vào file TẠM (.tmp) trước, chỉ rename sang tên thật khi đã tải ĐỦ dung lượng.
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
     * Trả về intent JSON dự đoán bởi model local.
     *
     * ⚠️ LƯU Ý: hàm này KHÔNG còn tự kích hoạt tải model (việc tải đã được
     * AgentApplication.onCreate() khởi động từ lúc mở app). Nếu model chưa tải xong
     * tại thời điểm gọi, trả fallback "chat" ngay để không làm chậm lượt chat của user.
     *
     * ⚠️ LƯU Ý 2: engine hiện CHƯA thực sự load/run file .gguf — thư viện inference
     * (vd. llamacpp-kotlin) đang bị comment trong build.gradle.kts, nên hàm này luôn
     * trả SAFE_FALLBACK. Khi tích hợp inference thật, thay đoạn "return SAFE_FALLBACK"
     * cuối cùng bằng lời gọi model thật (input: prompt, modelFile).
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
            // TODO: gọi inference thật ở đây khi tích hợp llama.cpp
            SAFE_FALLBACK
        } catch (e: Exception) {
            logger.e("LocalRouterEngine", e.message ?: "predictIntent error", e)
            SAFE_FALLBACK
        }
    }
}