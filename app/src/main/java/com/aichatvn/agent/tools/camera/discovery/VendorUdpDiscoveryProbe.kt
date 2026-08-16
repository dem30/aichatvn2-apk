package com.aichatvn.agent.tools.camera.discovery

import android.content.Context
import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probe UDP broadcast riêng của V380 — camera P2P/cloud dùng giao thức riêng, KHÔNG phải ONVIF
 * WS-Discovery chuẩn, nên OnvifWsDiscoveryProbe sẽ không tìm ra được camera loại này.
 *
 * ⚠️ NGUỒN — packet request lấy NGUYÊN VẸN từ mã đã reverse-engineer, KHÔNG tự đoán:
 * https://github.com/dunderhay/CCTV-v380-pro/blob/master/scripts/discover-cam-on-network/findcam.py
 * Đã tự decode + xác nhận lại hex trước khi dùng (xem BROADCAST_PAYLOAD_HEX bên dưới) — chuỗi
 * ASCII "NVDEVSEARCH^100" + zero-padding tới đủ 128 byte, gửi broadcast UDP cổng 10008, lắng nghe
 * trả lời ở cổng 10009. Cũng tham khảo cùng project reverse-engineer với
 * https://github.com/prsyahmi/v380 (C++, dùng UtlDiscovery::Discover(), source .cpp không fetch
 * được trực tiếp lúc viết class này) và https://github.com/umar14/v380-android
 * (CameraDiscovery.java, Android port cùng giao thức).
 *
 * ⚠️ GIỚI HẠN ĐÃ BIẾT:
 * - findcam.py CHỈ demo gửi broadcast + in thô bytes nhận được ra console — KHÔNG có sẵn logic
 *   parse response thành field riêng (IP/Device ID/MAC). Việc parse ở dưới (parseResponse()) là
 *   suy luận hợp lý dựa trên format request đối xứng (ASCII prefix + phần dữ liệu) và trên việc
 *   `prsyahmi/v380 --discover` xác nhận trả về được devid/ip/mac từ cùng họ giao thức — NHƯNG
 *   offset/field cụ thể trong response CHƯA được xác nhận 1-1 với source thật. Nếu response thực
 *   tế không khớp giả định này, hàm parse trả null (bỏ qua gói, không tạo camera sai) thay vì đoán
 *   liều — cần bắt log thật (Logger.i ghi lại raw bytes) từ 1 lần chạy thật với camera thật để
 *   tinh chỉnh lại parseResponse() cho đúng.
 * - IP camera lấy từ `DatagramPacket.address` (nguồn gói UDP) — KHÔNG phải trích từ payload, vì
 *   payload response có thể không chứa IP tường minh (thiết bị tự biết IP nó gửi từ đâu qua UDP
 *   header, không cần nhúng lại trong payload). Cách này chắc chắn đúng bất kể payload thế nào.
 * - Port 10008/10009 KHÁC hoàn toàn port stream/lệnh 8800 mà prsyahmi/v380 dùng để login/lấy
 *   video — đây chỉ là bước TÌM camera, không phải bước LẤY ảnh/stream. Lấy stream thật (nếu làm
 *   sau) là việc của 1 tầng khác (CameraConnectionManager dự kiến), không phải của probe này.
 * - Giống các probe khác, một số máy Android hạn chế UDP broadcast ở chế độ tiết kiệm pin —
 *   DatagramSocket + SO_BROADCAST là đủ cho hầu hết máy, không cần MulticastLock (broadcast khác
 *   multicast, không cần lock riêng).
 */
@Singleton
class VendorUdpDiscoveryProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : CameraProbe {

    override val name: String = "V380 UDP Discovery"

