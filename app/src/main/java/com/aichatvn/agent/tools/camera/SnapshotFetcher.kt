package com.aichatvn.agent.tools.camera

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    // ✅ MỚI: client HTTP dùng chung cho MỌI camera trên LAN (xem CameraLanHttpClient.kt) — thay
    // cho việc tự dựng OkHttpClient.Builder() riêng. SnapshotFetcher là nơi gọi lặp lại nhiều lần
    // nhất tới CÙNG 1 camera (mỗi lượt CameraSkill.scanCamera theo lịch định kỳ) — đúng kịch bản
    // dễ trúng lỗi "connection tái sử dụng từ pool nhưng camera đã đóng" nhất nếu còn giữ pool
    // riêng cũ, nên gộp vào client dùng chung (đã tắt pool) là ưu tiên cao ở đây.
    @com.aichatvn.agent.di.CameraLanHttpClient cameraLanHttpClient: OkHttpClient,
    private val logger: Logger
) {

    private val client = cameraLanHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ✅ MỚI: hỗ trợ Basic Auth cho camera LAN yêu cầu đăng nhập (vd http://admin:pass@192.168.1.50/snap.jpg).
    // OkHttp KHÔNG tự đọc user:pass nhúng trong URL (khác HttpURLConnection/trình duyệt) — nếu
    // không xử lý riêng, request sẽ gửi đi KHÔNG có Authorization header và camera trả 401.
    //
    // username/password: optional, dùng khi URL không nhúng sẵn credential (vd cấu hình URL sạch
    // "http://192.168.1.50/snap.jpg" + nhập user/pass riêng ở màn Settings).
    //
    // Nếu URL có nhúng sẵn "user:pass@host" (dạng cũ), hàm tự tách ra và build Authorization header
    // tương ứng — không bắt người dùng phải sửa lại URL đã cấu hình từ trước.
    suspend fun fetchSnapshot(
        url: String,
        username: String? = null,
        password: String? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            logger.d("SnapshotFetcher", "Bắt đầu lấy snapshot: $url")

            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl == null) {
                logger.e("SnapshotFetcher", "URL không hợp lệ: $url")
                return@withContext null
            }

            // Ưu tiên username/password truyền vào; nếu không có, thử lấy từ user:pass nhúng
            // sẵn trong URL (HttpUrl tự parse phần userInfo dù OkHttp không tự gửi nó đi).
            val resolvedUser = username?.takeIf { it.isNotBlank() } ?: httpUrl.username.takeIf { it.isNotBlank() }
            val resolvedPass = password?.takeIf { it.isNotBlank() } ?: httpUrl.password.takeIf { it.isNotBlank() }

            val requestBuilder = Request.Builder()
                .url(httpUrl)
                .get()

            if (!resolvedUser.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(resolvedUser, resolvedPass ?: ""))
            }

            val request = requestBuilder.build()

            // KHẮC PHỤC RÒ RỈ: Ép buộc đóng Response bằng '.use' trong mọi tình huống (thành công lẫn thất bại)
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    logger.d("SnapshotFetcher", "Thành công: nhận ${bytes?.size ?: 0} bytes | url=$url")
                    bytes
                } else if (response.code == 401) {
                    logger.e("SnapshotFetcher", "HTTP 401 Unauthorized — kiểm tra lại username/password camera | url=$url")
                    null
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