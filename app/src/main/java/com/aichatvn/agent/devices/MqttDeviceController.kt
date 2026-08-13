package com.aichatvn.agent.devices

import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
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
 * ✅ MỚI (track Cloud Broker V1, xem MQTT_CLOUD_BROKER_PLAN.md mục 0.4a): tách "kết nối tới đâu"
 * ra khỏi MqttDeviceController — controller chỉ biết gọi connect(), không tự quyết định là nối
 * Cloud hay (sau này) nối Embedded Broker chạy trên Camera Node. Ở V1 chỉ có 1 implementation
 * (CloudMqttBrokerProvider). Khi làm Embedded Broker (V2, hiện đang HOÃN), thêm
 * EmbeddedCameraNodeBrokerProvider cùng interface này — KHÔNG cần sửa lại MqttDeviceController.
 */
interface MqttBrokerProvider {
    val brokerType: BrokerType
    suspend fun connect(): MqttClient?
}

enum class BrokerType { CLOUD, EMBEDDED_CAMERA_NODE }

/**
 * CloudMqttBrokerProvider
 *
 * Implementation duy nhất ở V1 — đọc cấu hình broker cloud (HiveMQ/EMQX...) đã lưu qua
 * AppConfigProvider, dựng MqttClient + connect. Logic thực chất y hệt getOrConnect() cũ trước
 * khi tách, chỉ chuyển chỗ ở.
 */
