package com.aichatvn.agent.tools.camera

import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.NetworkContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kết quả 1 camera dò được — đủ thông tin để điền thẳng vào CameraConfigDraft/saveCameraConfig,
 * không cần người dùng tự gõ tay URL/path.
 */
data class DiscoveredCamera(
    val ip: String,
    val snapshotUrl: String,
    val vendorGuess: String,       // vd "Hikvision", "Generic" — chỉ để hiển thị gợi ý, KHÔNG chắc chắn 100%
    val username: String?,         // null nếu không cần auth
    val password: String?,
    val previewBytes: ByteArray?   // ảnh JPEG thật đã fetch được — dùng để show thumbnail cho người dùng xác nhận
)

/**
 * ⚠️ CHỦ Ý: Tool này CHỈ dò tìm & xác nhận URL/path/credential hợp lệ — KHÔNG tự ý lưu vào DB.
 * Nơi gọi (ViewModel/UI) phải hiển thị danh sách DiscoveredCamera cho người dùng CHỌN & XÁC NHẬN
 * trước khi gọi saveCameraConfig(). Lý do: mạng LAN nhà có thể có nhiều thiết bị khác (router, TV,
 * loa thông minh...) vô tình mở port 80/8080 — tự động lưu thẳng dễ cấu hình nhầm thiết bị.
 *
 * Toàn bộ quá trình quét chỉ dùng giao tiếp LAN thuần (TCP connect + HTTP GET nội bộ), KHÔNG
 * gọi ra internet, KHÔNG cần Groq/cloud — hoạt động được cả khi điện thoại không có dữ liệu di
 * động, miễn đã kết nối cùng WiFi với camera.
 */
