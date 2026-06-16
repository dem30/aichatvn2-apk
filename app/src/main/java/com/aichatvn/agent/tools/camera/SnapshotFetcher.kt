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
        .connectTimeout(15, TimeUnit.SECONDS)  // Tăng từ 10 lên 15
        .readTimeout(15, TimeUnit.SECONDS)     // Tăng từ 10 lên 15
        .build()

    /**
     * FIX (Bug #2 - test luôn ra offline):
     * client.newCall(request).execute() là gọi mạng đồng bộ (blocking I/O).
     * Trước đây hàm này KHÔNG ép chạy trên Dispatchers.IO. Vì toàn bộ chuỗi gọi
     * (testCamera -> scanCamera -> ... -> fetchSnapshot) chạy trong viewModelScope.launch
     * (mặc định Dispatchers.Main), execute() ném NetworkOnMainThreadException ngay khi
     * gọi, exception này bị catch (e: Exception) bắt im lặng và trả về null.
     * -> handleOfflineCamera() luôn nhận null -> camera luôn bị set isOnline = 0,
     *    bất kể URL có sống hay không, vì request thật ra chưa từng được gửi đi.
     *
     * Sửa: bọc toàn bộ phần gọi mạng trong withContext(Dispatchers.IO) ngay tại đây,
     * để hàm luôn an toàn dù caller gọi từ thread nào (không cần sửa các file gọi nó).
     */
    suspend fun fetchSnapshot(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            logger.d("SnapshotFetcher", "Bắt đầu lấy snapshot: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                logger.d("SnapshotFetcher", "Thành công: nhận ${bytes?.size ?: 0} bytes | url=$url")
                bytes
            } else {
                logger.e("SnapshotFetcher", "HTTP lỗi ${response.code} (${response.message}) | url=$url")
                null
            }
        } catch (e: SocketTimeoutException) {
            // Camera không phản hồi kịp trong 15s (connect hoặc read timeout)
            logger.e("SnapshotFetcher", "Timeout khi gọi $url: ${e.message}", e)
            null
        } catch (e: UnknownHostException) {
            // Sai domain/IP hoặc không có mạng để phân giải DNS
            logger.e("SnapshotFetcher", "Không phân giải được host: $url - ${e.message}", e)
            null
        } catch (e: IOException) {
            // Lỗi kết nối chung (refused, reset, SSL, v.v.)
            logger.e("SnapshotFetcher", "Lỗi I/O khi gọi $url: ${e.message}", e)
            null
        } catch (e: Exception) {
            // Bắt mọi lỗi khác để không làm crash app, nhưng vẫn log rõ loại lỗi
            // (trước đây nhánh này âm thầm nuốt cả NetworkOnMainThreadException)
            logger.e("SnapshotFetcher", "Lỗi không xác định (${e.javaClass.simpleName}) khi gọi $url: ${e.message}", e)
            null
        }
    }
}