package com.aichatvn.agent.devices

import com.aichatvn.agent.data.MqttDeviceDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MqttDeviceController
 *
 * Tầng 3 — driver MQTT ĐẦU TIÊN, viết CHÍNH để kiểm chứng kiến trúc DeviceController sau
 * khi đã dọn Tuya (Tầng 2). File này CỐ TÌNH KHÔNG kết nối tới broker MQTT thật nào —
 * project chưa chọn thư viện MQTT client (Eclipse Paho, HiveMQ...), nên turnOn()/turnOff()
 * hiện chỉ CẬP NHẬT DB LOCAL (mqtt_devices.lastKnownState), không publish gì lên broker
 * thật. Đây là placeholder có chủ đích để kiểm tra 4 việc, KHÔNG phải để dùng thật ngoài đời:
 *
 *   1. Thiết bị "mqtt" có tự xuất hiện trong dropdown "device" (SmartActionFormSheet) không?
 *   2. "mqtt" có tự xuất hiện trong dropdown "Nguồn" của Precondition Guard không?
 *   3. Chọn nguồn "mqtt" rồi chọn attribute="state" có tự hiện đúng 2 lựa chọn true/false
 *      (giống Tuya) mà KHÔNG cần sửa DynamicOptionRegistry.kt không?
 *   4. Bật/tắt 1 thiết bị mqtt giả có tự ghi đúng world_state key "mqtt.<id>.state=..."
 *      không, để về sau lên lịch/automation dùng được ngay?
 *
 * ⚠️ TRƯỚC KHI DÙNG THẬT: thay TODO trong turnOn()/turnOff() bằng lệnh publish thật qua
 * client MQTT đã chọn, và nối getStatus() với dữ liệu subscribe được (không đọc DB tĩnh
 * như hiện tại) — xem gợi ý interface MqttClient tối thiểu ở cuối file.
 */
@Singleton
class MqttDeviceController @Inject constructor(
    private val mqttDeviceDao: MqttDeviceDao
    // TODO: inject thêm 1 MqttClient thật khi đã chọn thư viện, vd:
    //   private val mqttClient: MqttClient
) : DeviceController {

    override val protocol: DeviceProtocol = DeviceProtocol.MQTT

    // ✅ MQTT vốn mạnh ở nhận đẩy trạng thái realtime qua subscribe, không cần chủ động gọi
    // API hỏi trạng thái như Tuya (BATCH_STATUS không khai báo — dùng default tuần tự của
    // interface, đủ dùng vì đọc thẳng DB, không tốn API call nào).
    override val capabilities: Set<DeviceCapability> = setOf(
        DeviceCapability.REALTIME_STATUS
    )

    // ⚠️ Chọn khoá NGAY TỪ ĐẦU và KHÔNG đổi sau khi có người dùng thật lưu precondition với
    // khoá này — cùng ràng buộc áp dụng cho worldStateSource="tuya" (xem DeviceController.kt).
    override val worldStateSource: String = "mqtt"

    override val displayName: String = "📡 Thiết bị MQTT"

    override suspend fun turnOn(deviceId: String): DeviceActionResult {
        val entity = mqttDeviceDao.getDeviceById(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị MQTT id=$deviceId")
        return try {
            // TODO: publish thật lên broker, vd:
            //   mqttClient.publish(entity.topic, payload = "ON")
            // Hiện tại chỉ cập nhật cache local để luồng world_state/dropdown có dữ liệu
            // thật để kiểm chứng — KHÔNG có tác dụng điều khiển thiết bị vật lý nào.
            mqttDeviceDao.updateState(deviceId, state = true, online = true, timestamp = System.currentTimeMillis())
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi bật thiết bị MQTT")
        }
    }

    override suspend fun turnOff(deviceId: String): DeviceActionResult {
        val entity = mqttDeviceDao.getDeviceById(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị MQTT id=$deviceId")
        return try {
            // TODO: publish thật lên broker, vd:
            //   mqttClient.publish(entity.topic, payload = "OFF")
            mqttDeviceDao.updateState(deviceId, state = false, online = true, timestamp = System.currentTimeMillis())
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi tắt thiết bị MQTT")
        }
    }

    override suspend fun getStatus(deviceId: String): DeviceStatus? {
        // TODO: khi có mqttClient thật, đọc từ cache đã nhận qua subscribe thay vì đọc DB
        // tĩnh — DB hiện chỉ phản ánh lệnh BẬT/TẮT app tự gửi gần nhất, không phản ánh biến
        // động vật lý ngoài ý muốn (mất điện, bật tay ở công tắc...) như Tuya poll được.
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

/*
 * Gợi ý interface tối thiểu cho bước nối broker thật (KHÔNG phải code chạy được, chỉ để
 * tham khảo hình dạng — viết ở file riêng khi thật sự bắt tay làm, đừng paste thẳng vào đây):
 *
 * interface MqttClient {
 *     fun connect(brokerUrl: String, clientId: String)
 *     fun publish(topic: String, payload: String)
 *     fun subscribe(topic: String, onMessage: (payload: String) -> Unit)
 * }
 */