@Singleton
class CloudMqttBrokerProvider @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val logger: Logger,
) : MqttBrokerProvider {

    override val brokerType: BrokerType = BrokerType.CLOUD

    /**
     * buildBrokerUri
     *
     * Nếu người dùng dán nguyên 1 URI đầy đủ (có scheme "tcp://"/"ssl://" và port, vd dán thẳng
     * "ssl://xxxxx.s1.eu.hivemq.cloud:8883" theo đúng gợi ý trong MqttBrokerConfigDialog) —
     * DÙNG NGUYÊN, không đụng vào. Chỉ tự ráp URI từ PORT/USE_TLS khi Broker URL đã lưu là bare
     * host (không có "://") — tránh ghi đè lựa chọn port/scheme người dùng đã cố ý chọn khác mặc
     * định (mục 1.2 kế hoạch: "Không tự đặt mặc định nếu người dùng dán URL đã có sẵn scheme").
     */
    private suspend fun buildBrokerUri(rawBrokerUrl: String): String {
        if (rawBrokerUrl.contains("://")) return rawBrokerUrl

        val useTls = configProvider.getString(MqttConfigKeys.USE_TLS, "true").toBooleanStrictOrNull() ?: true
        val port = configProvider.getString(MqttConfigKeys.PORT, "").trim()
            .toIntOrNull() ?: if (useTls) 8883 else 1883
        val scheme = if (useTls) "ssl" else "tcp"
        return "$scheme://$rawBrokerUrl:$port"
    }

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

/**
 * MqttBrokerModule
 *
 * ⚠️ BẮT BUỘC: MqttBrokerProvider là interface — khác với các class cụ thể có @Singleton +
 * @Inject constructor (Hilt tự resolve được), interface CẦN khai báo rõ implementation nào qua
 * @Binds, nếu không build sẽ lỗi "Cannot provide MqttBrokerProvider" ở bước Hilt xử lý
 * (kspDebugKotlin), không phải lỗi compileDebugKotlin — dễ nhầm hướng debug nếu không để ý.
 *
 * Đặt @InstallIn(SingletonComponent::class) — giữ đúng scope với @Singleton của
 * CloudMqttBrokerProvider và MqttDeviceController. Nếu project có sẵn 1 module chung khác
 * (vd DeviceModule/AppModule) đã @InstallIn(SingletonComponent::class), NÊN gộp @Binds này vào
 * đó thay vì để module riêng — chỉ tách ra đây vì không có sẵn file module nào được cung cấp để
 * biết chính xác nên gộp vào đâu. Khi ghép vào project thật, cân nhắc di chuyển đoạn @Binds bên
 * dưới sang đúng module hiện có, xoá phần khai báo module thừa ở đây.
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class MqttBrokerModule {
    @dagger.Binds
    abstract fun bindMqttBrokerProvider(impl: CloudMqttBrokerProvider): MqttBrokerProvider
}

/**
 * MqttDeviceController
 *
 * (Giai đoạn 2) Đã nối broker MQTT thật qua Eclipse Paho — thư viện ĐÃ CÓ SẴN trong
 * build.gradle.kts từ trước (org.eclipse.paho.client.mqttv3:1.2.5), không thêm dependency mới.
 * Dùng bản CORE (`MqttClient`, đồng bộ/blocking), KHÔNG dùng `org.eclipse.paho.android.service`
 * (bản bọc Android Service, không cần thiết) — gọi trong withContext(Dispatchers.IO), đúng
 * phong cách project đang dùng cho các lệnh gọi mạng blocking khác (vd okhttp trong
 * CameraCapabilityProber, TuyaLocalController).
 *
 * Quản lý kết nối: 1 MqttClient singleton lười khởi tạo, dùng Mutex tránh 2 coroutine cùng
 * connect — cùng pattern CallSkill.getOrCreateMyDeviceCode() đã dùng.
 *
 * ✅ SỬA (track Cloud Broker V1): việc "kết nối tới đâu" giờ uỷ quyền cho MqttBrokerProvider
 * (đang inject CloudMqttBrokerProvider) — controller chỉ giữ vòng đời client (singleton + Mutex),
 * không tự biết chi tiết broker URL/port/TLS nữa.
 */
@Singleton
class MqttDeviceController @Inject constructor(
    private val mqttDeviceDao: MqttDeviceDao,
    private val brokerProvider: MqttBrokerProvider,
    private val logger: Logger,
) : DeviceController {

    override val protocol: DeviceProtocol = DeviceProtocol.MQTT

    override val capabilities: Set<DeviceCapability> = setOf(
        DeviceCapability.REALTIME_STATUS
    )

    override val worldStateSource: String = "mqtt"

    override val displayName: String = "📡 Thiết bị MQTT"

    private val connectMutex = Mutex()
    @Volatile private var client: MqttClient? = null

    /**
     * Đổi cấu hình broker giữa chừng (từ MqttViewModel, sau khi người dùng lưu ở tab MQTT) —
     * ngắt kết nối cũ ngay, lần gọi turnOn/turnOff tiếp theo sẽ tự connect lại theo config mới
     * thay vì lỡ dùng nhầm kết nối tới broker cũ đã đổi.
     */
    fun invalidateConnection() {
        try {
            client?.takeIf { it.isConnected }?.disconnect()
        } catch (e: Exception) {
            logger.d("MqttDeviceController", "disconnect() khi đổi broker: ${e.message}")
        } finally {
            client = null
        }
    }

    private suspend fun getOrConnect(): MqttClient? = connectMutex.withLock {
        client?.takeIf { it.isConnected }?.let { return it }
        val newClient = brokerProvider.connect() ?: return null
        client = newClient
        newClient
    }

    /**
     * scanTopics ("Quét nhanh")
     *
     * MQTT thuần KHÔNG có API "liệt kê thiết bị" chuẩn hoá (khác Tuya Cloud có thể query danh
     * sách qua tài khoản) — broker chỉ route message theo topic, không biết gì về "thiết bị".
     * Cách khả thi duy nhất không phụ thuộc vendor: LẮNG NGHE THỤ ĐỘNG mọi topic có publish
     * trong lúc quét, KHÔNG chủ động "hỏi" broker. Vì vậy:
     * - Thiết bị/gateway PHẢI tự publish trong đúng cửa sổ [timeoutMs] mới xuất hiện (vd
     *   Tasmota với Home Assistant Discovery bật, Zigbee2MQTT bridge, ESPHome birth message,
     *   hoặc bất kỳ firmware nào publish định kỳ/khi khởi động).
     * - Danh sách rỗng KHÔNG phải lỗi — chỉ là không có gì publish trong lúc quét. Nơi gọi
     *   (MqttViewModel) cần tự fallback về AddMqttDeviceDialog (nhập tay tên+topic) như
     *   phương án dự phòng, đúng như luồng "Kết nối" hiện có.
     *
     * Dùng subscribe("#", ...) — một số broker (đặc biệt Cloud có ACL giới hạn theo tài khoản,
     * vd HiveMQ Cloud free tier) có thể TỪ CHỐI subscribe wildcard toàn cục này. Bắt riêng
     * Exception ở đây, trả về rỗng thay vì để lỗi rơi ra ngoài — coi như một kết quả "quét
     * không ra gì" bình thường, không phải sự cố kết nối.
     *
     * Paho core MqttClient (bản đồng bộ đang dùng trong file này) VẪN hỗ trợ
     * subscribe(topicFilter, qos, IMqttMessageListener) — không cần đổi sang MqttAsyncClient.
     * Callback messageArrived chạy trên thread nội bộ của Paho (không phải thread gọi hàm
     * này), nên dùng ConcurrentHashMap.newKeySet() để gom topic an toàn giữa các thread, thay
     * vì MutableSet/HashSet thường (không thread-safe, có thể mất dữ liệu hoặc crash ngầm khi
     * nhiều topic tới dồn dập cùng lúc).
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
            // Broker chặn subscribe "#" (ACL) — không phải lỗi kết nối, coi như quét ra rỗng.
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
            // LUÔN unsubscribe dù delay bị huỷ giữa chừng (vd người dùng thoát màn hình sớm)
            // — tránh rò rỉ subscription "#" chạy nền vô thời hạn trên kết nối singleton dùng
            // chung cho cả turnOn/turnOff sau đó.
            try {
                mqttClient.unsubscribe("#")
            } catch (e: Exception) {
                logger.d("MqttDeviceController", "scanTopics(): unsubscribe('#') lỗi (bỏ qua): ${e.message}")
            }
        }

        found.toList().sorted()
    }

    override suspend fun turnOn(deviceId: String): DeviceActionResult =
        publish(deviceId, newState = true)

    override suspend fun turnOff(deviceId: String): DeviceActionResult =
        publish(deviceId, newState = false)

    /**
     * ✅ SỬA (track Cloud Broker V1): payload không còn hardcode "ON"/"OFF" ở turnOn/turnOff nữa
     * — mỗi thiết bị có thể có onPayload/offPayload riêng (mục 3 kế hoạch, vd thiết bị theo
     * convention khác dùng "1"/"0" hoặc "true"/"false"). Cần đọc entity TRƯỚC mới biết payload
     * đúng, nên publish() giờ nhận `newState: Boolean` thay vì `payload: String` cố định, tự
     * chọn entity.onPayload/entity.offPayload sau khi đã lấy được entity.
     */
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
                // ✅ SỬA (track Cloud Broker V1): dùng commandTopic (cột mới, mọi thiết bị đều
                // có giá trị nhờ MIGRATION_23_24 copy từ topic cũ) thay vì topic — topic giữ lại
                // trong Entity chỉ để tương thích hiển thị UI, KHÔNG còn là nguồn thật cho publish.
                mqttClient.publish(entity.commandTopic, MqttMessage(payload.toByteArray()))
                // ✅ Cập nhật cache local ngay sau khi publish thành công — getStatus() vẫn đọc
                // DB tĩnh (subscribe nhận trạng thái realtime CHƯA làm ở bước này, xem TODO
                // cuối file), nên lệnh app tự gửi cần tự phản ánh vào DB để UI không lệch.
                mqttDeviceDao.updateState(
                    deviceId, state = newState, online = true, timestamp = System.currentTimeMillis()
                )
                DeviceActionResult.Success
            } catch (e: Exception) {
                logger.e("MqttDeviceController", "publish($deviceId) lỗi: ${e.message}", e)
                DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi gửi lệnh MQTT")
            }
        }
    }

    override suspend fun getStatus(deviceId: String): DeviceStatus? {
        // TODO (chưa làm ở Giai đoạn 2): nối subscribe() thật để cache trạng thái realtime từ
        // broker thay vì chỉ đọc lại đúng lệnh app tự gửi gần nhất — DB hiện KHÔNG phản ánh
        // biến động vật lý ngoài ý muốn (mất điện, bật tay ở công tắc vật lý...).
        val entity = mqttDeviceDao.getDeviceById(deviceId) ?: return null
        return DeviceStatus(isOn = entity.lastKnownState, isOnline = entity.online)
    }

    override suspend fun listDevices(): List<DeviceSummary> {
        return mqttDeviceDao.getAllDevices().map { entity ->
            DeviceSummary(id = entity.id, displayName = entity.name)
        }
    }

    override suspend fun deleteDevice(deviceId: String): DeviceActionResult {
        return try {
            mqttDeviceDao.deleteDevice(deviceId)
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi xoá thiết bị MQTT")
        }
    }
}