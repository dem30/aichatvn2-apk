package com.aichatvn.agent.devices.mqtt

import android.content.Context
import android.net.wifi.WifiManager
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.NetworkContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MqttBrokerElectionManager
 *
 * Tầng 1 — MQTT Broker Election (xem MQTT_SYMMETRIC_BROKER_PLAN.md mục 2).
 *
 * Quyết định BrokerType hiện tại (CLOUD / EMBEDDED_CAMERA_NODE / ADHOC_LOCAL), phát hiện
 * broker chết, bầu lại, kích hoạt migrate Tasmota. KHÔNG publish/subscribe device state
 * trực tiếp — ranh giới cứng: tầng này không bao giờ gọi mqttClient.publish().
 *
 * Event-driven: electNow() chỉ chạy tại các điểm sự kiện rời rạc (app start, foreground,
 * Dashboard MQTT tab mở, publish fail thật, reconciliation ~30 phút). KHÔNG có vòng lặp
 * while(true) { delay(5000); probe() } ở bất kỳ đâu.
 */
@Singleton
class MqttBrokerElectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider,
    private val mqttDeviceDao: MqttDeviceDao,
    private val networkContext: NetworkContext,
    // ✅ MỚI: cần để reclaim Tasmota tự động khi broker đổi (mục 5, 9 kế hoạch) — trước đây
    // class này tồn tại nhưng KHÔNG được gọi ở bất kỳ đâu (hàm mồ côi). Election là nơi DUY
    // NHẤT biết "broker vừa đổi sang IP nào, mode gì" nên đây đúng là nơi phải gọi.
    private val tasmotaMigrator: TasmotaMqttHostMigrator,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "MqttBrokerElection"

        /** Cổng broker embedded mặc định — chuẩn MQTT, khớp kế hoạch cũ. */
        const val DEFAULT_EMBEDDED_BROKER_PORT = 1883

        /** Timeout probe TCP tới Camera Node / ad-hoc host (ms). */
        private const val PROBE_TIMEOUT_MS = 3_000L

        /** Timeout chờ UDP broadcast trả lời "ai đang host ad-hoc" (ms). */
        private const val ADHOC_DISCOVERY_TIMEOUT_MS = 2_000L

        /** Port UDP dùng cho ad-hoc discovery broadcast (không trùng 1883). */
        private const val ADHOC_DISCOVERY_UDP_PORT = 18830

        /** Magic prefix trong gói UDP discovery. */
        private const val ADHOC_MAGIC = "AICHATVN_MQTT_ADHOC"
    }

    enum class BrokerRole {
        NONE,
        HOSTING_EMBEDDED,
        FOLLOWING_CAMERA_NODE,
        FOLLOWING_ADHOC,
        USING_CLOUD
    }

    enum class ElectionTrigger {
        AppStart,
        AppForeground,
        DashboardOpen,
        PublishFailed,
        ConnectRequested,
        Reconciliation,
        Manual
    }

    data class BrokerElectionState(
        val role: BrokerRole = BrokerRole.NONE,
        /** IP broker đang dùng — null nếu role == USING_CLOUD hoặc NONE. */
        val activeBrokerIp: String? = null,
        /**
         * Epoch chống split-brain: mỗi lần quyết định HOSTING_EMBEDDED, máy tăng epoch
         * (local + broadcast kèm gói "tôi đang host"). Máy khác thấy 2 broker → epoch thấp
         * hơn tự nhường. Không phải Raft/Paxos — cố tình đơn giản cho vài máy trong 1 nhà.
         * Chấp nhận cửa sổ split-brain ngắn, tự hội tụ ở lần probe kế tiếp.
         */
        val epoch: Long = 0L,
        val decidedAt: Long = 0L,
        /** "camera_node_host" | "adhoc" | "cloud" — ghi vào MqttDeviceEntity.brokerMode. */
        val brokerMode: String = "cloud"
    )

    private val electMutex = Mutex()

    private val _currentState = MutableStateFlow(BrokerElectionState())
    val currentState: StateFlow<BrokerElectionState> = _currentState.asStateFlow()

    // Scope riêng cho responder UDP + migrate nền — sống độc lập với electNow() (không chặn
    // electNow() trả kết quả), huỷ theo vòng đời process (SupervisorJob, không cancel theo
    // từng lần elect).
    private val responderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var responderJob: Job? = null
    @Volatile private var responderSocket: DatagramSocket? = null

    /** Port broker embedded đang dùng (có thể override sau nếu cần). */
    val embeddedBrokerPort: Int = DEFAULT_EMBEDDED_BROKER_PORT

    /**
     * Hàm suspend duy nhất thực sự làm việc. Được gọi TẠI CÁC ĐIỂM SỰ KIỆN, không có
     * vòng lặp nội bộ nào tự gọi lại chính nó theo chu kỳ ngắn.
     */
    suspend fun electNow(trigger: ElectionTrigger): BrokerElectionState = electMutex.withLock {
        logger.i(TAG, "🗳️ electNow(trigger=$trigger) bắt đầu...")
        val previous = _currentState.value
        val result = withContext(Dispatchers.IO) {
            runElection(trigger)
        }
        _currentState.value = result
        logger.i(
            TAG,
            "✅ electNow xong: role=${result.role}, ip=${result.activeBrokerIp}, " +
                "epoch=${result.epoch}, mode=${result.brokerMode}"
        )

        // ✅ SỬA (thiếu wiring): bật/tắt listener trả lời PROBE port 18830 theo role hiện tại
        // — xem responder ở dưới. Trước đây KHÔNG có ai lắng nghe (bind) tại port này, nên
        // discoverAdhocHost() của MỌI Client luôn timeout → luôn tự host ad-hoc (split-brain
        // thường xuyên thay vì hiếm gặp).
        if (result.role == BrokerRole.HOSTING_EMBEDDED) {
            ensureAdhocResponderRunning()
        } else {
            stopAdhocResponder()
        }

        // ✅ SỬA (thiếu wiring): đồng bộ brokerMode cho MỌI thiết bị MQTT đã biết — trước đây
        // syncBrokerModeToDevices() tồn tại nhưng không nơi nào gọi (hàm mồ côi).
        syncBrokerModeToDevices(result.brokerMode)

        // ✅ SỬA (thiếu wiring, mục 5 + 9 kế hoạch): nếu broker vừa đổi sang 1 IP khác — bao
        // gồm đúng trường hợp "Camera Node quay lại → reclaim" (role trước đó FOLLOWING_ADHOC,
        // giờ FOLLOWING_CAMERA_NODE) — kích hoạt migrate Tasmota. Trước đây
        // TasmotaMqttHostMigrator tồn tại nhưng không được gọi ở bất kỳ đâu trong 4 patch
        // event-trigger, nên Acceptance Test #5/#6 không chạy dù code tồn tại.
        if (result.activeBrokerIp != null &&
            (result.role == BrokerRole.FOLLOWING_CAMERA_NODE || result.role == BrokerRole.HOSTING_EMBEDDED) &&
            result.activeBrokerIp != previous.activeBrokerIp
        ) {
            triggerTasmotaMigration(result)
        }

        result
    }

    /**
     * Chạy nền, KHÔNG chặn electNow() trả kết quả — HTTP push tới từng Tasmota có thể mất vài
     * giây/thiết bị. Lỗi từng thiết bị đã được pushMqttHostToTasmota() nuốt & log riêng, ở đây
     * chỉ log tổng kết.
     */
    private fun triggerTasmotaMigration(state: BrokerElectionState) {
        val ip = state.activeBrokerIp ?: return
        responderScope.launch {
            try {
                val res = tasmotaMigrator.migrateAll(
                    newBrokerIp = ip,
                    newBrokerMode = state.brokerMode,
                    newPort = embeddedBrokerPort
                )
                logger.i(
                    TAG,
                    "🔁 Tasmota migrate xong: success=${res.success}/${res.totalWithIp}, " +
                        "skipped(chưa có LAN IP)=${res.skippedNoIp}, failed=${res.failed}"
                )
            } catch (e: Exception) {
                logger.e(TAG, "Tasmota migrate lỗi: ${e.message}", e)
            }
        }
    }

    private suspend fun runElection(trigger: ElectionTrigger): BrokerElectionState {
        val now = System.currentTimeMillis()
        val previous = _currentState.value

        // Bước 1: đọc HOME_CAMERA_NODE_DEVICE_CODE (config đã có sẵn — KHÔNG tạo key mới).
        val homeCameraNodeCode = configProvider
            .getString(AppConfigDefaults.HOME_CAMERA_NODE_DEVICE_CODE, "")
            .trim()
        val myRole = configProvider
            .getString(AppConfigDefaults.DEVICE_ROLE, "client")
            .trim()
        val isThisCameraNode = myRole == "camera_node"

        // Bước 2: nếu có cấu hình Camera Node → probe còn sống không (TCP 1 lần, timeout ngắn).
        if (homeCameraNodeCode.isNotBlank() && !isThisCameraNode) {
            val cameraNodeIp = resolveCameraNodeIp(homeCameraNodeCode)
            if (cameraNodeIp != null && probeTcp(cameraNodeIp, embeddedBrokerPort)) {
                // Camera Node sống → FOLLOWING_CAMERA_NODE.
                // Nếu IP broker vừa đổi (vd trước đó FOLLOWING_ADHOC) → electNow() tự kích
                // hoạt migrate Tasmota ngay sau khi hàm này trả về (xem triggerTasmotaMigration).
                return BrokerElectionState(
                    role = BrokerRole.FOLLOWING_CAMERA_NODE,
                    activeBrokerIp = cameraNodeIp,
                    epoch = previous.epoch,
                    decidedAt = now,
                    brokerMode = "camera_node_host"
                )
            }
            // Camera Node không sống → sang bước 3/4.
            logger.d(TAG, "Camera Node ($homeCameraNodeCode) không phản hồi probe — tiếp tục ad-hoc/cloud")
        }

        // Bước 3: máy này chính là Camera Node đang online?
        if (isThisCameraNode) {
            val myIp = getLocalWifiIp()
            val newEpoch = previous.epoch + 1
            // Host embedded broker (Moquette sẽ bật ở tầng EmbeddedMqttBrokerProvider /
            // Foreground Service theo BrokerRole — xem bước 4 Implementation Order).
            return BrokerElectionState(
                role = BrokerRole.HOSTING_EMBEDDED,
                activeBrokerIp = myIp ?: "127.0.0.1",
                epoch = newEpoch,
                decidedAt = now,
                brokerMode = "camera_node_host"
            )
        }

        // Bước 4: Client, không có Camera Node sống.
        // Probe LAN xem có máy khác đang tự host ad-hoc không (UDP broadcast 1 lần).
        val adhocHost = discoverAdhocHost()
        if (adhocHost != null) {
            // Có máy khác đang host → FOLLOWING_ADHOC.
            // Nếu phát hiện 2 broker (epoch), máy epoch thấp hơn sẽ nhường ở lần probe sau.
            return BrokerElectionState(
                role = BrokerRole.FOLLOWING_ADHOC,
                activeBrokerIp = adhocHost.ip,
                epoch = adhocHost.epoch,
                decidedAt = now,
                brokerMode = "adhoc"
            )
        }

        // Không ai host → chính máy này làm ad-hoc.
        val myIp = getLocalWifiIp()
        if (myIp != null && networkContext.getCurrentWifiSubnet() != null) {
            val newEpoch = previous.epoch + 1
            // Broadcast "tôi đang host" để máy khác discover được.
            announceAdhocHost(myIp, newEpoch)
            return BrokerElectionState(
                role = BrokerRole.HOSTING_EMBEDDED,
                activeBrokerIp = myIp,
                epoch = newEpoch,
                decidedAt = now,
                brokerMode = "adhoc"
            )
        }

        // Bước 5: không có kết nối LAN nào khả dụng (đang ở ngoài) → USING_CLOUD.
        return BrokerElectionState(
            role = BrokerRole.USING_CLOUD,
            activeBrokerIp = null,
            epoch = previous.epoch,
            decidedAt = now,
            brokerMode = "cloud"
        )
    }

    /**
     * Resolve IP của Camera Node từ device code.
     *
     * Hiện tại: chưa có bảng map deviceCode→IP sẵn trong app (pairing chỉ lưu deviceCode).
     * Chiến lược tối thiểu:
     * 1. Thử đọc IP đã cache trong AppConfig (key phụ, optional).
     * 2. Nếu không có — trả null (probe sẽ fail → rơi ad-hoc/cloud).
     *
     * TODO (khi có pairing IP thật): lưu IP Camera Node lúc ghép cặp / heartbeat và đọc lại đây.
     * Không scan toàn subnet — tốn pin, không cần vì đã biết deviceCode từ pairing.
     */
    private suspend fun resolveCameraNodeIp(deviceCode: String): String? {
        // Optional cache key — không khai báo trong AppConfigDefaults (raw string, cùng khuôn
        // MqttConfigKeys). Chỉ dùng nội bộ election.
        val cached = configProvider.getString("mqtt.camera_node_ip.$deviceCode", "").trim()
        if (cached.isNotBlank()) return cached
        logger.d(TAG, "Chưa có IP cache cho Camera Node '$deviceCode' — bỏ qua probe Camera Node")
        return null
    }

    /** Probe TCP 1 lần tới host:port với timeout ngắn. */
    private fun probeTcp(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS.toInt())
                true
            }
        } catch (e: Exception) {
            logger.d(TAG, "probeTcp($host:$port) fail: ${e.message}")
            false
        }
    }

    /**
     * UDP broadcast 1 lần hỏi "ai đang host ad-hoc?".
     * Tái dùng pattern multicast lock từ TuyaLocalController.listenBroadcast() —
     * PHẢI nhớ CHANGE_WIFI_MULTICAST_STATE (đã tìm ra qua SHAS).
     */
    private data class AdhocHostInfo(val ip: String, val epoch: Long)

    private fun discoverAdhocHost(): AdhocHostInfo? {
        var multicastLock: WifiManager.MulticastLock? = null
        var socket: DatagramSocket? = null
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                @Suppress("DEPRECATION")
                multicastLock = wifiManager.createMulticastLock("MqttAdhocDiscovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = ADHOC_DISCOVERY_TIMEOUT_MS.toInt()
            }

            // Gửi probe
            val probePayload = "$ADHOC_MAGIC|PROBE".toByteArray(StandardCharsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            socket.send(
                DatagramPacket(probePayload, probePayload.size, broadcastAddr, ADHOC_DISCOVERY_UDP_PORT)
            )

            // Chờ 1 câu trả lời (timeout ngắn — không lặp).
            val buf = ByteArray(256)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            val text = String(response.data, 0, response.length, StandardCharsets.UTF_8)
            // Format: AICHATVN_MQTT_ADHOC|HOST|<ip>|<epoch>
            val parts = text.split("|")
            if (parts.size >= 4 && parts[0] == ADHOC_MAGIC && parts[1] == "HOST") {
                val ip = parts[2]
                val epoch = parts[3].toLongOrNull() ?: 0L
                // Bỏ qua nếu chính mình (so IP local).
                val myIp = getLocalWifiIp()
                if (ip != myIp) {
                    logger.i(TAG, "Phát hiện ad-hoc host: $ip epoch=$epoch")
                    return AdhocHostInfo(ip, epoch)
                }
            }
            null
        } catch (e: Exception) {
            // Timeout / không ai trả lời — bình thường.
            logger.d(TAG, "discoverAdhocHost: không tìm thấy host (${e.message})")
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Broadcast "tôi đang host ad-hoc" để máy khác discover được.
     * Gọi SAU KHI đã quyết định HOSTING_EMBEDDED với brokerMode=adhoc.
     * Không phải vòng lặp — chỉ 1 gói mỗi lần elect quyết định host.
     */
    private fun announceAdhocHost(ip: String, epoch: Long) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val payload = "$ADHOC_MAGIC|HOST|$ip|$epoch".toByteArray(StandardCharsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                socket.send(
                    DatagramPacket(payload, payload.size, broadcastAddr, ADHOC_DISCOVERY_UDP_PORT)
                )
            }
            logger.d(TAG, "announceAdhocHost: $ip epoch=$epoch")
        } catch (e: Exception) {
            logger.w(TAG, "announceAdhocHost lỗi: ${e.message}")
        }
    }

    /**
     * ✅ MỚI — LISTENER THẬT trả lời PROBE tại port [ADHOC_DISCOVERY_UDP_PORT].
     *
     * ⚠️ ĐÂY LÀ FIX CHO LỖI THIẾT KẾ NGHIÊM TRỌNG: trước đây `discoverAdhocHost()` gửi PROBE
     * rồi `receive()` trên socket EPHEMERAL của chính nó (không bind vào 18830), còn
     * `announceAdhocHost()` chỉ bắn 1 gói MỘT LẦN DUY NHẤT ngay lúc vừa quyết định host xong —
     * không phải phản hồi cho probe. Không có đoạn code nào thực sự LẮNG NGHE (bind) tại port
     * 18830 để trả lời PROBE của Client tới sau → discoverAdhocHost() của MỌI Client sau đó
     * luôn timeout → mọi Client kết luận "không ai host" → tự host ad-hoc → split-brain xảy ra
     * THƯỜNG XUYÊN, không phải cửa sổ hiếm gặp như thiết kế ban đầu giả định. Vi phạm trực
     * tiếp Acceptance Test #2/#3/#4/#5.
     *
     * Sửa: khi máy này đang ở role HOSTING_EMBEDDED (dù camera_node_host hay adhoc), chạy 1
     * coroutine nền bind cố định port 18830, nhận "PROBE", unicast trả lời thẳng về
     * packet.address/packet.port của máy hỏi với HOST hiện tại + epoch mới nhất (đọc động từ
     * `_currentState`, không đóng cứng epoch lúc start). Dừng khi role đổi khỏi HOSTING_EMBEDDED.
     */
    private fun ensureAdhocResponderRunning() {
        if (responderJob?.isActive == true) return
        responderJob = responderScope.launch {
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    @Suppress("DEPRECATION")
                    multicastLock = wifiManager.createMulticastLock("MqttAdhocResponder").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }

                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ADHOC_DISCOVERY_UDP_PORT))
                }
                responderSocket = socket
                logger.i(TAG, "📡 Ad-hoc responder bind port $ADHOC_DISCOVERY_UDP_PORT — sẵn sàng trả lời PROBE")

                val buf = ByteArray(256)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    // Block tới khi có gói mới HOẶC socket bị close() (ném SocketException,
                    // thoát vòng lặp qua catch bên dưới) — KHÔNG phải polling theo chu kỳ.
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (text == "$ADHOC_MAGIC|PROBE") {
                        val state = _currentState.value
                        if (state.role == BrokerRole.HOSTING_EMBEDDED && state.activeBrokerIp != null) {
                            val reply = "$ADHOC_MAGIC|HOST|${state.activeBrokerIp}|${state.epoch}"
                                .toByteArray(StandardCharsets.UTF_8)
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            logger.d(TAG, "↩️ Trả lời PROBE từ ${packet.address}: HOST ${state.activeBrokerIp} epoch=${state.epoch}")
                        }
                    }
                }
            } catch (e: SocketException) {
                // socket.close() gọi từ stopAdhocResponder() — dừng bình thường, không phải lỗi.
                logger.d(TAG, "Ad-hoc responder dừng: ${e.message}")
            } catch (e: Exception) {
                logger.w(TAG, "Ad-hoc responder lỗi: ${e.message}")
            } finally {
                try {
                    responderSocket?.close()
                } catch (_: Exception) {
                }
                responderSocket = null
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopAdhocResponder() {
        responderJob?.cancel()
        responderJob = null
        try {
            responderSocket?.close()
        } catch (_: Exception) {
        }
        responderSocket = null
    }

    /** IP Wi-Fi hiện tại của máy này (null nếu không kết nối Wi-Fi). */
    private fun getLocalWifiIp(): String? {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            logger.e(TAG, "getLocalWifiIp lỗi: ${e.message}", e)
            null
        }
    }

    /**
     * Cập nhật brokerMode cho mọi thiết bị MQTT đã biết (sau election).
     * ✅ SỬA: trước đây public nhưng không tầng ngoài nào gọi (hàm mồ côi) — giờ electNow()
     * tự gọi trực tiếp sau mỗi lần bầu, đảm bảo DB luôn khớp role hiện tại. Vẫn giữ public vì
     * MqttViewModel/UI có thể muốn gọi thủ công (ví dụ nút "làm mới" trong Dashboard).
     */
    suspend fun syncBrokerModeToDevices(brokerMode: String) {
        withContext(Dispatchers.IO) {
            try {
                val devices = mqttDeviceDao.getAllDevices()
                devices.forEach { device ->
                    if (device.brokerMode != brokerMode) {
                        mqttDeviceDao.updateBrokerMode(device.id, brokerMode)
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "syncBrokerModeToDevices lỗi: ${e.message}", e)
            }
        }
    }
}
