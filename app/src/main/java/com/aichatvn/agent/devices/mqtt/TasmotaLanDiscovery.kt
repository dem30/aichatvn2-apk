package com.aichatvn.agent.devices.mqtt

import android.content.Context
import android.net.wifi.WifiManager
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.NetworkContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TasmotaLanDiscovery
 *
 * LAN discovery riêng cho thiết bị MQTT/Tasmota (phương án b — mục 5.3 kế hoạch).
 * Tương tự TuyaLocalController.discoverIp() nhưng:
 * - Quét subnet /24 hiện tại (từ NetworkContext)
 * - HTTP GET http://<ip>/cm?cmnd=Status%200 (Tasmota Status 0)
 * - Parse StatusNET.IPAddress + StatusNET.Mac / Hostname
 * - Match với MqttDeviceEntity đã lưu (theo name/topic hoặc MAC) → updateLanInfo()
 *
 * Chạy độc lập với broker discovery — không ghi đè commandTopic/stateTopic
 * (dùng updateLanInfo tách riêng, đúng khuôn TuyaDeviceDao.updateLocalControlInfo).
 */
@Singleton
class TasmotaLanDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mqttDeviceDao: MqttDeviceDao,
    private val networkContext: NetworkContext,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "TasmotaLanDiscovery"
        private const val PER_HOST_TIMEOUT_MS = 1_500L
        private const val MAX_PARALLEL = 32
    }

    data class DiscoveredTasmota(
        val ip: String,
        val mac: String?,
        val hostname: String?,
        val friendlyName: String?
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(PER_HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PER_HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PER_HOST_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Quét subnet hiện tại, trả về danh sách Tasmota tìm được.
     * Đồng thời cập nhật deviceLanIp/deviceMac cho thiết bị MQTT đã lưu nếu match.
     */
    suspend fun scanAndUpdate(): List<DiscoveredTasmota> = withContext(Dispatchers.IO) {
        val subnet = networkContext.getCurrentWifiSubnet()
        if (subnet.isNullOrBlank()) {
            logger.w(TAG, "Không có Wi-Fi subnet — bỏ qua LAN discovery")
            return@withContext emptyList()
        }

        logger.i(TAG, "🔍 Quét Tasmota trên $subnet.0/24 ...")
        val found = scanSubnet(subnet)
        logger.i(TAG, "✅ Tìm thấy ${found.size} thiết bị Tasmota trên LAN")

        // Match với thiết bị đã lưu và cập nhật LAN info.
        val known = mqttDeviceDao.getAllDevices()
        for (device in found) {
            val match = known.find { entity ->
                // Match theo MAC nếu cả hai đều có
                (!device.mac.isNullOrBlank() && !entity.deviceMac.isNullOrBlank() &&
                    device.mac.equals(entity.deviceMac, ignoreCase = true)) ||
                    // Match theo hostname / friendly name gần đúng với name hoặc topic
                    (!device.hostname.isNullOrBlank() && (
                        entity.name.contains(device.hostname, ignoreCase = true) ||
                            entity.commandTopic.contains(device.hostname, ignoreCase = true) ||
                            entity.topic.contains(device.hostname, ignoreCase = true)
                        )) ||
                    (!device.friendlyName.isNullOrBlank() &&
                        entity.name.equals(device.friendlyName, ignoreCase = true))
            }
            if (match != null) {
                mqttDeviceDao.updateLanInfo(match.id, device.ip, device.mac)
                logger.i(TAG, "📎 Cập nhật LAN info cho \"${match.name}\": ${device.ip} mac=${device.mac}")
            }
        }
        found
    }

    private suspend fun scanSubnet(subnet: String): List<DiscoveredTasmota> = coroutineScope {
        // ⚠️ SỬA: `(1..254).map { async {...} }` khởi chạy TẤT CẢ 254 coroutine ngay lập tức
        // (async là eager) — `chunked(MAX_PARALLEL)` trước đây chỉ ảnh hưởng cách GOM kết quả,
        // không giới hạn số request đồng thời thật. Dùng Semaphore để limit thật số lượng
        // request HTTP chạy song song, tránh bão hòa máy yếu / mạng LAN.
        val semaphore = Semaphore(MAX_PARALLEL)
        val jobs = (1..254).map { lastOctet ->
            async {
                semaphore.withPermit {
                    val ip = "$subnet.$lastOctet"
                    probeTasmota(ip)
                }
            }
        }
        jobs.awaitAll().filterNotNull()
    }

    private suspend fun probeTasmota(ip: String): DiscoveredTasmota? {
        return withTimeoutOrNull(PER_HOST_TIMEOUT_MS + 200) {
            try {
                val request = Request.Builder()
                    .url("http://$ip/cm?cmnd=Status%200")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull null
                    val body = response.body?.string() ?: return@withTimeoutOrNull null
                    parseStatus0(ip, body)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Parse Tasmota Status 0 JSON.
     * Ví dụ:
     * {"Status":{"FriendlyName":["Den_PK"]}, "StatusNET":{"IPAddress":"192.168.1.50","Mac":"AA:BB:..."}}
     */
    private fun parseStatus0(ip: String, body: String): DiscoveredTasmota? {
        return try {
            val json = JSONObject(body)
            val statusNet = json.optJSONObject("StatusNET") ?: return null
            val reportedIp = statusNet.optString("IPAddress", ip).ifBlank { ip }
            val mac = statusNet.optString("Mac", "").ifBlank { null }
            val hostname = statusNet.optString("Hostname", "").ifBlank { null }
            val status = json.optJSONObject("Status")
            val friendlyArr = status?.optJSONArray("FriendlyName")
            val friendlyName = if (friendlyArr != null && friendlyArr.length() > 0) {
                friendlyArr.optString(0).ifBlank { null }
            } else null
            DiscoveredTasmota(reportedIp, mac, hostname, friendlyName)
        } catch (_: Exception) {
            null
        }
    }
}
