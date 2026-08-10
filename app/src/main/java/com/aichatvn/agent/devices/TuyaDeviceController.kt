package com.aichatvn.agent.devices

import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.skills.TuyaManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TuyaDeviceController
 *
 * Lớp bọc MỎNG quanh TuyaManager hiện có để nó tuân theo DeviceController — KHÔNG sửa
 * TuyaManager.kt. Mọi logic thật (Cloud API, Local UDP/TCP, retry, fallback gateway...)
 * vẫn nằm nguyên trong TuyaManager; file này chỉ dịch giữa deviceId (khoá ổn định DB dùng
 * để audit/registry) và deviceName (khoá mà các hàm public của TuyaManager đang nhận).
 *
 * Vì sao cần dịch id → name: TuyaManager.turnOn()/turnOff()/getStatus() hiện nhận
 * `deviceName` (xem TuyaManager.kt dòng ~731-919), trong khi DeviceController chuẩn hoá
 * theo `deviceId` (ổn định, không đổi khi người dùng đổi tên hiển thị trong app Tuya).
 * Việc tra cứu id → name đi qua TuyaDeviceDao — nếu không tìm thấy entity, coi là Failure
 * thay vì crash.
 */
@Singleton
class TuyaDeviceController @Inject constructor(
    private val tuyaManager: TuyaManager,
    private val tuyaDeviceDao: TuyaDeviceDao
) : DeviceController {

    override val protocol: DeviceProtocol = DeviceProtocol.TUYA_CLOUD

    override val capabilities: Set<DeviceCapability> = setOf(
        DeviceCapability.LOCAL_CONTROL,
        DeviceCapability.BATCH_STATUS
    )

    override suspend fun turnOn(deviceId: String): DeviceActionResult {
        val name = resolveName(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị Tuya id=$deviceId")
        return try {
            tuyaManager.turnOn(name)
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi bật thiết bị")
        }
    }

    override suspend fun turnOff(deviceId: String): DeviceActionResult {
        val name = resolveName(deviceId)
            ?: return DeviceActionResult.Failure("Không tìm thấy thiết bị Tuya id=$deviceId")
        return try {
            tuyaManager.turnOff(name)
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi tắt thiết bị")
        }
    }

    override suspend fun getStatus(deviceId: String): DeviceStatus? {
        val entity = tuyaDeviceDao.getDeviceById(deviceId) ?: return null
        return try {
            val isOn = tuyaManager.getStatus(entity.name)
            DeviceStatus(isOn = isOn, isOnline = entity.online)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getStatusBatch(deviceIds: List<String>): Map<String, DeviceStatus> {
        // TuyaManager.getStatusBatch() nhận id trực tiếp (khác turnOn/turnOff/getStatus
        // nhận name) — xem TuyaManager.kt dòng 822. Tận dụng thẳng, không cần dịch tên.
        val onlineByName = tuyaManager.getStatusBatch(deviceIds)
        val entities = deviceIds.mapNotNull { tuyaDeviceDao.getDeviceById(it) }
        return entities.associate { entity ->
            val isOn = onlineByName[entity.id] ?: false
            entity.id to DeviceStatus(isOn = isOn, isOnline = entity.online)
        }
    }

    private suspend fun resolveName(deviceId: String): String? {
        return tuyaDeviceDao.getDeviceById(deviceId)?.name
    }
}
