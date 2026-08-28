package com.aichatvn.agent.devices

import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.devices.mqtt.MqttDiscoveryEngine
import com.aichatvn.agent.devices.mqtt.DiscoveredMqttDevice
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MqttConfigKeys
 *
 * Key cấu hình broker — RAW STRING KEY đọc/ghi thẳng qua AppConfigProvider.getString/set(key,
 * default), cùng quy ước "worldstate_guard_$deviceId" đã dùng trong TuyaScreen.kt — KHÔNG
 * đăng ký trong AppConfigDefaults.kt. Lý do: cấu hình broker nằm ngay trong tab "MQTT" mới
 * (mục 13.3 tài liệu kiến trúc), không qua SettingsScreen, nên không cần entry hiện ở đó.
 * public (không phải private trong companion object của controller) để MqttViewModel dùng
 * chung đúng 1 nguồn tên key, tránh gõ tay 2 chỗ dễ lệch.
 */
object MqttConfigKeys {
    const val BROKER_URL = "mqtt.broker_url"
    const val USERNAME = "mqtt.username"
    const val PASSWORD = "mqtt.password"

    // ✅ MỚI (track Cloud Broker V1): Port/TLS tách riêng khỏi Broker URL để UI "Kiểm tra kết
    // nối" hiện được form rõ ràng hơn thay vì bắt người dùng tự gõ đúng scheme "ssl://host:port".
    // KHÔNG dùng để override nếu Broker URL người dùng dán vào đã có sẵn scheme+port riêng —
    // xem MqttDeviceController.buildBrokerUri(): chỉ áp dụng khi Broker URL là bare host.
    const val PORT = "mqtt.port"           // mặc định 8883 (TLS) nếu chưa cấu hình
    const val USE_TLS = "mqtt.use_tls"     // mặc định true
}

/**
 * MqttBrokerProvider
 *
 * Tách "kết nối tới đâu" ra khỏi MqttDeviceController — controller chỉ biết gọi connect(),
 * không tự quyết định là nối Cloud hay (sau này, track khác — KHÔNG làm trong task này) nối
 * Embedded Broker chạy trên Camera Node. Hiện chỉ có 1 implementation (CloudMqttBrokerProvider).
 */
interface MqttBrokerProvider {
    val brokerType: BrokerType
    suspend fun connect(): MqttClient?
}

enum class BrokerType { CLOUD, EMBEDDED_CAMERA_NODE }

/**
 * CloudMqttBrokerProvider
 *
 * Implementation duy nhất — đọc cấu hình broker cloud (HiveMQ/EMQX...) đã lưu qua
 * AppConfigProvider, dựng MqttClient + connect. KHÔNG thay đổi so với bản trước — track
 * "hoàn thiện Cloud MQTT" (realtime + discovery) không đụng tới logic connect/auth ở đây.
 */
@Singleton
class CloudMqttBrokerProvider @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val logger: Logger,
) : MqttBrokerProvider {

    override val brokerType: BrokerType = BrokerType.CLOUD

    private suspend fun buildBrokerUri(rawBrokerUrl: String): String {
        if (rawBrokerUrl.contains("://")) return rawBrokerUrl

        val useTls = configProvider.getString(MqttConfigKeys.USE_TLS, "true").toBooleanStrictOrNull() ?: true
        val port = configProvider.getString(MqttConfigKeys.PORT, "").trim()
            .toIntOrNull() ?: if (useTls) 8883 else 1883
        val scheme = if (useTls) "ssl" else "tcp"
        return "$scheme://$rawBrokerUrl:$port"
    }

    /**
     * ✅ MỚI (track hoàn thiện Cloud MQTT — mục "Reconnect/resubscribe"): bật
     * `isAutomaticReconnect` — Paho tự thử kết nối lại khi mất kết nối, KHÔNG cần
     * MqttDeviceController tự viết vòng lặp retry riêng. Kèm `MqttCallbackExtended` (đăng ký ở
     * MqttDeviceController, không đăng ký ở đây) là cơ chế bắt sự kiện "vừa kết nối lại xong" để
     * gọi lại subscribe — xem MqttDeviceController.installRealtimeCallback().
     */
    override suspend fun connect(): MqttClient? {
        val rawBrokerUrl = configProvider.getString(MqttConfigKeys.BROKER_URL, "").trim()
        if (rawBrokerUrl.isBlank()) return null // chưa cấu hình — nghiệp vụ gọi tự xử lý null

        return try {
            val brokerUri = buildBrokerUri(rawBrokerUrl)
            val clientId = "aichatvn2_" + UUID.randomUUID().toString().take(8)
            val newClient = MqttClient(brokerUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                // ✅ MỚI: cho phép Paho tự reconnect nền — điều kiện bắt buộc để
                // MqttCallbackExtended.connectComplete(reconnect = true, ...) bắn ra sau khi
                // mạng chập chờn rồi ổn định lại, không cần app tự polling kiểm tra isConnected.
                isAutomaticReconnect = true
                val user = configProvider.getString(MqttConfigKeys.USERNAME, "").trim()
                val pass = configProvider.getString(MqttConfigKeys.PASSWORD, "")
                if (user.isNotBlank()) {
                    userName = user
                    password = pass.toCharArray()
                }
            }
            newClient.connect(options)
            newClient
        } catch (e: Exception) {
            logger.e("CloudMqttBrokerProvider", "Kết nối broker lỗi: ${e.message}", e)
            null
        }
    }
}

