package com.aichatvn.agent.tools.camera

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kết quả probe capability của 1 camera đã biết IP (khác CameraAutoDiscoveryTool — tool đó dò
 * TÌM camera mới trên cả dải LAN; tool này CHỈ kiểm tra 1 camera đã có sẵn hỗ trợ gì thêm ngoài
 * snapshot HTTP đã xác nhận từ trước).
 *
 * ⚠️ CHỦ Ý: supported=true chỉ có nghĩa "phát hiện có vẻ hỗ trợ" — KHÔNG tự bật tính năng.
 * Nơi gọi (CameraDetailViewModel) chỉ lưu kết quả này vào onvifSupported/rtspSupported;
 * onvifEnabled/rtspEnabled vẫn do người dùng tự bật qua Switch riêng.
 */
data class CapabilityProbeResult(
    val onvifSupported: Boolean,
    val onvifEventUrl: String? = null,
    val rtspSupported: Boolean,
    val rtspUrl: String? = null,
    val probedAt: Long = System.currentTimeMillis()
)

@Singleton
class CameraCapabilityProber @Inject constructor(
    private val logger: Logger,
) {
    companion object {
        // Timeout ngắn — giống triết lý PORT_SCAN_TIMEOUT_MS bên CameraAutoDiscoveryTool: đây là
        // xác nhận "có hỗ trợ không", không phải fetch dữ liệu thật, nên không cần chờ lâu.
        private const val ONVIF_PROBE_TIMEOUT_MS = 3_000L
        private const val RTSP_PROBE_TIMEOUT_MS = 2_500L
        private const val RTSP_DEFAULT_PORT = 554

        // ✅ Danh sách path RTSP phổ biến theo hãng — cùng tinh thần với SNAPSHOT_PATTERNS bên
        // discovery tool nhưng khác path vì RTSP stream path ≠ HTTP snapshot path của cùng hãng.
        private val RTSP_PATHS = listOf(
            "/Streaming/Channels/101",              // Hikvision
            "/cam/realmonitor?channel=1&subtype=0",  // Dahua
            "/stream1",                              // TP-Link Tapo / generic
            "/live",                                 // generic
            "/onvif1",                               // generic ONVIF profile
        )
    }

    private val onvifClient = OkHttpClient.Builder()
        .connectTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun probe(
        ip: String,
        httpPort: Int = 80,
        username: String?,
        password: String?
    ): CapabilityProbeResult = withContext(Dispatchers.IO) {
        val onvifUrl = runCatching { tryProbeOnvif(ip, httpPort) }.getOrNull()
        val rtspUrl = runCatching { tryProbeRtsp(ip, username, password) }.getOrNull()

        logger.i(
            "CameraCapabilityProber",
            "Probe $ip xong — ONVIF=${onvifUrl != null}, RTSP=${rtspUrl != null}"
        )

        CapabilityProbeResult(
            onvifSupported = onvifUrl != null,
            onvifEventUrl = onvifUrl,
            rtspSupported = rtspUrl != null,
            rtspUrl = rtspUrl
        )
    }

    /**
     * Bước 1 mới chỉ xác nhận cổng ONVIF device_service tồn tại và trả lời đúng SOAP —
     * CHƯA xử lý WS-Security UsernameToken digest (nhiều camera đòi auth ngay từ GetCapabilities).
     * Camera cần digest auth sẽ bị báo "không hỗ trợ" ở bước này dù thực ra có — đây là giới hạn
     * đã biết, cần bổ sung sau nếu gặp nhiều case thực tế như vậy.
     */
    private fun tryProbeOnvif(ip: String, port: Int): String? {
        val deviceServiceUrl = "http://$ip:$port/onvif/device_service"
        val soapBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              <s:Body>
                <GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl">
                  <Category>All</Category>
                </GetCapabilities>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        return try {
            val request = Request.Builder()
                .url(deviceServiceUrl)
                .post(soapBody.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
                .build()

            onvifClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                // SOAP Fault vẫn coi là "có ONVIF" — server đã hiểu & phản hồi đúng giao thức,
                // chỉ là từ chối vì auth/tham số, không phải "cổng này không phải ONVIF".
                if (!response.isSuccessful && !body.contains("soap", ignoreCase = true)) return null

                // Trích XAddr của Events service — regex đơn giản, đủ cho probe, không kéo thêm
                // XML parser dependency chỉ để đọc 1 giá trị.
                Regex("<tt:Events>.*?<tt:XAddr>(.*?)</tt:XAddr>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1)
                    ?: deviceServiceUrl // hỗ trợ ONVIF nhưng không tách được Events service riêng
            }
        } catch (e: Exception) {
            logger.d("CameraCapabilityProber", "tryProbeOnvif($ip) không có ONVIF: ${e.message}")
            null
        }
    }

    /**
     * DESCRIBE thô qua raw socket — KHÔNG dùng ExoPlayer/ MediaExtractor để tránh chi phí decode
     * lúc probe. Chỉ cần xác nhận server trả lời đúng giao thức RTSP.
     *
     * ⚠️ SỬA: URL trả về PHẢI nhúng sẵn credential (rtsp://user:pass@ip:port/path) nếu camera cần
     * auth — RtspMediaSource (ExoPlayer) lẫn ffmpeg-kit (CameraRecorder) đều KHÔNG có tham số
     * username/password riêng cho RTSP, chỉ đọc được credential từ chính URI khi kết nối thật
     * (khác lúc probe ở đây, probe tự thêm header Authorization thủ công qua socket). Nếu trả về
     * URL trần (không có user:pass@) trong khi camera cần auth, open_live_view/record ở
     * CameraSkill sẽ luôn thất bại dù probe từng báo "hỗ trợ".
     */
    private fun tryProbeRtsp(ip: String, username: String?, password: String?): String? {
        for (path in RTSP_PATHS) {
            val plainUrl = "rtsp://$ip:$RTSP_DEFAULT_PORT$path"
            if (rtspDescribeOk(plainUrl, username, password)) {
                return buildRtspUrlWithCredentials(ip, path, username, password)
            }
        }
        return null
    }

    // Percent-encode qua android.net.Uri.encode() — an toàn cho user/pass chứa ký tự đặc biệt
    // (@, :, /, khoảng trắng...) vốn khá phổ biến với mật khẩu camera đặt tuỳ ý.
    private fun buildRtspUrlWithCredentials(ip: String, path: String, username: String?, password: String?): String {
        if (username.isNullOrBlank()) return "rtsp://$ip:$RTSP_DEFAULT_PORT$path"
        val encodedUser = android.net.Uri.encode(username)
        val encodedPass = android.net.Uri.encode(password ?: "")
        return "rtsp://$encodedUser:$encodedPass@$ip:$RTSP_DEFAULT_PORT$path"
    }

    private fun rtspDescribeOk(url: String, username: String?, password: String?): Boolean {
        return try {
            val uri = URI(url)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, RTSP_DEFAULT_PORT), RTSP_PROBE_TIMEOUT_MS.toInt())
                socket.soTimeout = RTSP_PROBE_TIMEOUT_MS.toInt()

                val request = buildString {
                    append("DESCRIBE $url RTSP/1.0\r\n")
                    append("CSeq: 1\r\n")
                    if (!username.isNullOrBlank()) {
                        val basic = android.util.Base64.encodeToString(
                            "$username:${password ?: ""}".toByteArray(), android.util.Base64.NO_WRAP
                        )
                        append("Authorization: Basic $basic\r\n")
                    }
                    append("Accept: application/sdp\r\n\r\n")
                }
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()

                val statusLine = socket.getInputStream().bufferedReader().readLine() ?: return false
                // 401 vẫn coi là "có RTSP" — chỉ sai credential, camera vẫn xác nhận hỗ trợ giao thức.
                statusLine.contains("RTSP/1.0 200") || statusLine.contains("RTSP/1.0 401")
            }
        } catch (e: Exception) {
            false
        }
    }
}