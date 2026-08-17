package com.aichatvn.agent.tools.camera

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
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
 *
 * ✅ MỚI: snapshotUrl/snapshotBytes — kết quả thử HTTP snapshot NGAY TRÊN port đã biết của camera
 * (vd port dò được từ VendorUdpDiscoveryProbe, như 8800 của V380), bổ sung cho ONVIF/RTSP vốn chỉ
 * thử port cố định (80/8899/554 mặc định). Nhiều camera vendor riêng (V380...) không hỗ trợ
 * ONVIF/RTSP nhưng vẫn lộ 1 endpoint ảnh JPEG dạng HTTP ngay trên port lệnh của chính hãng — đây
 * là nguồn verifiedSnapshotUrl thực tế duy nhất cho nhóm camera đó, xem tryProbeHttpSnapshot().
 */
data class CapabilityProbeResult(
    val onvifSupported: Boolean,
    val onvifEventUrl: String? = null,
    val rtspSupported: Boolean,
    val rtspUrl: String? = null,
    val snapshotUrl: String? = null,
    val snapshotBytes: ByteArray? = null,
    val probedAt: Long = System.currentTimeMillis()
)

@Singleton
class CameraCapabilityProber @Inject constructor(
    // ✅ MỚI: client HTTP dùng chung cho MỌI camera trên LAN (xem CameraLanHttpClient.kt) — thay
    // cho việc tự dựng 2 OkHttpClient.Builder() riêng (onvifClient/snapshotClient) như trước, mỗi
    // cái 1 ConnectionPool mặc định độc lập, dễ dính lỗi "unexpected end of stream" (và các dạng
    // IOException transport tương tự) khi probe nhiều camera OEM không tuân thủ keep-alive đúng
    // chuẩn — cùng lớp bug đã vá ở OnvifEventRelay, fix chung 1 chỗ áp dụng cho mọi nơi gọi HTTP
    // tới camera thay vì vá lại lần nữa ở đây.
    @com.aichatvn.agent.di.CameraLanHttpClient private val cameraLanHttpClient: OkHttpClient,
    private val logger: Logger,
) {
    companion object {
        // Timeout ngắn — giống triết lý PORT_SCAN_TIMEOUT_MS bên CameraAutoDiscoveryTool: đây là
        // xác nhận "có hỗ trợ không", không phải fetch dữ liệu thật, nên không cần chờ lâu.
        private const val ONVIF_PROBE_TIMEOUT_MS = 3_000L
        private const val RTSP_PROBE_TIMEOUT_MS = 2_500L
        private const val RTSP_DEFAULT_PORT = 554

        // ⚠️ CANDIDATE, không phải khẳng định — 1 số camera (nhiều nguồn nhắc tới V380, chưa xác
        // nhận chính hãng) dùng port ONVIF riêng khác hẳn port HTTP snapshot mặc định 80. Chỉ
        // dùng làm cổng thử THÊM khi cổng mặc định thất bại — tryProbeOnvif() vẫn chỉ báo có
        // ONVIF sau khi GetCapabilities SOAP thật trả lời đúng giao thức tại cổng đó, không suy
        // diễn "port 8899 mở nghĩa là có ONVIF".
        private const val ONVIF_FALLBACK_PORT = 8899

        // ✅ MỚI: knownPort (vd 8800 gán cứng cho MỌI camera V380 bởi VendorUdpDiscoveryProbe,
        // xem V380_COMMAND_PORT — CHỈ là đoán theo tài liệu, KHÔNG phải port dò được thật từ chính
        // camera đó) không đáng tin tuyệt đối — nhiều camera đời rẻ tiền/khác firmware dùng port
        // LAN khác hẳn. Danh sách port phổ biến thêm vào đây để probe không chỉ tin 1 con số đoán
        // sẵn — mỗi port vẫn phải qua đúng 1 lượt thử path thật (rtspDescribeOk/fetchSnapshot),
        // không có port nào tự động coi là "hỗ trợ" chỉ vì mở/connect được.
        // Nguồn: 80/8080/8000/88 (HTTP camera IP phổ biến, cùng danh sách HttpSnapshotProbe),
        // 8899 (ONVIF phổ biến), 8800 (V380 theo tài liệu), 34567 (Dahua/Hikvision cũ dùng riêng
        // cho SDK/private protocol, đôi khi camera Trung Quốc giá rẻ nhái theo cũng dùng port này).
        private val CANDIDATE_PORTS_FOR_KNOWN_CAMERA = listOf(8800, 80, 8080, 8000, 88, 8899, 34567)

        // Timeout riêng, ngắn hơn timeout probe path thật — dùng để LỌC NHANH port nào thật sự mở
        // trước khi tốn công thử từng path/credential, tránh quét ~7 port × nhiều path × nhiều
        // credential mất quá lâu trên 1 lần bấm "Tự dò giao thức".
        private const val PORT_OPEN_CHECK_TIMEOUT_MS = 300

        // ✅ Danh sách path RTSP phổ biến theo hãng — cùng tinh thần với SNAPSHOT_PATTERNS bên
        // discovery tool nhưng khác path vì RTSP stream path ≠ HTTP snapshot path của cùng hãng.
        private val RTSP_PATHS = listOf(
            "/Streaming/Channels/101",              // Hikvision
            "/cam/realmonitor?channel=1&subtype=0",  // Dahua
            "/stream1",                              // TP-Link Tapo / generic
            "/live",                                 // generic
            "/onvif1",                               // generic ONVIF profile
            // ⚠️ V380 — CHƯA xác nhận 1-1 với tài liệu chính hãng, chỉ tổng hợp từ nhiều nguồn
            // reverse-engineer độc lập đồng thuận cùng con số (cùng nhóm nguồn với
            // VendorUdpDiscoveryProbe.kt). Chỉ là 2 CANDIDATE thêm vào danh sách thử — như mọi
            // path khác trong list này, chỉ được coi là "hỗ trợ" sau khi rtspDescribeOk() xác
            // nhận DESCRIBE trả 200/401 thật, không giả định riêng cho V380. Một nguồn ghi rõ
            // firmware 2505/3505 trở lên mặc định TẮT RTSP — probe fail ở path này với 1 số máy
            // V380 nhiều khả năng là do firmware, không phải path sai.
            "/live/ch00_0",                          // V380 — sub-stream (candidate, chưa xác nhận chính hãng)
            "/live/ch00_1",                          // V380 — main-stream (candidate, chưa xác nhận chính hãng)
        )

        // ✅ dùng cho tryProbeHttpSnapshot(): cùng danh sách path với HttpSnapshotProbe (đồng bộ,
        // xem SNAPSHOT_PATTERNS trong HttpSnapshotProbe.kt) — thử trên từng port trong
        // CANDIDATE_PORTS_FOR_KNOWN_CAMERA (đã lọc port sống trước), vì mục tiêu là xác minh 1
        // camera cụ thể (không quét toàn dải 254 IP như HttpSnapshotProbe).
        private val HTTP_SNAPSHOT_PATHS = listOf(
            "/ISAPI/Streaming/channels/101/picture", // Hikvision
            "/cgi-bin/snapshot.cgi?channel=1",        // Dahua
            "/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2", // Foscam
            "/cgi-bin/currentpic.cgi",                 // Generic CGI
            "/snapshot.jpg",                           // Generic
            "/snap.jpg",                               // Generic
            "/image.jpg",                              // Generic
            "/onvif/snapshot",                         // Generic ONVIF
            "/cgi-bin/api.cgi?cmd=Snap&channel=0",     // Reolink
            // V380 — port lệnh 8800 theo README prsyahmi/v380 đôi khi lộ HTTP snapshot đơn giản,
            // CHƯA xác nhận chính hãng, chỉ CANDIDATE thêm vào — chỉ tính "hỗ trợ" khi thật sự
            // fetch được JPEG (xem fetchSnapshot()), không suy diễn khác.
            "/tmpfs/snap.jpg",
            "/snap.cgi",
        )

        private val DEFAULT_SNAPSHOT_CREDENTIALS: List<Pair<String, String>> = listOf(
            "admin" to "admin",
            "admin" to "12345",
            "admin" to "",
        )
    }

    // ✅ MỚI: derive từ cameraLanHttpClient qua newBuilder() — chỉ đổi timeout (ngắn hơn, đúng nhu
    // cầu "xác nhận có hỗ trợ không" chứ không phải fetch dữ liệu thật), vẫn CHIA SẺ
    // ConnectionPool/Dispatcher (đã tắt pool) với client gốc, theo đúng khuyến nghị OkHttp khi cần
    // nhiều cấu hình timeout trong cùng 1 app — không tạo client hoàn toàn mới.
    private val onvifClient = cameraLanHttpClient.newBuilder()
        .connectTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val snapshotClient = cameraLanHttpClient.newBuilder()
        .connectTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ONVIF_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * @param knownPort port đã biết/đoán được của camera (vd 8800 gán cứng cho V380 bởi
     * VendorUdpDiscoveryProbe) — dùng làm port ƯU TIÊN thử trước (khả năng đúng cao nhất, thử
     * trước để nhanh), KHÔNG còn là port DUY NHẤT — xem CANDIDATE_PORTS_FOR_KNOWN_CAMERA, mỗi port
     * ứng viên khác vẫn được thử tuần tự nếu knownPort thất bại. httpPort (mặc định 80) giữ nguyên
     * riêng cho ONVIF — hành vi cũ không đổi. null nghĩa là không có gợi ý port, vẫn thử hết danh
     * sách ứng viên chuẩn.
     */
    suspend fun probe(
        ip: String,
        httpPort: Int = 80,
        knownPort: Int? = null,
        username: String?,
        password: String?,
        // XAddr lấy trực tiếp từ WS-Discovery.
        // Khi có XAddr, ưu tiên chính endpoint này thay vì tự dựng từ IP + port.
        knownOnvifDeviceUrl: String? = null
    ): CapabilityProbeResult = withContext(Dispatchers.IO) {
        // knownPort lên đầu danh sách (ưu tiên thử trước vì khả năng đúng cao hơn), sau đó tới các
        // port ứng viên chuẩn — distinct() để không thử trùng khi knownPort đã nằm trong danh sách.
        val candidatePorts = (listOfNotNull(knownPort) + CANDIDATE_PORTS_FOR_KNOWN_CAMERA).distinct()

        val onvifUrl = runCatching {
            tryProbeOnvif(
                ip = ip,
                httpPort = httpPort,
                username = username,
                password = password,
                knownOnvifDeviceUrl = knownOnvifDeviceUrl
            )
        }.getOrNull()
        val rtspUrl = runCatching { tryProbeRtsp(ip, candidatePorts, username, password) }.getOrNull()
        // ✅ MỚI: chỉ thử HTTP snapshot khi ONVIF/RTSP đều không xác nhận được gì — đây là phương
        // án cuối để vẫn trả về được 1 verifiedSnapshotUrl cho camera dùng giao thức riêng (V380...)
        // mà 2 bước trên không phủ tới. Không thử khi đã có onvif/rtsp vì lúc đó camera coi như đã
        // "hỗ trợ" theo nghĩa chuẩn, tuỳ CameraSkill quyết định dùng nguồn nào.
        val snapshot = if (onvifUrl == null && rtspUrl == null) {
            runCatching { tryProbeHttpSnapshot(ip, candidatePorts, username, password) }.getOrNull()
        } else null

        logger.i(
            "CameraCapabilityProber",
            "Probe $ip xong (candidatePorts=$candidatePorts) — ONVIF=${onvifUrl != null}, " +
                "RTSP=${rtspUrl != null}, HTTP snapshot=${snapshot != null}"
        )

        CapabilityProbeResult(
            onvifSupported = onvifUrl != null,
            onvifEventUrl = onvifUrl,
            rtspSupported = rtspUrl != null,
            rtspUrl = rtspUrl,
            snapshotUrl = snapshot?.url,
            snapshotBytes = snapshot?.bytes
        )
    }

    /**
     * Thử cổng ONVIF theo thứ tự: cổng HTTP mặc định truyền vào (hành vi cũ, không đổi) trước,
     * rồi mới thử ONVIF_FALLBACK_PORT nếu cổng đó thất bại VÀ khác cổng vừa thử (tránh thử trùng
     * khi httpPort đã là 8899). Mỗi cổng vẫn đi qua đúng 1 hàm probe thật (tryProbeOnvifOnPort) —
     * không có nhánh nào tự gán "có ONVIF" mà không qua GetCapabilities SOAP thật.
     */
    private fun tryProbeOnvif(
        ip: String,
        httpPort: Int,
        username: String?,
        password: String?,
        knownOnvifDeviceUrl: String?
    ): String? {
        // WS-Discovery đã trả XAddr thật của camera: giữ nguyên endpoint đó.
        if (!knownOnvifDeviceUrl.isNullOrBlank()) {
            val xAddr = knownOnvifDeviceUrl.trim()
            if (runCatching { URI(xAddr).host }.getOrNull().isNullOrBlank()) {
                logger.w(
                    "CameraCapabilityProber",
                    "XAddr từ WS-Discovery không hợp lệ: '$xAddr' — fallback IP/port."
                )
            } else {
                tryProbeOnvifAtUrl(xAddr, username, password)?.let { return it }
            }
        }

        // Fallback cho caller cũ chưa truyền XAddr.
        tryProbeOnvifOnPort(ip, httpPort, username, password)?.let { return it }
        if (httpPort != ONVIF_FALLBACK_PORT) {
            tryProbeOnvifOnPort(ip, ONVIF_FALLBACK_PORT, username, password)?.let { return it }
        }
        return null
    }

    private fun tryProbeOnvifOnPort(ip: String, port: Int, username: String?, password: String?): String? =
        tryProbeOnvifAtUrl("http://$ip:$port/onvif/device_service", username, password)

    /**
     * Probe đúng endpoint ONVIF Device Service đã biết.
     * Không tự suy diễn lại XAddr nếu WS-Discovery đã cung cấp nó.
     *
     * ✅ SỬA (bug thật: GetCapabilities không gửi Authorization dù probe() nhận username/password
     * — camera yêu cầu Basic Auth trả 401/SOAP Fault NotAuthorized, onvifUrl luôn null, khiến
     * onvifEventUrl không bao giờ được lưu vào DB dù ONVIF Events thực sự tồn tại và
     * OnvifEventRelay.soapPostOnce() vẫn gửi đúng Authorization). Thêm Basic Auth giống hệt cách
     * OnvifEventRelay đã làm, để kết quả probe nhất quán với hành vi subscribe thật sau này.
     */
    private fun tryProbeOnvifAtUrl(deviceServiceUrl: String, username: String?, password: String?): String? {
        val uri = runCatching { URI(deviceServiceUrl) }.getOrNull() ?: return null
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null

        val soapAction = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"

        val soap12Body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              <s:Body>
                <GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl">
                  <Category>All</Category>
                </GetCapabilities>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val soap11Body = soap12Body.replace(
            "http://www.w3.org/2003/05/soap-envelope",
            "http://schemas.xmlsoap.org/soap/envelope/"
        )

        fun probe(body: String, contentType: String, soapActionHeader: Boolean): String? {
            return try {
                val builder = Request.Builder()
                    .url(deviceServiceUrl)
                    .post(body.toRequestBody(contentType.toMediaType()))
                    // OEM hsoap/2.8: test thực tế cho thấy Connection: close ổn định.
                    .header("Connection", "close")
                    .header("Accept", "application/soap+xml, text/xml, */*")

                if (soapActionHeader) {
                    builder.header("SOAPAction", "\"$soapAction\"")
                }
                if (!username.isNullOrBlank()) {
                    builder.header("Authorization", Credentials.basic(username, password ?: ""))
                }

                onvifClient.newCall(builder.build()).execute().use { response ->
                    val responseBody = response.body?.string() ?: return null
                    if (!response.isSuccessful &&
                        !responseBody.contains("soap", ignoreCase = true)
                    ) return null
                    responseBody
                }
            } catch (e: Exception) {
                logger.d(
                    "CameraCapabilityProber",
                    "GetCapabilities ${contentType.substringBefore(';')} $deviceServiceUrl lỗi: ${e.message}"
                )
                null
            }
        }

        val body = probe(
            soap12Body,
            "application/soap+xml; charset=utf-8",
            soapActionHeader = false
        ) ?: probe(
            soap11Body,
            "text/xml; charset=utf-8",
            soapActionHeader = true
        ) ?: return null

        val eventsBlock = Regex(
            """<(?:[A-Za-z_][\w.-]*:)?Events\b[^>]*>(.*?)</(?:[A-Za-z_][\w.-]*:)?Events\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(body)?.groupValues?.get(1)

        val eventsXAddr = eventsBlock?.let { block ->
            Regex(
                """<(?:[A-Za-z_][\w.-]*:)?XAddr\b[^>]*>\s*(.*?)\s*</(?:[A-Za-z_][\w.-]*:)?XAddr\s*>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        return when {
            eventsXAddr != null -> eventsXAddr
            eventsBlock != null -> deviceServiceUrl
            else -> null
        }
    }

    /**
     * DESCRIBE thô qua raw socket — KHÔNG dùng ExoPlayer/ MediaExtractor để tránh chi phí decode
     * lúc probe. Chỉ cần xác nhận server trả lời đúng giao thức RTSP.
     *
     * ⚠️ URL trả về PHẢI nhúng sẵn credential (rtsp://user:pass@ip:port/path) nếu camera cần auth
     * — RtspMediaSource (ExoPlayer) lẫn ffmpeg-kit (CameraRecorder) đều KHÔNG có tham số
     * username/password riêng cho RTSP, chỉ đọc được credential từ chính URI khi kết nối thật
     * (khác lúc probe ở đây, probe tự thêm header Authorization thủ công qua socket). Nếu trả về
     * URL trần (không có user:pass@) trong khi camera cần auth, open_live_view/record ở
     * CameraSkill sẽ luôn thất bại dù probe từng báo "hỗ trợ".
     *
     * ✅ SỬA: nhận danh sách port ứng viên (candidatePorts, đã gồm RTSP_DEFAULT_PORT ở nơi gọi nếu
     * cần) thay vì chỉ 1 port cố định 554 — trước đây hardcode 554 khiến camera dùng port riêng
     * (V380, hoặc bất kỳ camera Trung Quốc giá rẻ nào không theo chuẩn) không bao giờ được xác
     * minh dù thật sự có RTSP trên port đó. Lọc nhanh port có mở (isPortOpen) TRƯỚC khi thử từng
     * path — port đóng thì bỏ qua ngay, không tốn công thử hết RTSP_PATHS trên port chắc chắn
     * không kết nối được.
     */
    private fun tryProbeRtsp(ip: String, candidatePorts: List<Int>, username: String?, password: String?): String? {
        val ports = (candidatePorts + RTSP_DEFAULT_PORT).distinct()
        for (port in ports) {
            if (!isPortOpen(ip, port)) continue
            for (path in RTSP_PATHS) {
                val plainUrl = "rtsp://$ip:$port$path"
                if (rtspDescribeOk(plainUrl, port, username, password)) {
                    return buildRtspUrlWithCredentials(ip, port, path, username, password)
                }
            }
        }
        return null
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PORT_OPEN_CHECK_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Percent-encode qua android.net.Uri.encode() — an toàn cho user/pass chứa ký tự đặc biệt
    // (@, :, /, khoảng trắng...) vốn khá phổ biến với mật khẩu camera đặt tuỳ ý.
    private fun buildRtspUrlWithCredentials(ip: String, port: Int, path: String, username: String?, password: String?): String {
        if (username.isNullOrBlank()) return "rtsp://$ip:$port$path"
        val encodedUser = android.net.Uri.encode(username)
        val encodedPass = android.net.Uri.encode(password ?: "")
        return "rtsp://$encodedUser:$encodedPass@$ip:$port$path"
    }

    private fun rtspDescribeOk(url: String, port: Int, username: String?, password: String?): Boolean {
        return try {
            val uri = URI(url)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(uri.host, port), RTSP_PROBE_TIMEOUT_MS.toInt())
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

    private data class SnapshotResult(val url: String, val bytes: ByteArray)

    /**
     * ✅ SỬA: nhận danh sách port ứng viên (candidatePorts) thay vì 1 port cố định — phương án
     * cuối cho camera vendor riêng (V380...) không xác nhận được ONVIF/RTSP trên bất kỳ port nào.
     * Lọc nhanh port có mở TRƯỚC (isPortOpen, dùng lại từ tryProbeRtsp) để không tốn công thử hết
     * HTTP_SNAPSHOT_PATHS × DEFAULT_SNAPSHOT_CREDENTIALS trên port chắc chắn đóng. Cùng tiêu chí
     * "hỗ trợ" với HttpSnapshotProbe (Content-Type ảnh HOẶC magic byte FF D8 của JPEG thật) —
     * không suy diễn từ HTTP 200 chung chung, path lạ trả HTML/JSON vẫn bị loại.
     */
    private fun tryProbeHttpSnapshot(ip: String, candidatePorts: List<Int>, username: String?, password: String?): SnapshotResult? {
        for (port in candidatePorts) {
            if (!isPortOpen(ip, port)) continue
            for (path in HTTP_SNAPSHOT_PATHS) {
                val url = "http://$ip:$port$path"

                fetchSnapshot(url, username, password)?.let { return SnapshotResult(url, it) }

                // Nếu người dùng chưa nhập credential, thử thêm vài default phổ biến — cùng danh
                // sách tinh thần với HttpSnapshotProbe.DEFAULT_CREDENTIALS, để không bỏ sót camera
                // cần đăng nhập nhưng người dùng chưa biết tài khoản/mật khẩu.
                if (username.isNullOrBlank()) {
                    for ((user, pass) in DEFAULT_SNAPSHOT_CREDENTIALS) {
                        fetchSnapshot(url, user, pass)?.let { return SnapshotResult(url, it) }
                    }
                }
            }
        }
        return null
    }

    private fun fetchSnapshot(url: String, username: String?, password: String?): ByteArray? {
        return try {
            val requestBuilder = Request.Builder().url(url).get()
            if (!username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(username, password ?: ""))
            }
            snapshotClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = response.header("Content-Type") ?: ""
                val bytes = response.body?.bytes() ?: return null
                val looksLikeJpeg = bytes.size > 100 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
                if (contentType.contains("image", ignoreCase = true) || looksLikeJpeg) bytes else null
            }
        } catch (e: Exception) {
            null
        }
    }
}