// ❌ ĐÃ XOÁ (MQTT Symmetric Broker, bắt buộc khi merge — xem
// MQTT_SYMMETRIC_BROKER_IMPLEMENTATION_REPORT.md mục "Bước 3"): abstract class MqttBrokerModule
// cũ (@Binds tĩnh CloudMqttBrokerProvider -> MqttBrokerProvider) — thay bằng
// MqttBrokerModule.kt mới (file riêng, @Provides ĐỘNG chọn Cloud/Embedded theo BrokerRole qua
// DelegatingMqttBrokerProvider). Giữ cả 2 sẽ trùng @Module cùng cung cấp MqttBrokerProvider,
// Hilt lỗi build ngay. CloudMqttBrokerProvider phía trên GIỮ NGUYÊN 100%, chỉ đường bind đổi.

/**
 * MqttDeviceRuntimeStatus
 *
 * ✅ MỚI (track hoàn thiện Cloud MQTT, mục "Realtime state/status"): trạng thái RUNTIME (không
 * lưu DB) tách biệt khỏi cột `online`/`lastKnownState` tĩnh trong MqttDeviceEntity — 4 giá trị,
 * KHÔNG rút gọn về 2 giá trị on/off như trước, để UI phân biệt được "chắc chắn đang tắt" với
 * "không rõ vì chưa có message nào". Quy tắc chuyển trạng thái, xem installRealtimeCallback():
 * - UNKNOWN: vừa subscribe xong, CHƯA nhận được message nào trên stateTopic của thiết bị này.
 * - ON / OFF: đã nhận ít nhất 1 message trên stateTopic, payload khớp onPayload/offPayload.
 * - OFFLINE: broker báo mất kết nối (connectionLost) — MỌI thiết bị đang theo dõi đều rơi về
 *   OFFLINE cho tới khi reconnect + resubscribe xong (quay lại UNKNOWN, chờ message mới).
 *
 * Payload lạ (không khớp onPayload lẫn offPayload) được GIỮ NGUYÊN trạng thái cũ thay vì đoán —
 * xem handleIncomingStateMessage().
 */
enum class MqttDeviceRuntimeStatus { UNKNOWN, ON, OFF, OFFLINE }

data class MqttRealtimeState(
    val deviceId: String,
    val status: MqttDeviceRuntimeStatus,
    val updatedAt: Long
)

/**
 * MqttDeviceController
 *
 * (Track hoàn thiện Cloud MQTT) Bổ sung so với bản trước:
 * 1. Realtime state/status: subscribe stateTopic của mọi thiết bị đã lưu ngay sau khi connect
 *    (và sau mỗi lần reconnect tự động), parse theo onPayload/offPayload riêng từng thiết bị,
 *    phát ra qua `realtimeStates: StateFlow` để MqttViewModel/UI quan sát realtime — KHÔNG chỉ
 *    đọc cache DB tĩnh như trước.
 * 2. discoverDevices(): lớp discovery mới dùng MqttDiscoveryEngine (Home Assistant/Homie/
 *    vendor adapter) — xem devices/mqtt/MqttDiscoveryEngine.kt. scanTopics() CŨ vẫn giữ nguyên
 *    làm fallback quan sát thô, không xoá, không đổi hành vi.
 *
 * KHÔNG đổi implements DeviceController — turnOn/turnOff/getStatus/listDevices/deleteDevice giữ
 * nguyên chữ ký/hành vi bên ngoài, để không phá SmartSwitchSkill/DeviceControlRegistry đang gọi
 * qua interface này. getStatus() vẫn trả DeviceStatus(isOn, isOnline) — hai field boolean đó ĐÃ
 * ĐỦ để phản ánh "online + on/off" cho các bên tiêu thụ hiện có; phần 4 trạng thái chi tiết hơn
 * (UNKNOWN riêng biệt với OFFLINE) chỉ dành cho UI mới qua `realtimeStates`, không đẩy ngược
 * vào interface DeviceController dùng chung.
 */