@Singleton
class CameraAutoDiscoveryTool @Inject constructor(
    private val logger: Logger,
    // ✅ MỚI (Giai đoạn 3, mục 19 Bước 1): getLocalSubnetPrefix() cũ đã chuyển sang đây
    // (NetworkContext.getCurrentWifiSubnet()) để SystemAuditor dùng chung — hành vi giữ
    // nguyên 100%, chỉ đổi chỗ ở. @ApplicationContext Context không còn dùng trực tiếp ở
    // class này nữa (chỉ NetworkContext cần nó) nên đã gỡ khỏi constructor — Hilt tự cung
    // cấp NetworkContext (đã có @Singleton @Inject constructor riêng), không cần khai báo gì
    // thêm ở DeviceModule/AppModule.
    private val networkContext: NetworkContext,
) {
    companion object {
        // Timeout ngắn cho bước quét port sống — mục đích chỉ lọc nhanh IP "có vẻ có thiết bị
        // web" trước khi tốn công thử path ở bước sau. 300ms đủ cho LAN nội bộ (không phải qua
        // internet), quá cao sẽ khiến quét 254 IP mất quá lâu.
        private const val PORT_SCAN_TIMEOUT_MS = 300L

        // Timeout khi thử path snapshot thật — cần dài hơn quét port vì camera phải chụp + encode
        // JPEG rồi mới trả response, không chỉ là bắt tay TCP đơn thuần.
        private const val SNAPSHOT_TRY_TIMEOUT_MS = 3_000L

        // Số IP quét song song cùng lúc — giới hạn để tránh quá tải Wi-Fi chip của điện thoại
        // (một số máy tầm trung/thấp có thể chập chờn nếu mở quá nhiều socket cùng lúc).
        private const val MAX_CONCURRENT_SCANS = 32

        // Các cổng phổ biến camera IP thường mở HTTP snapshot/ONVIF.
        private val CANDIDATE_PORTS = listOf(80, 8080, 8000, 88)

        // ✅ Danh sách path snapshot phổ biến theo hãng — KHÔNG đầy đủ 100% (camera đời quá cũ/lạ
        // có thể vẫn phải cấu hình tay), nhưng phủ được phần lớn camera IP phổ thông trên thị
        // trường VN. Mở rộng dần danh sách này theo phản hồi thực tế từ khách hàng.
        private val SNAPSHOT_PATTERNS: List<Pair<String, String>> = listOf(
            "Hikvision" to "/ISAPI/Streaming/channels/101/picture",
            "Hikvision (alt)" to "/ISAPI/Streaming/channels/1/picture",
            "Dahua" to "/cgi-bin/snapshot.cgi?channel=1",
            "Dahua (alt)" to "/cgi-bin/snapshot.cgi",
            "Foscam" to "/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2",
            "Generic CGI 1" to "/cgi-bin/currentpic.cgi",
            "Generic CGI 2" to "/cgi-bin/snapshot.cgi",
            "Generic 1" to "/snapshot.jpg",
            "Generic 2" to "/snap.jpg",
            "Generic 3" to "/image.jpg",
            "Generic 4" to "/image/jpeg.cgi",
            "Generic 5" to "/jpg/image.jpg",
            "Generic 6" to "/onvif/snapshot",
            "Reolink" to "/cgi-bin/api.cgi?cmd=Snap&channel=0",
            "TP-Link Tapo (local)" to "/stream1",
        )

        // ✅ Cặp user/pass mặc định phổ biến nhất — CHỦ Ý giới hạn số lượng ít (không brute-force
        // sâu) vì đây là hành vi thử trên toàn mạng LAN, dù chỉ chạy trong nhà cũng nên giữ ở mức
        // hợp lý, không giống tấn công dò mật khẩu. Nếu khách đã đổi pass khỏi mặc định, các cặp
        // này sẽ fail và người dùng cần nhập tay — đây là giới hạn chấp nhận được của tính năng.
        //
        // ⚠️ SỬA (bảo mật): danh sách này CHỈ được thử sau khi 1 path đã xác nhận trả về 401 (tức
        // endpoint có thật và có yêu cầu auth) — KHÔNG thử credential trên mọi path × mọi IP sống
        // ngay từ đầu. Trước đây app sẽ tự gửi admin/admin, admin/12345... tới BẤT KỲ thiết bị nào
        // mở cổng 80/8080 trong nhà (router phụ, NAS, máy in, camera hàng xóm dùng chung Wi-Fi
        // khách...), về bản chất là credential-stuffing diện rộng dù mục đích tốt. Thu hẹp phạm vi
        // thử về đúng các endpoint đã xác nhận là camera thật giúp giảm rủi ro này.
        private val DEFAULT_CREDENTIALS: List<Pair<String, String>> = listOf(
            "admin" to "admin",
            "admin" to "12345",
            "admin" to "",
        )
    }

    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(SNAPSHOT_TRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SNAPSHOT_TRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Điểm vào chính — quét toàn bộ dải LAN hiện tại, trả về danh sách camera dò được.
     * Không throw ra ngoài; lỗi từng bước được log và bỏ qua, không làm hỏng kết quả tổng.
     *
     * @param onProgress callback tuỳ chọn để UI hiển thị tiến độ (vd "Đang quét 45/254 IP...").
     */
    suspend fun discoverCameras(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        try {
            val subnetPrefix = networkContext.getCurrentWifiSubnet()
            if (subnetPrefix == null) {
                logger.w("CameraAutoDiscoveryTool", "⚠️ Không lấy được subnet LAN — có đang kết nối Wi-Fi không?")
                return@withContext emptyList()
            }
            logger.i("CameraAutoDiscoveryTool", "🔍 Bắt đầu quét dải $subnetPrefix.1-254")

            // B1+B2: quét 254 IP song song (giới hạn concurrency), lọc ra IP có port sống.
            val aliveHosts = scanAliveHosts(subnetPrefix, onProgress)
            logger.i("CameraAutoDiscoveryTool", "🔍 Tìm thấy ${aliveHosts.size} IP có port mở: $aliveHosts")

            if (aliveHosts.isEmpty()) return@withContext emptyList()

            // B3+B4: với mỗi IP sống, thử path/credential — cũng chạy song song giữa các IP để
            // không cộng dồn thời gian tuyến tính (254 IP x nhiều path sẽ quá lâu nếu tuần tự).
            val results = coroutineScope {
                aliveHosts.map { host ->
                    async { tryDiscoverOnHost(host) }
                }.awaitAll()
            }.filterNotNull()

            logger.i("CameraAutoDiscoveryTool", "✅ Hoàn tất: tìm thấy ${results.size} camera khả dụng")
            results
        } catch (e: Exception) {
            logger.e("CameraAutoDiscoveryTool", "discoverCameras lỗi: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Quét 254 IP trong dải, với mỗi IP thử kết nối TCP tới các CANDIDATE_PORTS.
     * Chạy song song có giới hạn (MAX_CONCURRENT_SCANS) bằng cách chia thành từng đợt (chunk).
     */
    private suspend fun scanAliveHosts(
        subnetPrefix: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): List<String> = coroutineScope {
        val allIps = (1..254).map { "$subnetPrefix.$it" }
        val alive = mutableListOf<String>()
        var scanned = 0

        allIps.chunked(MAX_CONCURRENT_SCANS).forEach { chunk ->
            val chunkResults = chunk.map { ip ->
                async {
                    val isAlive = CANDIDATE_PORTS.any { port -> isPortOpen(ip, port) }
                    ip to isAlive
                }
            }.awaitAll()

            chunkResults.forEach { (ip, isAlive) -> if (isAlive) alive.add(ip) }
            scanned += chunk.size
            onProgress?.invoke(scanned, allIps.size)
        }
        alive
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PORT_SCAN_TIMEOUT_MS.toInt())
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Với 1 IP đã xác định có port mở, thử lần lượt từng path × từng credential cho đến khi tìm
     * được 1 kết quả trả về ảnh JPEG hợp lệ. Dừng ngay khi tìm thấy match đầu tiên (không cần thử
     * hết toàn bộ tổ hợp) — vì mỗi camera thực tế chỉ có đúng 1 path/credential đúng, thử tiếp chỉ
     * tốn thời gian vô ích.
     */
    private suspend fun tryDiscoverOnHost(ip: String): DiscoveredCamera? {
        for (port in CANDIDATE_PORTS) {
            for ((vendor, path) in SNAPSHOT_PATTERNS) {
                val portSuffix = if (port == 80) "" else ":$port"
                val url = "http://$ip$portSuffix$path"

                // Bước 1: thử KHÔNG auth trước — nếu path này không tồn tại trên thiết bị (404,
                // connection refused...) thì bỏ qua hẳn, không có lý do gì để thử credential.
                val probe = withTimeoutOrNull(SNAPSHOT_TRY_TIMEOUT_MS) { fetchAndValidate(url, null, null) }
                if (probe?.result != null) {
                    logger.i("CameraAutoDiscoveryTool", "✅ Tìm thấy camera tại $url (vendor đoán: $vendor, không cần auth)")
                    return DiscoveredCamera(
                        ip = ip, snapshotUrl = url, vendorGuess = vendor,
                        username = null, password = null, previewBytes = probe.result
                    )
                }

                // Bước 2: chỉ thử credential mặc định nếu endpoint đã XÁC NHẬN có tồn tại và yêu
                // cầu auth (401) — không rải credential lên các path không tồn tại (404) hay các
                // thiết bị không phải camera.
                if (probe?.requiresAuth == true) {
                    for ((user, pass) in DEFAULT_CREDENTIALS) {
                        val bytes = withTimeoutOrNull(SNAPSHOT_TRY_TIMEOUT_MS) { fetchAndValidate(url, user, pass).result }
                        if (bytes != null) {
                            logger.i("CameraAutoDiscoveryTool", "✅ Tìm thấy camera tại $url (vendor đoán: $vendor, auth mặc định)")
                            return DiscoveredCamera(
                                ip = ip, snapshotUrl = url, vendorGuess = vendor,
                                username = user, password = pass, previewBytes = bytes
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Gọi HTTP GET tới url (kèm auth nếu có) và xác thực response THỰC SỰ là ảnh JPEG hợp lệ —
     * không chỉ dựa vào HTTP 200, vì nhiều thiết bị (router quản trị, NAS...) cũng trả 200 cho
     * path lạ (thường là trang lỗi HTML) dẫn đến false positive nếu chỉ check status code.
     *
     * Trả về [ProbeResult] thay vì ByteArray? đơn thuần để phân biệt "path không tồn tại" (bỏ qua
     * hẳn) với "path tồn tại nhưng cần auth" (requiresAuth=true) — bên gọi chỉ leo thang thử
     * credential mặc định khi thực sự cần, không rải lên mọi path.
     */
    private data class ProbeResult(val result: ByteArray?, val requiresAuth: Boolean = false)

    private fun fetchAndValidate(url: String, username: String?, password: String?): ProbeResult {
        return try {
            val requestBuilder = Request.Builder().url(url).get()
            if (!username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(username, password ?: ""))
            }

            scanClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 401) return ProbeResult(null, requiresAuth = true)
                if (!response.isSuccessful) return ProbeResult(null)

                val contentType = response.header("Content-Type") ?: ""
                val bytes = response.body?.bytes() ?: return ProbeResult(null)

                // Chấp nhận nếu Content-Type khai báo đúng là ảnh, HOẶC nếu server không khai báo
                // rõ nhưng magic bytes đúng chuẩn JPEG (0xFF 0xD8) — một số camera rẻ tiền trả
                // Content-Type sai/thiếu dù dữ liệu vẫn là JPEG hợp lệ.
                val looksLikeJpeg = bytes.size > 100 &&
                    bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

                if (contentType.contains("image", ignoreCase = true) || looksLikeJpeg) {
                    ProbeResult(bytes)
                } else {
                    ProbeResult(null)
                }
            }
        } catch (e: Exception) {
            ProbeResult(null)
        }
    }
}
