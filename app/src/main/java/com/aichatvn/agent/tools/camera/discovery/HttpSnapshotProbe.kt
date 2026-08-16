package com.aichatvn.agent.tools.camera.discovery

import com.aichatvn.agent.tools.camera.DiscoveredCamera
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
 * Probe "quét toàn dải LAN qua HTTP snapshot path" — ĐÂY LÀ TOÀN BỘ LOGIC CŨ của
 * CameraAutoDiscoveryTool.discoverCameras(), chuyển nguyên vẹn vào đây làm 1 probe trong hệ thống
 * multi-protocol mới. Hành vi giữ nguyên 100%, không đổi timeout/port/path/credential nào.
 *
 * ⚠️ CameraAutoDiscoveryTool.kt (file cũ) vẫn còn tồn tại để không phá code đang gọi nó trực tiếp
 * (nếu có) trong lúc chuyển tiếp — nhưng CameraDiscoveryEngine từ nay dùng probe này, không gọi
 * CameraAutoDiscoveryTool nữa. Cân nhắc xoá CameraAutoDiscoveryTool.kt khi đã xác nhận không còn
 * chỗ nào khác import nó.
 */
@Singleton
class HttpSnapshotProbe @Inject constructor(
    private val logger: Logger,
    private val networkContext: NetworkContext,
) : CameraProbe {

    override val name: String = "HTTP Snapshot Scan"

    companion object {
        private const val PORT_SCAN_TIMEOUT_MS = 300L
        private const val SNAPSHOT_TRY_TIMEOUT_MS = 3_000L
        private const val MAX_CONCURRENT_SCANS = 32

        private val CANDIDATE_PORTS = listOf(80, 8080, 8000, 88)

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

    override suspend fun discover(
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        try {
            val subnetPrefix = networkContext.getCurrentWifiSubnet()
            if (subnetPrefix == null) {
                logger.w("HttpSnapshotProbe", "⚠️ Không lấy được subnet LAN — có đang kết nối Wi-Fi không?")
                return@withContext emptyList()
            }
            logger.i("HttpSnapshotProbe", "🔍 Bắt đầu quét dải $subnetPrefix.1-254")

            val aliveHosts = scanAliveHosts(subnetPrefix, onProgress)
            logger.i("HttpSnapshotProbe", "🔍 Tìm thấy ${aliveHosts.size} IP có port mở: $aliveHosts")

            if (aliveHosts.isEmpty()) return@withContext emptyList()

            val results = coroutineScope {
                aliveHosts.map { host ->
                    async { tryDiscoverOnHost(host) }
                }.awaitAll()
            }.filterNotNull()

            logger.i("HttpSnapshotProbe", "✅ Hoàn tất: tìm thấy ${results.size} camera khả dụng")
            results
        } catch (e: Exception) {
            logger.e("HttpSnapshotProbe", "discover lỗi: ${e.message}", e)
            emptyList()
        }
    }

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

    private suspend fun tryDiscoverOnHost(ip: String): DiscoveredCamera? {
        for (port in CANDIDATE_PORTS) {
            for ((vendor, path) in SNAPSHOT_PATTERNS) {
                val portSuffix = if (port == 80) "" else ":$port"
                val url = "http://$ip$portSuffix$path"

                val probe = withTimeoutOrNull(SNAPSHOT_TRY_TIMEOUT_MS) { fetchAndValidate(url, null, null) }
                if (probe?.result != null) {
                    logger.i("HttpSnapshotProbe", "✅ Tìm thấy camera tại $url (vendor đoán: $vendor, không cần auth)")
                    return DiscoveredCamera(
                        ip = ip, snapshotUrl = url, vendorGuess = vendor,
                        username = null, password = null, previewBytes = probe.result,
                        protocol = "http_snapshot"
                    )
                }

                if (probe?.requiresAuth == true) {
                    for ((user, pass) in DEFAULT_CREDENTIALS) {
                        val bytes = withTimeoutOrNull(SNAPSHOT_TRY_TIMEOUT_MS) { fetchAndValidate(url, user, pass).result }
                        if (bytes != null) {
                            logger.i("HttpSnapshotProbe", "✅ Tìm thấy camera tại $url (vendor đoán: $vendor, auth mặc định)")
                            return DiscoveredCamera(
                                ip = ip, snapshotUrl = url, vendorGuess = vendor,
                                username = user, password = pass, previewBytes = bytes,
                                protocol = "http_snapshot"
                            )
                        }
                    }
                }
            }
        }
        return null
    }

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
