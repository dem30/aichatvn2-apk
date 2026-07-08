package com.aichatvn.agent.tools.camera

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotFetcher @Inject constructor(
    private val logger: Logger
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSnapshot(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            logger.d("SnapshotFetcher", "Bắt đầu lấy snapshot: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            // KHẮC PHỤC RÒ RỈ: Ép buộc đóng Response bằng '.use' trong mọi tình huống (thành công lẫn thất bại)
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    logger.d("SnapshotFetcher", "Thành công: nhận ${bytes?.size ?: 0} bytes | url=$url")
                    bytes
                } else {
                    logger.e("SnapshotFetcher", "HTTP lỗi ${response.code} (${response.message}) | url=$url")
                    null
                }
            }
        } catch (e: SocketTimeoutException) {
            logger.e("SnapshotFetcher", "Timeout khi gọi $url: ${e.message}", e)
            null
        } catch (e: UnknownHostException) {
            logger.e("SnapshotFetcher", "Không phân giải được host: $url - ${e.message}", e)
            null
        } catch (e: IOException) {
            logger.e("SnapshotFetcher", "Lỗi I/O khi gọi $url: ${e.message}", e)
            null
        } catch (e: Exception) {
            logger.e("SnapshotFetcher", "Lỗi không xác định (${e.javaClass.simpleName}) khi gọi $url: ${e.message}", e)
            null
        }
    }
}