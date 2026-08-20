package com.aichatvn.agent.devices.mqtt

import android.content.Context
import android.net.wifi.WifiManager
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.data.model.MqttDeviceEntity
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.NetworkContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
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
    // ✅ MỚI (auto-adopt thiết bị Tasmota mới mua): 2 dependency thêm để tự động "nhận nuôi"
    // Tasmota lạ tìm thấy trên LAN — dùng ĐÚNG hàm pushMqttHostToTasmota() đã có sẵn trong
    // TasmotaMqttHostMigrator (trước đây chỉ gọi khi Election đổi broker, giờ dùng thêm ở đây
    // cho ca "thiết bị chưa từng biết"), và electionManager để biết broker HIỆN TẠI của app là
    // gì (không hard-code, không tự suy đoán — lấy đúng nguồn app đang thực sự dùng).
    private val hostMigrator: TasmotaMqttHostMigrator,
    private val electionManager: MqttBrokerElectionManager,
) {
    companion object {
        private const val TAG = "TasmotaLanDiscovery"
        private const val PER_HOST_TIMEOUT_MS = 1_500L
        private const val MAX_PARALLEL = 32

        // Sau khi push MqttHost mới, Tasmota cần vài giây để áp dụng + kết nối lại MQTT trước
        // khi app có thể subscribe/nhận trạng thái từ nó. Không quá ngắn (thiết bị chưa kịp
        // reconnect) cũng không quá dài (người dùng đang chờ UI phản hồi).
        private const val ADOPT_SETTLE_DELAY_MS = 3_000L
    }

    data class DiscoveredTasmota(
        val ip: String,
        val mac: String?,
        val hostname: String?,
        val friendlyName: String?
    )

    /** Kết quả 1 lần auto-adopt — xem docstring discoverAdoptableDevices(). */
    data class AdoptableTasmotaDevice(
        val ip: String,
        val mac: String?,
        val suggestedName: String,
        val commandTopic: String,
        val stateTopic: String,
        val onPayload: String,
        val offPayload: String,
    )

    data class DiscoverAdoptableResult(
        val adoptable: List<AdoptableTasmotaDevice>, // đã trỏ broker THÀNH CÔNG, sẵn sàng cho UI xác nhận rồi mới lưu
        val failedIps: List<String>,                 // tìm thấy nhưng push MqttHost thất bại (mất mạng giữa chừng, ACL...)
        val skippedNoBroker: Boolean                 // app CHÍNH NÓ chưa có broker nào để trỏ vào (Cloud chưa cấu hình, chưa Election)
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

    /**
     * discoverAdoptableDevices ("Quét là thấy, xác nhận mới lưu" — ĐÚNG nguyên tắc đã áp dụng
     * cho HA Discovery/Homie ở MqttDiscoveryEngine.kt: "sau khi discovery được thiết bị, UI
     * phải cho người dùng xem và xác nhận trước khi lưu". Bản trước (autoAdoptUnknownDevices)
     * tự insertDevice() ngay là SAI so với chuẩn này — hàm này thay thế, KHÔNG tự lưu DB.
     *
     * Gọi SAU scanAndUpdate() (hoặc tự nó gọi lại quét subnet — mặc định found rỗng) để xử lý
     * phần trước đây bị BỎ QUA ÂM THẦM: những Tasmota tìm thấy trên LAN nhưng KHÔNG match thiết
     * bị nào đã lưu — tức là thiết bị hoàn toàn mới, đang trỏ MQTT tới broker mặc định của
     * hãng (không phải broker app này dùng).
     *
     * Với mỗi thiết bị lạ:
     * 1. Lấy broker HIỆN TẠI app đang dùng thật (qua electionManager — cùng nguồn
     *    DelegatingMqttBrokerProvider dùng để connect, không đoán riêng ở đây).
     * 2. Gọi hostMigrator.pushMqttHostToTasmota() — TÁI DÙNG nguyên hàm đã có, không viết lại
     *    logic HTTP command. Đây là bước BẮT BUỘC phải làm trước (không thể "xem trước" trạng
     *    thái MQTT của 1 thiết bị chưa trỏ đúng broker) — nhưng chỉ dừng ở mức đổi cấu hình
     *    MqttHost trên chính thiết bị, KHÔNG ghi gì vào DB app.
     * 3. Nếu push thành công → gói thành AdoptableTasmotaDevice (tên gợi ý từ
     *    hostname/friendlyName, commandTopic/stateTopic theo ĐÚNG convention mặc định của
     *    TasmotaAdapter trong MqttDiscoveryEngine.kt — "cmnd/<topic>/POWER" và
     *    "stat/<topic>/RESULT" — để 2 nơi không lệch nhau) và trả về cho UI.
     * 4. UI hiển thị danh sách này (giống hệt màn xác nhận DiscoveredMqttDevice hiện có) —
     *    người dùng xem tên/IP, bấm "Thêm" cho từng thiết bị (hoặc "Thêm tất cả") →
     *    ViewModel mới gọi mqttDeviceDao.insertDevice() lúc đó, KHÔNG phải trong hàm này.
     *
     * skippedNoBroker = true nghĩa là bản thân app CHƯA có broker nào (chưa cấu hình Cloud, mà
     * Embedded cũng chưa bầu ra host) — không có gì để trỏ Tasmota vào, nên KHÔNG push (tránh
     * trỏ thiết bị vào broker rỗng/không tồn tại). UI nên báo người dùng cấu hình broker trước.
     */
    suspend fun discoverAdoptableDevices(
        found: List<DiscoveredTasmota> = emptyList()
    ): DiscoverAdoptableResult = withContext(Dispatchers.IO) {
        val scanResult = found.ifEmpty { scanAndUpdate() }

        val (brokerIp, brokerPort) = resolveCurrentBrokerTarget()
        if (brokerIp.isNullOrBlank()) {
            logger.w(TAG, "⚠️ discoverAdoptableDevices: app chưa có broker nào sẵn sàng — bỏ qua, không push vào thiết bị lạ")
            return@withContext DiscoverAdoptableResult(emptyList(), emptyList(), skippedNoBroker = true)
        }

        // Thiết bị lạ = có trong kết quả scan NHƯNG không match bất kỳ bản ghi nào đã lưu.
        // Dùng lại đúng tiêu chí match của scanAndUpdate() để không xử lý trùng 1 thiết bị.
        val known = mqttDeviceDao.getAllDevices()
        val unknown = scanResult.filter { device ->
            known.none { entity ->
                (!device.mac.isNullOrBlank() && !entity.deviceMac.isNullOrBlank() &&
                    device.mac.equals(entity.deviceMac, ignoreCase = true)) ||
                    (!device.hostname.isNullOrBlank() && (
                        entity.name.contains(device.hostname, ignoreCase = true) ||
                            entity.commandTopic.contains(device.hostname, ignoreCase = true) ||
                            entity.topic.contains(device.hostname, ignoreCase = true)
                        )) ||
                    (!device.friendlyName.isNullOrBlank() &&
                        entity.name.equals(device.friendlyName, ignoreCase = true))
            }
        }

        if (unknown.isEmpty()) return@withContext DiscoverAdoptableResult(emptyList(), emptyList(), skippedNoBroker = false)

        logger.i(TAG, "🆕 Phát hiện ${unknown.size} Tasmota lạ — thử trỏ về broker $brokerIp:$brokerPort để chuẩn bị cho UI xác nhận")

        val adoptable = mutableListOf<AdoptableTasmotaDevice>()
        val failed = mutableListOf<String>()

        for (device in unknown) {
            val ok = hostMigrator.pushMqttHostToTasmota(device.ip, brokerIp, brokerPort)
            if (!ok) {
                failed.add(device.ip)
                logger.w(TAG, "❌ discoverAdoptableDevices: push MqttHost thất bại cho ${device.ip} (mất mạng giữa chừng?)")
                continue
            }

            // deviceTopic Tasmota mặc định = hostname thiết bị tự đặt (vd "tasmota_1A2B3C") —
            // ĐÚNG quy ước TasmotaAdapter.statResultRegex đang dùng để nhận diện qua MQTT sau
            // này, nên phải khớp y hệt, không tự đặt tên khác.
            val deviceTopic = device.hostname?.takeIf { it.isNotBlank() } ?: device.ip.replace(".", "_")
            val suggestedName = device.friendlyName?.takeIf { it.isNotBlank() }
                ?: device.hostname?.takeIf { it.isNotBlank() }
                ?: "Tasmota $deviceTopic"

            adoptable += AdoptableTasmotaDevice(
                ip = device.ip,
                mac = device.mac,
                suggestedName = suggestedName,
                commandTopic = "cmnd/$deviceTopic/POWER",
                stateTopic = "stat/$deviceTopic/RESULT",
                onPayload = "ON",
                offPayload = "OFF",
            )
            logger.i(TAG, "✅ discoverAdoptableDevices: \"$suggestedName\" (${device.ip}) đã trỏ broker $brokerIp:$brokerPort — chờ người dùng xác nhận")
        }

        // Cho thiết bị vừa push xong thời gian áp dụng + reconnect MQTT trước khi UI cho phép
        // bấm "Thêm" — tránh insertDevice() ngay khi thiết bị còn chưa kịp publish trạng thái
        // đầu tiên (subscribe sẽ thấy UNKNOWN 1 lúc, không phải lỗi, nhưng tránh gây hoang mang).
        if (adoptable.isNotEmpty()) delay(ADOPT_SETTLE_DELAY_MS)

        DiscoverAdoptableResult(adoptable, failed, skippedNoBroker = false)
    }

    /**
     * confirmAdopt — gọi từ ViewModel SAU KHI người dùng xem danh sách discoverAdoptableDevices()
     * trả về và bấm "Thêm" (từng cái hoặc "Thêm tất cả"). Đây là nơi DUY NHẤT thực sự ghi vào
     * DB cho luồng auto-adopt — điểm khác biệt cốt lõi so với bản trước (đã insertDevice() tự
     * động ngay trong lúc quét, sai nguyên tắc "xác nhận trước khi lưu").
     *
     * KHÔNG push lại MqttHost ở đây — device đã được push xong trong discoverAdoptableDevices()
     * rồi (làm 2 lần vừa dư vừa có thể gây lỗi nếu thiết bị đang trong lúc apply lần push
     * trước). Nếu người dùng đợi quá lâu giữa lúc quét và lúc bấm "Thêm" khiến push đã hết
     * hiệu lực (vd thiết bị mất điện rồi cấp lại, quay về MqttHost mặc định), lần subscribe/
     * publish đầu tiên sẽ thất bại — người dùng cần quét lại, không phải lỗi âm thầm.
     */
    suspend fun confirmAdopt(device: AdoptableTasmotaDevice): MqttDeviceEntity = withContext(Dispatchers.IO) {
        val entity = MqttDeviceEntity(
            id = "tasmota_auto_" + UUID.randomUUID().toString().take(8),
            name = device.suggestedName,
            topic = device.commandTopic.removePrefix("cmnd/").removeSuffix("/POWER"),
            commandTopic = device.commandTopic,
            stateTopic = device.stateTopic,
            onPayload = device.onPayload,
            offPayload = device.offPayload,
            discoverySource = "auto_lan_adopt",
            brokerSource = if (electionManager.currentState.value.role ==
                MqttBrokerElectionManager.BrokerRole.USING_CLOUD
            ) "cloud" else "embedded_camera_node",
            brokerMode = brokerModeLabel(),
            deviceLanIp = device.ip,
            deviceMac = device.mac,
        )
        mqttDeviceDao.insertDevice(entity)
        logger.i(TAG, "✅ confirmAdopt: đã lưu \"${entity.name}\" (${device.ip}) vào DB theo xác nhận người dùng")
        entity
    }

    /**
     * Broker app đang THỰC SỰ dùng — lấy từ electionManager (cùng nguồn
     * DelegatingMqttBrokerProvider dùng để connect() thật), không đoán/hard-code riêng ở đây.
     * USING_CLOUD/NONE → không có IP LAN cụ thể để trỏ Tasmota vào (Cloud broker là hostname
     * DNS, không phải IP LAN Tasmota có thể luôn reach được cùng cách) — coi như "chưa có
     * broker LAN sẵn sàng", trả null để caller báo người dùng thay vì âm thầm trỏ sai.
     */
    private fun resolveCurrentBrokerTarget(): Pair<String?, Int> {
        val state = electionManager.currentState.value
        val port = electionManager.embeddedBrokerPort
        return when (state.role) {
            MqttBrokerElectionManager.BrokerRole.USING_CLOUD,
            MqttBrokerElectionManager.BrokerRole.NONE -> null to port
            else -> state.activeBrokerIp to port
        }
    }

    private fun brokerModeLabel(): String = when (electionManager.currentState.value.role) {
        MqttBrokerElectionManager.BrokerRole.HOSTING_EMBEDDED,
        MqttBrokerElectionManager.BrokerRole.FOLLOWING_CAMERA_NODE -> "camera_node_host"
        MqttBrokerElectionManager.BrokerRole.FOLLOWING_ADHOC -> "adhoc"
        else -> "camera_node_host" // USING_CLOUD/NONE không tới được nhánh này (đã return null ở resolveCurrentBrokerTarget)
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