    companion object {
        private const val BROADCAST_ADDRESS = "255.255.255.255"
        private const val SEND_PORT = 10008
        private const val LISTEN_PORT = 10009
        private const val WAIT_WINDOW_MS = 4_000
        private const val SOCKET_TIMEOUT_MS = 500
        // Port lệnh/stream mặc định của V380 theo README prsyahmi/v380 — dùng để KHỞI ĐIỂM cho
        // CameraCapabilityProber thử kết nối thật sau discovery, KHÔNG dùng cho chính bước
        // broadcast discovery này (port đó là SEND_PORT/LISTEN_PORT ở trên, khác hẳn).
        private const val V380_COMMAND_PORT = 8800

        // ✅ Hex NGUYÊN VẸN copy từ findcam.py (biến `data` trong nvdevsearch packet) — đã tự
        // decode lại để xác nhận: ASCII "NVDEVSEARCH^100" (15 byte) + zero-padding tới 128 byte.
        // KHÔNG chỉnh sửa/rút gọn — payload có thể cần đúng độ dài 128 byte để camera chấp nhận.
        private const val BROADCAST_PAYLOAD_HEX =
            "4e564445565345415243485e3130300000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000"

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val byteStr = clean.substring(i * 2, i * 2 + 2)
                out[i] = byteStr.toInt(16).toByte()
            }
            return out
        }
    }

    override suspend fun discover(
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        onProgress?.invoke(0, 1)

        try {
            val results = sendBroadcastAndCollect()
            onProgress?.invoke(1, 1)
            logger.i("VendorUdpDiscoveryProbe", "✅ Hoàn tất: tìm thấy ${results.size} camera qua V380 UDP")
            results
        } catch (e: Exception) {
            logger.e("VendorUdpDiscoveryProbe", "discover lỗi: ${e.message}", e)
            emptyList()
        }
    }

    private fun sendBroadcastAndCollect(): List<DiscoveredCamera> {
        val found = LinkedHashMap<String, DiscoveredCamera>() // key = IP, khử trùng nếu trả lời nhiều lần
        val payload = hexToBytes(BROADCAST_PAYLOAD_HEX)

        DatagramSocket(LISTEN_PORT).use { socket ->
            socket.broadcast = true
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val broadcastAddr = InetAddress.getByName(BROADCAST_ADDRESS)
            val sendPacket = DatagramPacket(payload, payload.size, broadcastAddr, SEND_PORT)

            try {
                socket.send(sendPacket)
                logger.d("VendorUdpDiscoveryProbe", "📡 Đã gửi NVDEVSEARCH broadcast tới :$SEND_PORT, chờ trả lời tại :$LISTEN_PORT...")
            } catch (e: Exception) {
                logger.w("VendorUdpDiscoveryProbe", "Gửi broadcast thất bại: ${e.message}")
                return emptyList()
            }

            val deadline = System.currentTimeMillis() + WAIT_WINDOW_MS
            val buffer = ByteArray(4096)

            while (System.currentTimeMillis() < deadline) {
                val recvPacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(recvPacket)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    logger.d("VendorUdpDiscoveryProbe", "Lỗi nhận response: ${e.message}")
                    continue
                }

                val senderIp = recvPacket.address?.hostAddress ?: continue
                if (found.containsKey(senderIp)) continue // đã ghi nhận IP này, bỏ qua trả lời trùng

                val rawBytes = recvPacket.data.copyOfRange(0, recvPacket.length)
                // Log RAW bytes ở mức info — CHỦ Ý, không phải debug thừa: đây là dữ liệu cần thiết
                // để verify/tinh chỉnh parseResponse() với camera thật, vì offset field response
                // chưa được xác nhận 1-1 với source (xem ghi chú giới hạn ở đầu file).
                logger.i(
                    "VendorUdpDiscoveryProbe",
                    "📥 Response từ $senderIp (${rawBytes.size} byte): ${rawBytes.joinToString("") { "%02x".format(it) }}"
                )

                val parsed = parseResponse(rawBytes)
                logger.i(
                    "VendorUdpDiscoveryProbe",
                    "✅ Camera V380 tại $senderIp" +
                        (parsed?.deviceId?.let { " — deviceId=$it" } ?: " — deviceId chưa xác định được từ response")
                )

                found[senderIp] = DiscoveredCamera(
                    ip = senderIp,
                    // ⚠️ Port lệnh/stream thật của V380 là 8800 (xác nhận qua README prsyahmi/v380)
                    // — KHÁC hoàn toàn port 10008/10009 dùng để discovery ở trên. Port này chỉ là
                    // "đoán theo tài liệu", CHƯA được CameraCapabilityProber xác minh thật.
                    port = V380_COMMAND_PORT,
                    protocol = "vendor_udp",
                    deviceId = parsed?.deviceId,
                    manufacturer = "V380"
                    // credentialsRequired/credentials/verifiedSnapshotUrl: để mặc định (false/null/null)
                    // — broadcast response KHÔNG xác nhận được gì về auth hay ảnh/stream thật, xem
                    // CameraCapabilityProber cho bước xác minh tiếp theo.
                )
            }
        }

        return found.values.toList()
    }

    private data class V380ParsedResponse(val deviceId: String?, val mac: String?)

    /**
     * ⚠️ SUY LUẬN, CHƯA XÁC NHẬN 1-1 — xem ghi chú giới hạn ở đầu file. Thử tìm chuỗi ASCII in
     * được trong response (device ID/MAC nhiều khả năng ở dạng text giống payload request, theo
     * đúng phong cách "NVDEVSEARCH^100" toàn ASCII + null-padding). Nếu không tìm được gì hợp lý,
     * trả về field null thay vì bịa — DiscoveredCamera vẫn được tạo (IP đã chắc chắn đúng từ UDP
     * header), chỉ thiếu deviceId hiển thị thêm cho người dùng xác nhận.
     */
    private fun parseResponse(bytes: ByteArray): V380ParsedResponse? {
        return try {
            val text = bytes.toString(Charsets.US_ASCII).trim { it == '\u0000' || it.isWhitespace() }
            if (text.isBlank()) return null

            // Response NVDEVSEARCH thường theo cùng format "PREFIX^field1^field2..." như request —
            // tách theo dấu '^' và lọc ký tự không in được, không giả định số lượng field cố định.
            val parts = text.split("^").map { part ->
                part.filter { c -> c.code in 32..126 }
            }.filter { it.isNotBlank() }

            V380ParsedResponse(
                deviceId = parts.getOrNull(1),
                mac = parts.getOrNull(2)
            )
        } catch (e: Exception) {
            null
        }
    }
}
