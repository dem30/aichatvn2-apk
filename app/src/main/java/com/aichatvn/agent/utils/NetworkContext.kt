package com.aichatvn.agent.utils

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetworkContext
 *
 * (Giai đoạn 3 — mục 19 Bước 1) Tách logic đọc subnet Wi-Fi hiện tại của điện thoại ra khỏi
 * CameraAutoDiscoveryTool.getLocalSubnetPrefix() (giữ nguyên hành vi 100%, chỉ đổi chỗ ở) —
 * giờ dùng chung cho 2 mục đích khác nhau:
 *   1. CameraAutoDiscoveryTool: quét toàn dải subnet để TÌM camera mới (đã sửa để gọi qua đây).
 *   2. SystemAuditor (mới): SO SÁNH subnet hiện tại với subnet đã lưu lúc probe/dò LAN thành
 *      công lần trước, để biết điện thoại có đang ở LAN nhà hay không → HealthStatus.DEGRADED.
 *
 * Không cần quyền mới — WifiManager.connectionInfo đã dùng sẵn ở CameraAutoDiscoveryTool và
 * TuyaLocalController từ trước; không liên quan CHANGE_WIFI_MULTICAST_STATE (quyền đó chỉ cần
 * cho UDP broadcast riêng của Tuya, không liên quan đọc IP đơn thuần ở đây).
 */
@Singleton
class NetworkContext @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /**
     * Dải IP LAN hiện tại (vd "192.168.1") dựa vào IP của chính điện thoại trên Wi-Fi.
     * Giả định subnet /24 (255.255.255.0) — đúng với tuyệt đại đa số modem/router gia đình,
     * y hệt giả định cũ của CameraAutoDiscoveryTool. Trả về null nếu không kết nối Wi-Fi
     * (kể cả đang dùng data di động) hoặc đọc lỗi.
     */
    fun getCurrentWifiSubnet(): String? {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null // chưa kết nối Wi-Fi

            // ipAddress trả về little-endian trên Android — tách từng byte đúng thứ tự hiển thị.
            val ip = String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
            ip.substringBeforeLast(".")
        } catch (e: Exception) {
            logger.e("NetworkContext", "getCurrentWifiSubnet lỗi: ${e.message}", e)
            null
        }
    }

    /**
     * So subnet HIỆN TẠI của điện thoại với subnet suy ra từ 1 IP đầy đủ đã lưu trước đó
     * (vd TuyaDeviceEntity.lastKnownIp, hoặc host tách từ CameraConfigEntity.snapshoturl).
     *
     * @param savedIp IP đầy đủ đã lưu lúc probe/dò LAN thành công lần trước (vd
     *                "192.168.1.45"). null/rỗng → trả về false (chưa từng dò được thì không
     *                có cơ sở để nói "đang ở nhà").
     * @return true nếu 3 octet đầu khớp với subnet hiện tại (coi là "đang ở cùng LAN nhà").
     *
     * ⚠️ Giới hạn đã biết (chấp nhận được cho mục đích hiển thị audit, không cần chính xác
     * 100%): chỉ đúng nếu router nhà không đổi dải IP giữa các lần — đa số router gia đình giữ
     * cố định trừ khi factory reset. Cũng không phân biệt được "đang ở 1 mạng LAN khác trùng
     * ngẫu nhiên dải 192.168.1.x" với "đang ở đúng nhà" — false positive hiếm nhưng có thể.
     */
    fun isOnSavedSubnet(savedIp: String?): Boolean {
        if (savedIp.isNullOrBlank()) return false
        val savedSubnet = savedIp.substringBeforeLast(".", missingDelimiterValue = "")
        if (savedSubnet.isBlank()) return false
        val currentSubnet = getCurrentWifiSubnet() ?: return false
        return currentSubnet == savedSubnet
    }
}
