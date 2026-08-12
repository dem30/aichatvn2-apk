
package com.aichatvn.agent.devices

import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
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
 */
@Singleton
class MqttDeviceController @Inject constructor(
    private val mqttDeviceDao: MqttDeviceDao,
    private val configProvider: AppConfigProvider,
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

        val brokerUrl = configProvider.getString(MqttConfigKeys.BROKER_URL, "").trim()
        if (brokerUrl.isBlank()) return null // chưa cấu hình — nghiệp vụ gọi tự xử lý null

        return try {
            val clientId = "aichatvn2_" + UUID.randomUUID().toString().take(8)
            val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
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
            client = newClient
            newClient
        } catch (e: Exception) {
            logger.e("MqttDeviceController", "Kết nối broker lỗi: ${e.message}", e)
            null
        }
    }

    override suspend fun turnOn(deviceId: String): DeviceActionResult =
        publish(deviceId, payload = "ON", newState = true)

    override suspend fun turnOff(deviceId: String): DeviceActionResult =
        publish(deviceId, payload = "OFF", newState = false)

    private suspend fun publish(deviceId: String, payload: String, newState: Boolean): DeviceActionResult {
        val entity = mqttDeviceDao.getDeviceById(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị MQTT id=$deviceId")

        return withContext(Dispatchers.IO) {
            try {
                val mqttClient = getOrConnect()
                    ?: return@withContext DeviceActionResult.Failure(
                        "Chưa cấu hình broker MQTT — vào tab MQTT để nhập địa chỉ broker."
                    )
                mqttClient.publish(entity.topic, MqttMessage(payload.toByteArray()))
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