// ⚠️ GlobalScope: 2 chỗ dùng trong file này (installRealtimeCallback/handleIncomingStateMessage)
// đều chạy TRÊN THREAD NỘI BỘ CỦA PAHO (connectComplete/messageArrived), không nằm trong bất kỳ
// CoroutineScope nào của Android (không phải viewModelScope/lifecycleScope) — không có scope cha
// hợp lý nào để gắn vào. Vì MqttDeviceController là @Singleton (sống suốt vòng đời app), dùng
// GlobalScope ở đây là lựa chọn có chủ đích, không phải quên inject scope — nếu project đã có
// sẵn 1 "ApplicationScope" tự định nghĩa (CoroutineScope(SupervisorJob() + Dispatchers.Default)
// inject qua Hilt), NÊN đổi sang dùng scope đó thay vì GlobalScope khi ghép vào project thật.
@OptIn(DelicateCoroutinesApi::class)
@Singleton
class MqttDeviceController @Inject constructor(
    private val mqttDeviceDao: MqttDeviceDao,
    private val brokerProvider: MqttBrokerProvider,
    private val discoveryEngine: MqttDiscoveryEngine,
    private val logger: Logger,
    // ✅ MỚI (MQTT Symmetric Broker, PATCH A2): song song publishTuyaStateChange() ở
    // SmartSwitchSkill/WebhookGatewayService — publish qua household để các máy khác trong nhà
    // sync world_state MQTT, không chỉ máy vừa ra lệnh/vừa nhận message.
    private val householdEventPublisher: com.aichatvn.agent.skills.HouseholdEventPublisher,
) : DeviceController {

    override val protocol: DeviceProtocol = DeviceProtocol.MQTT

    override val capabilities: Set<DeviceCapability> = setOf(
        DeviceCapability.REALTIME_STATUS
    )

    override val worldStateSource: String = "mqtt"

    override val displayName: String = "📡 Thiết bị MQTT"

    private val connectMutex = Mutex()
    @Volatile private var client: MqttClient? = null

    // ✅ MỚI: deviceId -> stateTopic đang thực sự có subscription sống trên client hiện tại.
    // Dùng để refreshDeviceSubscriptions() biết topic nào cần subscribe thêm / unsubscribe bớt
    // khi danh sách thiết bị đổi (thêm/xoá), tránh subscribe trùng hoặc rò rỉ subscription cũ
    // của thiết bị đã bị xoá.
    private val activeStateSubscriptions = ConcurrentHashMap<String, String>() // deviceId -> stateTopic

    private val _realtimeStates = MutableStateFlow<Map<String, MqttRealtimeState>>(emptyMap())
    /** Quan sát realtime cho UI (MqttViewModel) — KHÔNG phải nguồn thật cho DeviceController.getStatus(). */
    val realtimeStates: StateFlow<Map<String, MqttRealtimeState>> = _realtimeStates.asStateFlow()

    /**
     * Đổi cấu hình broker giữa chừng (từ MqttViewModel, sau khi người dùng lưu ở tab MQTT) —
     * ngắt kết nối cũ ngay, lần gọi turnOn/turnOff/subscribe tiếp theo sẽ tự connect + subscribe
     * lại theo config mới thay vì lỡ dùng nhầm kết nối tới broker cũ đã đổi.
     */
    fun invalidateConnection() {
        try {
            client?.takeIf { it.isConnected }?.disconnect()
        } catch (e: Exception) {
            logger.d("MqttDeviceController", "disconnect() khi đổi broker: ${e.message}")
        } finally {
            client = null
            activeStateSubscriptions.clear()
            // Config vừa đổi — trạng thái realtime cũ (nếu có) không còn tin cậy, về UNKNOWN hết
            // cho tới khi kết nối lại + subscribe lại xong.
            markAllUnknown()
        }
    }

    private suspend fun getOrConnect(): MqttClient? = connectMutex.withLock {
        client?.takeIf { it.isConnected }?.let { return it }
        val newClient = brokerProvider.connect() ?: return null
        installRealtimeCallback(newClient)
        client = newClient
        // Kết nối mới (không phải do Paho tự auto-reconnect gọi lại connectComplete) — chủ động
        // subscribe ngay 1 lần ở đây, vì connectComplete(reconnect = false, ...) của lần connect
        // ĐẦU TIÊN cũng bắn ra, nhưng để chắc chắn không phụ thuộc thời điểm callback thật sự
        // chạy (chạy trên thread nội bộ Paho), gọi thêm 1 lần tường minh tại đây là vô hại
        // (subscribeAllDeviceStates() tự bỏ qua topic đã có trong activeStateSubscriptions).
        subscribeAllDeviceStates(newClient)
        newClient
    }

    /**
     * ✅ MỚI: đăng ký MqttCallbackExtended lên client — bắt 2 sự kiện:
     * - connectionLost: mất kết nối (mạng rớt, broker restart...) — đánh dấu MỌI thiết bị đang
     *   theo dõi realtime là OFFLINE ngay lập tức, không chờ timeout nào khác. Paho tự lo phần
     *   reconnect nhờ isAutomaticReconnect = true đã bật ở CloudMqttBrokerProvider.
     * - connectComplete(reconnect, ...): bắn ra cho CẢ lần connect đầu tiên (reconnect = false)
     *   LẪN mọi lần Paho tự reconnect thành công (reconnect = true) — đây là nơi DUY NHẤT cần
     *   gọi lại subscribeAllDeviceStates() để thoả yêu cầu "khi reconnect MQTT, phải subscribe
     *   lại", không cần code gọi ngoài tự đoán khi nào đã reconnect xong.
     *
     * messageArrived(topic, message) ở cấp callback toàn cục CHỈ được Paho gọi cho các
     * subscription KHÔNG có IMqttMessageListener ring riêng — mọi subscribe(topic, qos, listener)
     * trong file này (kể cả scanTopics() cũ) đều truyền listener riêng, nên implement rỗng ở đây
     * là an toàn, không mất message nào.
     */
    private fun installRealtimeCallback(mqttClient: MqttClient) {
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                logger.i(
                    "MqttDeviceController",
                    if (reconnect) "🔄 MQTT reconnect thành công ($serverURI) — subscribe lại state topics"
                    else "✅ MQTT connect thành công ($serverURI)"
                )
                activeStateSubscriptions.clear()
                markAllUnknown()
                // setCallback chạy trên thread nội bộ Paho — subscribeAllDeviceStates() gọi
                // mqttDeviceDao (Room, có thể block I/O) nên đẩy sang 1 coroutine IO riêng thay
                // vì chạy thẳng trên thread callback của Paho.
                GlobalScope.launch(Dispatchers.IO) {
                    subscribeAllDeviceStates(mqttClient)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                logger.w("MqttDeviceController", "⚠️ Mất kết nối MQTT: ${cause?.message}")
                markAllOffline()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // Không dùng — mọi subscribe() trong file này đều gắn listener riêng theo topic.
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Không cần theo dõi — publish() hiện tại là fire-and-forget (không QoS 2 chờ
                // xác nhận 2 chiều), giữ đúng hành vi cũ.
            }
        })
    }

    private fun markAllUnknown() {
        _realtimeStates.update { current ->
            current.mapValues { (id, state) ->
                if (state.status == MqttDeviceRuntimeStatus.OFFLINE)
                    state.copy(status = MqttDeviceRuntimeStatus.UNKNOWN, updatedAt = System.currentTimeMillis())
                else state
            }
        }
    }

    private fun markAllOffline() {
        val now = System.currentTimeMillis()
        _realtimeStates.update { current ->
            current.mapValues { (_, state) -> state.copy(status = MqttDeviceRuntimeStatus.OFFLINE, updatedAt = now) }
        }
    }

    /**
     * subscribeAllDeviceStates
     *
     * Duyệt toàn bộ thiết bị đã lưu, subscribe `stateTopic` (bỏ qua thiết bị chưa có stateTopic
     * — thiết bị nhập tay kiểu cũ trước khi có trường này chỉ điều khiển một chiều, không có gì
     * để subscribe, KHÔNG coi là lỗi). Gọi lại an toàn nhiều lần (idempotent) nhờ
     * activeStateSubscriptions — thiết bị đã subscribe rồi thì bỏ qua.
     */
    private suspend fun subscribeAllDeviceStates(mqttClient: MqttClient) {
        val devices = try {
            withContext(Dispatchers.IO) { mqttDeviceDao.getAllDevices() }
        } catch (e: Exception) {
            logger.e("MqttDeviceController", "subscribeAllDeviceStates(): đọc danh sách thiết bị lỗi: ${e.message}", e)
            return
        }

        devices.forEach { entity ->
            val stateTopic = entity.stateTopic?.trim().orEmpty()
            if (stateTopic.isBlank()) return@forEach
            if (activeStateSubscriptions[entity.id] == stateTopic) return@forEach // đã subscribe

            // Khởi tạo UNKNOWN ngay khi bắt đầu theo dõi — CHƯA có message nào, không được suy ra OFF.
            _realtimeStates.update { it + (entity.id to MqttRealtimeState(entity.id, MqttDeviceRuntimeStatus.UNKNOWN, System.currentTimeMillis())) }

            try {
                mqttClient.subscribe(stateTopic, 1, IMqttMessageListener { _, message ->
                    handleIncomingStateMessage(entity.id, entity.onPayload, entity.offPayload, message?.payload)
                })
                activeStateSubscriptions[entity.id] = stateTopic
            } catch (e: Exception) {
                logger.e(
                    "MqttDeviceController",
                    "subscribeAllDeviceStates(): subscribe stateTopic='$stateTopic' cho thiết bị ${entity.id} lỗi: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * refreshDeviceSubscriptions
     *
     * ✅ MỚI: gọi TỪ NGOÀI (MqttViewModel) ngay sau addDevice()/deleteDevice() — để thiết bị vừa
     * thêm được subscribe state ngay, không phải đợi tới lần reconnect kế tiếp; và thiết bị vừa
     * xoá được unsubscribe ngay, không rò rỉ subscription/entry cache của thiết bị không còn
     * tồn tại. Không throw — publish/turnOn/turnOff không phụ thuộc hàm này, lỗi ở đây chỉ ảnh
     * hưởng phần hiển thị realtime, không ảnh hưởng điều khiển.
     */
    suspend fun refreshDeviceSubscriptions() = withContext(Dispatchers.IO) {
        val mqttClient = getOrConnect() ?: return@withContext
        val currentDeviceIds = try {
            mqttDeviceDao.getAllDevices().map { it.id }.toSet()
        } catch (e: Exception) {
            return@withContext
        }

        // Unsubscribe + dọn cache cho thiết bị đã bị xoá.
        val removedIds = activeStateSubscriptions.keys - currentDeviceIds
        removedIds.forEach { deviceId ->
            val topic = activeStateSubscriptions.remove(deviceId)
            try {
                if (topic != null) mqttClient.unsubscribe(topic)
            } catch (e: Exception) {
                logger.d("MqttDeviceController", "refreshDeviceSubscriptions(): unsubscribe lỗi (bỏ qua): ${e.message}")
            }
            _realtimeStates.update { it - deviceId }
        }

        subscribeAllDeviceStates(mqttClient)
    }

    /**
     * handleIncomingStateMessage
     *
     * So khớp payload nhận được với `onPayload`/`offPayload` RIÊNG của từng thiết bị (không
     * hardcode "ON"/"OFF") — trim + so khớp nguyên văn (case-sensitive, vì nhiều firmware phân
     * biệt hoa/thường, vd Tasmota trả đúng "ON"/"OFF" nhưng 1 số custom firmware có thể dùng
     * "on"/"off" — KHÔNG tự ý lowercase để tránh đổi ý nghĩa payload người dùng đã cấu hình).
     * Payload lạ (không khớp cả hai) GIỮ NGUYÊN trạng thái cũ thay vì đoán thành OFF — đúng yêu
     * cầu "không tự đoán nguy hiểm từ topic MQTT thuần".
     *
     * Cập nhật 2 nơi khi khớp ON/OFF: (1) `_realtimeStates` — nguồn cho UI mới; (2)
     * `mqttDeviceDao.updateState()` — cache DB cũ vẫn cần đúng vì DeviceController.getStatus()
     * (dùng bởi SmartSwitchSkill, SystemAuditor...) đọc từ đây, KHÔNG đọc `_realtimeStates`.
     */
    private fun handleIncomingStateMessage(deviceId: String, onPayload: String, offPayload: String, rawPayload: ByteArray?) {
        val payload = rawPayload?.toString(Charsets.UTF_8)?.trim() ?: return
        val newStatus = when (payload) {
            onPayload -> MqttDeviceRuntimeStatus.ON
            offPayload -> MqttDeviceRuntimeStatus.OFF
            else -> {
                logger.d("MqttDeviceController", "handleIncomingStateMessage($deviceId): payload lạ '$payload' — giữ nguyên trạng thái cũ")
                return
            }
        }
        val now = System.currentTimeMillis()
        _realtimeStates.update { it + (deviceId to MqttRealtimeState(deviceId, newStatus, now)) }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                mqttDeviceDao.updateState(
                    deviceId,
                    state = newStatus == MqttDeviceRuntimeStatus.ON,
                    online = true,
                    timestamp = now
                )
                // ✅ MỚI (PATCH A2): broker phát hiện đổi trạng thái (vd bấm tay công tắc vật lý)
                // — publish cho household với source="subscribe" để phân biệt với điều khiển chủ
                // động qua app (source mặc định "mqtt" ở publish()/turnOn()/turnOff() phía trên).
                val entity = mqttDeviceDao.getDeviceById(deviceId)
                if (entity != null) {
                    householdEventPublisher.publishMqttStateChange(
                        deviceId,
                        entity.name,
                        newStatus == MqttDeviceRuntimeStatus.ON,
                        source = "subscribe"
                    )
                }
            } catch (e: Exception) {
                logger.e("MqttDeviceController", "handleIncomingStateMessage($deviceId): cập nhật DB lỗi: ${e.message}", e)
            }
        }
    }

    /**
     * scanTopics ("Quét nhanh" — GIỮ NGUYÊN, không đổi hành vi)
     *
     * ✅ Theo yêu cầu track hoàn thiện Cloud MQTT: giữ hàm này làm fallback observer thô, KHÔNG
     * còn là con đường discovery chính — xem discoverDevices() bên dưới cho luồng chuẩn mới.
     * Docstring gốc giữ nguyên lý do kỹ thuật (MQTT không có API "liệt kê thiết bị" chuẩn hoá).
     */
    suspend fun scanTopics(timeoutMs: Long = 8000L): List<String> = withContext(Dispatchers.IO) {
        val mqttClient = getOrConnect()
            ?: run {
                logger.d("MqttDeviceController", "scanTopics(): chưa cấu hình broker, bỏ qua")
                return@withContext emptyList()
            }

        val found = ConcurrentHashMap.newKeySet<String>()
        val listener = IMqttMessageListener { topic, _ -> found.add(topic) }

        try {
            mqttClient.subscribe("#", 0, listener)
        } catch (e: Exception) {
            logger.e(
                "MqttDeviceController",
                "scanTopics(): broker từ chối subscribe '#' (có thể do ACL giới hạn): ${e.message}",
                e
            )
            return@withContext emptyList()
        }

        try {
            delay(timeoutMs)
        } finally {
            try {
                mqttClient.unsubscribe("#")
            } catch (e: Exception) {
                logger.d("MqttDeviceController", "scanTopics(): unsubscribe('#') lỗi (bỏ qua): ${e.message}")
            }
        }

        found.toList().sorted()
    }

    /**
     * discoverDevices ("Quét nhanh" chuẩn mới — track hoàn thiện Cloud MQTT)
     *
     * Lắng nghe thụ động "#" TRONG CÙNG 1 cửa sổ thời gian như scanTopics(), nhưng đưa từng cặp
     * (topic, payload) qua `MqttDiscoveryEngine` (Home Assistant / Homie / vendor adapter) thay
     * vì chỉ gom topic thô. Trả về CẢ HAI: danh sách thiết bị đã NHẬN DIỆN được (đủ tên gợi ý +
     * command/state topic + payload — sẵn sàng cho UI xác nhận trước khi lưu) VÀ danh sách topic
     * thô không adapter nào nhận diện được (dùng làm fallback y hệt scanTopics() cũ cho luồng
     * "Thêm tay").
     *
     * dedupe theo `uniqueId` — 1 thiết bị Home Assistant Discovery thường publish lại retained
     * message nhiều lần (mỗi lần app subscribe lại), không được hiện trùng trong danh sách.
     */
    suspend fun discoverDevices(timeoutMs: Long = 8000L): MqttDiscoveryScanResult = withContext(Dispatchers.IO) {
        val mqttClient = getOrConnect()
            ?: run {
                logger.d("MqttDeviceController", "discoverDevices(): chưa cấu hình broker, bỏ qua")
                return@withContext MqttDiscoveryScanResult(emptyList(), emptyList())
            }

        val recognized = ConcurrentHashMap<String, DiscoveredMqttDevice>() // uniqueId -> device
        val rawTopics = ConcurrentHashMap.newKeySet<String>()

        val listener = IMqttMessageListener { topic, message ->
            rawTopics.add(topic)
            val payload = try {
                message?.payload?.toString(Charsets.UTF_8) ?: ""
            } catch (e: Exception) {
                ""
            }
            discoveryEngine.parse(topic, payload)?.let { device ->
                recognized[device.uniqueId] = device
            }
        }

        try {
            mqttClient.subscribe("#", 0, listener)
        } catch (e: Exception) {
            logger.e(
                "MqttDeviceController",
                "discoverDevices(): broker từ chối subscribe '#' (có thể do ACL giới hạn): ${e.message}",
                e
            )
            return@withContext MqttDiscoveryScanResult(emptyList(), emptyList())
        }

        try {
            delay(timeoutMs)
        } finally {
            try {
                mqttClient.unsubscribe("#")
            } catch (e: Exception) {
                logger.d("MqttDeviceController", "discoverDevices(): unsubscribe('#') lỗi (bỏ qua): ${e.message}")
            }
        }

        // Loại khỏi rawTopics những topic đã được 1 adapter nhận diện thành công (đã có trong
        // recognized) — tránh hiện trùng lặp giữa 2 danh sách trả về cho UI.
        val recognizedTopics = recognized.values.mapNotNull { it.stateTopic } +
            recognized.values.map { it.commandTopic }
        val fallbackTopics = rawTopics.filterNot { it in recognizedTopics }.sorted()

        MqttDiscoveryScanResult(recognized.values.toList(), fallbackTopics)
    }

    override suspend fun turnOn(deviceId: String): DeviceActionResult =
        publish(deviceId, newState = true)

    override suspend fun turnOff(deviceId: String): DeviceActionResult =
        publish(deviceId, newState = false)

    // ✅ MỚI (Dimmer): publish mức độ qua levelCommandTopic riêng (KHÁC commandTopic của
    // publish() bên dưới — commandTopic chỉ nhận onPayload/offPayload nhị phân). Payload gửi
    // thẳng chuỗi số 0-100 — khác Tuya (không cần quy đổi thang đo vì MQTT không có khái
    // niệm DPS chuẩn hoá, mỗi thiết bị Tasmota/Home Assistant tự parse số nguyên % ở firmware).
    // Trả Failure rõ ràng nếu levelCommandTopic chưa cấu hình, KHÔNG thử publish vào
    // commandTopic (payload số sẽ bị hiểu nhầm thành lệnh on/off ở phía thiết bị).
    override suspend fun setLevel(deviceId: String, percent: Int): DeviceActionResult {
        val entity = mqttDeviceDao.getDeviceById(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị MQTT id=$deviceId")
        val levelTopic = entity.levelCommandTopic
            ?: return DeviceActionResult.Failure(
                "Thiết bị này chưa cấu hình topic điều chỉnh mức độ (levelCommandTopic)."
            )
        val safePercent = percent.coerceIn(0, 100)

        return withContext(Dispatchers.IO) {
            try {
                val mqttClient = getOrConnect()
                    ?: return@withContext DeviceActionResult.Failure(
                        "Chưa cấu hình broker MQTT — vào tab MQTT để nhập địa chỉ broker."
                    )
                mqttClient.publish(levelTopic, MqttMessage(safePercent.toString().toByteArray()))
                // Phản hồi lạc quan tức thời cho UI, giống publish() — nếu thiết bị CÓ
                // levelStateTopic, message thật (khi được implement subscribe riêng) sẽ ghi đè
                // lại sau. online=true vì publish thành công nghĩa là broker đã nhận.
                mqttDeviceDao.updateLevel(
                    deviceId, level = safePercent, online = true, timestamp = System.currentTimeMillis()
                )
                DeviceActionResult.Success
            } catch (e: Exception) {
                logger.e("MqttDeviceController", "setLevel($deviceId, $safePercent%) lỗi: ${e.message}", e)
                DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi chỉnh mức độ MQTT")
            }
        }
    }

    override suspend fun isDeviceDimmable(deviceId: String): Boolean {
        return mqttDeviceDao.getDeviceById(deviceId)?.isDimmable ?: false
    }

    private suspend fun publish(deviceId: String, newState: Boolean): DeviceActionResult {
        val entity = mqttDeviceDao.getDeviceById(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị MQTT id=$deviceId")
        val payload = if (newState) entity.onPayload else entity.offPayload

        return withContext(Dispatchers.IO) {
            try {
                val mqttClient = getOrConnect()
                    ?: return@withContext DeviceActionResult.Failure(
                        "Chưa cấu hình broker MQTT — vào tab MQTT để nhập địa chỉ broker."
                    )
                mqttClient.publish(entity.commandTopic, MqttMessage(payload.toByteArray()))
                // Cập nhật cache local ngay sau khi publish thành công — nếu thiết bị CÓ
                // stateTopic, message thật từ broker (qua handleIncomingStateMessage) sẽ ghi đè
                // lại đúng giá trị thật ngay sau đó; dòng dưới chỉ là phản hồi lạc quan tức thời
                // cho UI trong lúc chờ, không thay thế nguồn thật.
                mqttDeviceDao.updateState(
                    deviceId, state = newState, online = true, timestamp = System.currentTimeMillis()
                )
                // ✅ MỚI (PATCH A2): publish ngay sau lệnh chủ động thành công — dùng entity.name
                // đã có sẵn (không cần đọc lại DB), source mặc định "mqtt" (điều khiển chủ động,
                // phân biệt với "subscribe" ở handleIncomingStateMessage() bên dưới).
                try {
                    householdEventPublisher.publishMqttStateChange(deviceId, entity.name, newState)
                } catch (e: Exception) {
                    logger.d("MqttDeviceController", "publishMqttStateChange lỗi (bỏ qua): ${e.message}")
                }
                DeviceActionResult.Success
            } catch (e: Exception) {
                logger.e("MqttDeviceController", "publish($deviceId) lỗi: ${e.message}", e)
                DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi gửi lệnh MQTT")
            }
        }
    }

    override suspend fun getStatus(deviceId: String): DeviceStatus? {
        // ✅ SỬA (track hoàn thiện Cloud MQTT): trước đây TODO — chỉ đọc lại DB tĩnh. Giờ ưu
        // tiên đọc `_realtimeStates` (nếu thiết bị có stateTopic và đã nhận được ít nhất 1
        // message) — đây mới là nguồn PHẢN ÁNH TRẠNG THÁI VẬT LÝ THẬT (mất điện, bật tay ở công
        // tắc vật lý sẽ cập nhật qua đây). Fallback về DB tĩnh khi runtime state là UNKNOWN/chưa
        // có (thiết bị không có stateTopic, hoặc vừa mới subscribe, chưa kịp nhận message nào) —
        // giữ đúng hành vi cũ cho trường hợp đó thay vì trả null/lỗi.
        //
        // isOnline=false khi runtime OFFLINE (mất kết nối broker) — ĐÂY LÀ ĐIỂM DUY NHẤT tín
        // hiệu OFFLINE/UNKNOWN "rò" ra ngoài interface DeviceController dùng chung (qua
        // isOnline), vẫn đúng hợp đồng cũ (isOnline Boolean), không cần đổi interface.
        val entity = mqttDeviceDao.getDeviceById(deviceId) ?: return null
        val runtime = _realtimeStates.value[deviceId]
        return when (runtime?.status) {
            MqttDeviceRuntimeStatus.ON -> DeviceStatus(isOn = true, isOnline = true)
            MqttDeviceRuntimeStatus.OFF -> DeviceStatus(isOn = false, isOnline = true)
            MqttDeviceRuntimeStatus.OFFLINE -> DeviceStatus(isOn = entity.lastKnownState, isOnline = false)
            else -> DeviceStatus(isOn = entity.lastKnownState, isOnline = entity.online) // UNKNOWN hoặc chưa theo dõi — fallback DB cũ
        }
    }

    override suspend fun listDevices(): List<DeviceSummary> {
        return mqttDeviceDao.getAllDevices().map { entity ->
            DeviceSummary(id = entity.id, displayName = entity.name)
        }
    }

    override suspend fun deleteDevice(deviceId: String): DeviceActionResult {
        return try {
            mqttDeviceDao.deleteDevice(deviceId)
            // Dọn subscription + cache realtime của thiết bị vừa xoá ngay — không chờ tới lần
            // reconnect kế tiếp mới dọn (xem refreshDeviceSubscriptions()).
            client?.let { mqttClient ->
                activeStateSubscriptions.remove(deviceId)?.let { topic ->
                    try { mqttClient.unsubscribe(topic) } catch (e: Exception) { /* bỏ qua */ }
                }
            }
            _realtimeStates.update { it - deviceId }
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi xoá thiết bị MQTT")
        }
    }

    // ✅ MỚI (Nhất quán Dashboard): LẦN ĐẦU thiết bị MQTT xuất hiện trên Dashboard chính — trước
    // đây getDashboardNodes() (SmartSwitchSkill.kt) chỉ đọc tuyaDeviceDao(), MQTT chỉ điều
    // khiển được qua tab MQTT riêng/chat/lịch. Không cần getStatusBatch() như Tuya — MQTT đã
    // cache lastKnownState/lastKnownLevel trực tiếp qua subscribe realtime (xem
    // DeviceCapability.REALTIME_STATUS), đọc thẳng cột Entity là đủ, không cần gọi gì thêm.
    override suspend fun buildDashboardNodes(): List<DeviceNode> {
        val mqttDevices = mqttDeviceDao.getAllDevices()

        return mqttDevices.map { dev ->
            // ✅ SỬA: _realtimeStates lưu MqttRealtimeState (enum 4 trạng thái: UNKNOWN/ON/OFF/
            // OFFLINE — xem MqttDeviceCard ở MqttScreen.kt), KHÔNG phải Boolean đơn giản như
            // giả định ban đầu. Map đúng 4 trạng thái để nhất quán với cách tab MQTT hiển thị,
            // OFFLINE (mất kết nối realtime) ưu tiên hiển thị khác biệt với "Đang tắt" thường.
            val runtimeStatus = _realtimeStates.value[dev.id]?.status ?: MqttDeviceRuntimeStatus.UNKNOWN
            val isOnline = dev.online && runtimeStatus != MqttDeviceRuntimeStatus.OFFLINE
            val isDeviceOn = when (runtimeStatus) {
                MqttDeviceRuntimeStatus.ON -> true
                MqttDeviceRuntimeStatus.OFF -> false
                // UNKNOWN/OFFLINE: chưa có tín hiệu realtime — dùng lastKnownState cache DB
                // làm fallback, giống hành vi Tuya khi offline (coi tắt để hiển thị an toàn).
                else -> dev.lastKnownState
            }
            val (deviceIcon, deviceLabel, deviceType) = resolveDeviceVisual(dev)

            DeviceNode(
                id = dev.id,
                name = dev.name,
                type = deviceType,
                pluginId = "smart_switch", // xem ghi chú convention ở TuyaDeviceController.buildDashboardNodes()
                defaultAction = "status",
                defaultParams = mapOf("device" to dev.id),
                supportedActions = listOf(
                    DeviceAction(
                        id = "set",
                        title = "Bật $deviceLabel",
                        icon = deviceIcon,
                        defaultParams = mapOf("state" to true)
                    ),
                    DeviceAction(
                        id = "set",
                        title = "Tắt $deviceLabel",
                        icon = "🔌",
                        defaultParams = mapOf("state" to false)
                    ),
                    DeviceAction(
                        id = "status",
                        title = "Trạng thái",
                        icon = "ℹ️"
                    )
                ),
                x = 0f, // gán thật ở SmartSwitchSkill.getDashboardNodes() sau khi gộp
                y = 0f,
                online = isOnline,
                icon = deviceIcon,
                // MQTT không có khái niệm "lastKnownIp" như Tuya Local — thiết bị MQTT được xác
                // định bằng topic, không phải IP cụ thể trong tầng app này (broker lo phần định
                // tuyến mạng). null để bottom sheet hiển thị "Chưa xác định", nhất quán với Tuya.
                ip = null,
                battery = null,
                status = when {
                    runtimeStatus == MqttDeviceRuntimeStatus.OFFLINE -> "Mất kết nối"
                    !isOnline -> "Mất kết nối"
                    isDeviceOn -> "Đang bật"
                    else -> "Đang tắt"
                },
                room = dev.room,
                isDimmable = dev.isDimmable,
                // MqttDeviceEntity CÓ cache lastKnownLevel thật (khác Tuya) — dùng ngay, không
                // để null như bên Tuya.
                dimmerLevel = dev.lastKnownLevel
            )
        }
    }

    private fun resolveDeviceVisual(dev: com.aichatvn.agent.data.model.MqttDeviceEntity): Triple<String, String, DeviceType> {
        val name = dev.name.lowercase()
        return when {
            name.contains("bơm") || name.contains("pump")                    -> Triple("🚰", "Máy Bơm", DeviceType.PUMP)
            name.contains("giặt")                                            -> Triple("🧺", "Máy Giặt", DeviceType.SWITCH)
            name.contains("điều hòa") || name.contains("điều hoà") || name.contains("máy lạnh") -> Triple("❄️", "Điều Hòa", DeviceType.SWITCH)
            name.contains("hút bụi")                                         -> Triple("🧹", "Máy Hút Bụi", DeviceType.SWITCH)
            name.contains("tủ lạnh")                                         -> Triple("🧊", "Tủ Lạnh", DeviceType.SWITCH)
            name.contains("lò vi sóng")                                      -> Triple("♨️", "Lò Vi Sóng", DeviceType.SWITCH)
            name.contains("nóng lạnh") || name.contains("bình nóng")         -> Triple("🚿", "Bình Nóng Lạnh", DeviceType.SWITCH)
            name.contains("quạt")                                            -> Triple("🌀", "Quạt", DeviceType.SWITCH)
            name.contains("ổ cắm") || name.contains("ổ điện")                -> Triple("🔌", "Ổ Cắm", DeviceType.SWITCH)
            name.contains("công tắc")                                        -> Triple("🔘", "Công Tắc", DeviceType.SWITCH)
            else                                                             -> Triple("💡", "Đèn", DeviceType.LIGHT)
        }
    }
}

/** Kết quả 1 lần discoverDevices() — xem docstring hàm đó. */
data class MqttDiscoveryScanResult(
    val recognizedDevices: List<DiscoveredMqttDevice>,
    val fallbackTopics: List<String>
)