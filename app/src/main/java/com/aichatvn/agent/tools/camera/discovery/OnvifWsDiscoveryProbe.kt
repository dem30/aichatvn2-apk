package com.aichatvn.agent.tools.camera.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probe ONVIF WS-Discovery — gửi 1 gói UDP multicast "Probe" tới 239.255.255.250:3702 (địa chỉ +
 * cổng chuẩn ONVIF/WS-Discovery), camera nào hỗ trợ ONVIF trên cùng LAN sẽ tự trả lời "ProbeMatch"
 * kèm địa chỉ IP + XAddr (device service URL) của nó.
 *
 * Ưu điểm so với HttpSnapshotProbe: KHÔNG cần quét lần lượt 254 IP × nhiều port/path — camera tự
 * "giơ tay" trả lời, nên nhanh hơn nhiều và tìm được cả camera không có HTTP snapshot lộ ra ngoài
 * (ảnh vẫn phải lấy qua RTSP/ONVIF media service, không phải việc của probe này).
 *
 * ⚠️ GIỚI HẠN ĐÃ BIẾT (ghi lại để không ai tưởng đây là bug lạ):
 * - Chỉ bắt ProbeMatch trả lời TRONG cửa sổ chờ (WAIT_WINDOW_MS) — ONVIF không có khái niệm
 *   "danh sách camera cố định", chỉ có "ai trả lời trong lúc tôi lắng nghe". Camera phản hồi chậm
 *   (mạng yếu, đang bận) có thể bị bỏ lỡ ở 1 lượt quét — người dùng bấm "Quét lại" là cách hợp lệ.
 * - verifiedSnapshotUrl luôn null — ProbeMatch KHÔNG trả về ảnh, chỉ trả IP + XAddr (lưu vào
 *   deviceId). UI phải tự xử lý
 *   hiển thị "Cần xác nhận thêm" cho protocol=="onvif" thay vì mong đợi luôn có ảnh preview.
 * - CHƯA xử lý WS-Security UsernameToken — một số camera yêu cầu Probe phải kèm digest auth mới
 *   trả lời; camera loại này sẽ không xuất hiện qua probe này (tương tự giới hạn đã ghi trong
 *   CameraCapabilityProber.tryProbeOnvif). Không phải lỗi, là giới hạn của bước đầu.
 * - Cần WifiManager.MulticastLock để nhận được gói UDP multicast — THIẾU nó là lỗi CÙNG LOẠI với
 *   lỗi CHANGE_WIFI_MULTICAST_STATE đã từng gặp ở Tuya LAN discovery (SecurityException khi
 *   acquire, hoặc gói bị Android lọc âm thầm nếu quên acquire). Đã tự acquire/release trong hàm
 *   này — nếu app CHƯA khai báo permission CHANGE_WIFI_MULTICAST_STATE trong AndroidManifest.xml
 *   (nên đã có sẵn từ lần sửa Tuya trước), probe này sẽ luôn trả về rỗng mà không throw lỗi rõ
 *   ràng, vì acquire() ném SecurityException bị bắt và log lại thay vì crash discovery.
 */
@Singleton
class OnvifWsDiscoveryProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : CameraProbe {

    override val name: String = "ONVIF WS-Discovery"

    companion object {
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val MULTICAST_PORT = 3702
        // Cửa sổ chờ ProbeMatch — ONVIF không có "kết thúc" tường minh, camera có thể trả lời bất
        // cứ lúc nào sau khi nhận Probe. 4s đủ cho LAN nhà, dài hơn thì trải nghiệm quét chậm đi.
        private const val WAIT_WINDOW_MS = 4_000
        private const val SOCKET_TIMEOUT_MS = 500

        private val PROBE_MESSAGE_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                        xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
              <e:Header>
                <w:MessageID>uuid:%s</w:MessageID>
                <w:To e:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                <w:Action e:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
              </e:Header>
              <e:Body>
                <d:Probe>
                  <d:Types>dn:NetworkVideoTransmitter</d:Types>
                </d:Probe>
              </e:Body>
            </e:Envelope>
        """.trimIndent()

    }

    override suspend fun discover(
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        onProgress?.invoke(0, 1)

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("aichatvn2-onvif-discovery")

        try {
            multicastLock?.setReferenceCounted(true)
            try {
                multicastLock?.acquire()
            } catch (e: SecurityException) {
                // Xem ghi chú ở đầu file — thiếu CHANGE_WIFI_MULTICAST_STATE thì tới đây, không
                // phải bug của logic probe, là thiếu khai báo permission trong Manifest.
                logger.w(
                    "OnvifWsDiscoveryProbe",
                    "⚠️ Không acquire được MulticastLock (thiếu quyền CHANGE_WIFI_MULTICAST_STATE?): ${e.message}"
                )
                return@withContext emptyList()
            }

            val results = sendProbeAndCollect()
            onProgress?.invoke(1, 1)
            logger.i("OnvifWsDiscoveryProbe", "✅ Hoàn tất: tìm thấy ${results.size} camera qua ONVIF")
            results
        } catch (e: Exception) {
            logger.e("OnvifWsDiscoveryProbe", "discover lỗi: ${e.message}", e)
            emptyList()
        } finally {
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (e: Exception) {
                logger.w("OnvifWsDiscoveryProbe", "Lỗi release MulticastLock: ${e.message}")
            }
        }
    }

    private fun sendProbeAndCollect(): List<DiscoveredCamera> {
        val found = LinkedHashMap<String, DiscoveredCamera>() // key = IP, khử trùng nếu 1 camera trả lời nhiều lần

        MulticastSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.timeToLive = 8 // đủ vượt vài switch/router trong LAN nhà, không cần lớn hơn

            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            val messageId = UUID.randomUUID().toString()
            val probeXml = PROBE_MESSAGE_TEMPLATE.format(messageId)
            val probeBytes = probeXml.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                probeBytes, probeBytes.size, InetSocketAddress(group, MULTICAST_PORT)
            )

            try {
                socket.send(packet)
                logger.d("OnvifWsDiscoveryProbe", "📡 Đã gửi WS-Discovery Probe, chờ ProbeMatch...")
            } catch (e: Exception) {
                logger.w("OnvifWsDiscoveryProbe", "Gửi Probe thất bại: ${e.message}")
                return emptyList()
            }

            val deadline = System.currentTimeMillis() + WAIT_WINDOW_MS
            val buffer = ByteArray(8192)

            while (System.currentTimeMillis() < deadline) {
                val recvPacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(recvPacket)
                } catch (e: java.net.SocketTimeoutException) {
                    continue // bình thường — chỉ là chưa có gói nào tới trong SOCKET_TIMEOUT_MS này, thử lại
                } catch (e: Exception) {
                    logger.d("OnvifWsDiscoveryProbe", "Lỗi nhận ProbeMatch: ${e.message}")
                    continue
                }

                val responseXml = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                val senderIp = recvPacket.address?.hostAddress ?: continue

                if (found.containsKey(senderIp)) continue // đã ghi nhận IP này rồi, bỏ qua trả lời trùng

                val xAddr = extractXAddr(responseXml)
                if (xAddr == null) {
                    logger.d("OnvifWsDiscoveryProbe", "Gói từ $senderIp không phải ProbeMatch hợp lệ, bỏ qua")
                    continue
                }

                logger.i("OnvifWsDiscoveryProbe", "✅ ProbeMatch từ $senderIp — XAddr=$xAddr")
                // Trích port từ XAddr nếu có (vd "http://192.168.0.50:8899/onvif/device_service")
                // — nhiều camera TQ giá rẻ dùng port ONVIF riêng khác 80. Mặc định 80 nếu không
                // parse được port tường minh trong XAddr (URI không có port nghĩa là port mặc định
                // của scheme, tức 80 cho http).
                val onvifPort = runCatching { java.net.URI(xAddr).port }.getOrNull()
                    ?.takeIf { it > 0 } ?: 80

                found[senderIp] = DiscoveredCamera(
                    ip = senderIp,
                    port = onvifPort,
                    protocol = "onvif",
                    deviceId = xAddr, // XAddr đóng vai trò định danh — chưa xác nhận credential/snapshot
                    manufacturer = "ONVIF"
                    // credentialsRequired/credentials/verifiedSnapshotUrl: để mặc định (false/null/null)
                    // — ProbeMatch KHÔNG xác nhận được gì về auth hay ảnh thật, xem CameraCapabilityProber
                    // cho bước xác minh tiếp theo (gọi từ CameraDiscoveryDialog khi người dùng bấm
                    // "Tự dò giao thức").
                )
            }
        }

        return found.values.toList()
    }

    /**
     * Trích XAddrs đầu tiên từ ProbeMatch response — regex đơn giản, cùng tinh thần với
     * CameraCapabilityProber.tryProbeOnvif() (không kéo thêm XML parser dependency chỉ để đọc
     * 1-2 giá trị). ProbeMatch có thể trả nhiều XAddr cách nhau bởi khoảng trắng — chỉ lấy cái đầu.
     */
    private fun extractXAddr(responseXml: String): String? {
        val match = Regex("<[a-zA-Z]*:?XAddrs>(.*?)</[a-zA-Z]*:?XAddrs>", RegexOption.DOT_MATCHES_ALL)
            .find(responseXml) ?: return null
        return match.groupValues[1].trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
