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

    // ⚠️ Giá trị literal "tuya" — GIỮ NGUYÊN, không đổi. Đây là khoá source đã được
    // SmartSwitchSkill ghi thẳng vào world_state từ trước (WorldStateHelper.setAttribute(
    // dao, "tuya", deviceId, "state", ...)) và là tiền tố mà TuyaScreen.PreconditionGuardDialog
    // đã dùng để build các chuỗi "tuya.<id>.state=..." lưu sẵn trong DB người dùng. Đổi
    // giá trị này ở đây coi như đổi luôn "địa chỉ" mà mọi automation cũ đang trỏ tới.
    override val worldStateSource: String = "tuya"

    // ✅ MỚI: nhãn y hệt text cũ đã hardcode trong DynamicOptionRegistry.precondition_source
    // trước refactor — giữ nguyên để người dùng không thấy dropdown đổi chữ đột ngột.
    override val displayName: String = "🔌 Thiết bị đóng ngắt Tuya"

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

    // ✅ MỚI (Dimmer): tuyaManager.setLevel() đã nhận thẳng deviceId (getDeviceInfo() bên
    // trong tự thử theo id trước), KHÔNG cần dịch qua tên như turnOn/turnOff ở trên — xem
    // TuyaManager.kt. capabilities cấp controller KHÔNG khai báo DIMMABLE ở đây vì không
    // phải mọi thiết bị Tuya đều là dimmer (khác LOCAL_CONTROL/BATCH_STATUS vốn đúng cho mọi
    // thiết bị Tuya không phân biệt) — nơi gọi (SmartSwitchSkill) tự kiểm tra cột
    // isDimmable của từng thiết bị trước khi gọi setLevel(), không dựa vào capabilities
    // chung của cả controller cho việc này.
    override suspend fun setLevel(deviceId: String, percent: Int): DeviceActionResult {
        return try {
            tuyaManager.setLevel(deviceId, percent)
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi chỉnh mức độ")
        }
    }

    override suspend fun isDeviceDimmable(deviceId: String): Boolean {
        return tuyaDeviceDao.getDeviceById(deviceId)?.isDimmable ?: false
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

    // ✅ MỚI: dùng riêng cho vòng poll 5s Dashboard — delegate thẳng
    // tuyaManager.getStatusBatchLocalOnly() (thuần Local, không rơi Cloud, xem ghi chú tại
    // TuyaManager.kt và DeviceController.kt). Trả về đúng khoá deviceId (TuyaManager đã
    // trả sẵn theo id, không cần dịch qua entity như getStatusBatch() ở trên).
    override suspend fun getStatusBatchLocalOnly(deviceIds: List<String>): Map<String, Boolean> {
        return tuyaManager.getStatusBatchLocalOnly(deviceIds)
    }

    // ✅ Điểm truy vấn tuyaDeviceDao.getAllDevices() DUY NHẤT còn lại trong toàn bộ app sau
    // khi hoàn tất Tầng 2 — DynamicOptionRegistry và mọi nơi khác chỉ được gọi qua
    // deviceRegistry.all().flatMap { it.listDevices() }, không tự query DAO này nữa.
    override suspend fun listDevices(): List<DeviceSummary> {
        return tuyaDeviceDao.getAllDevices().map { entity ->
            DeviceSummary(id = entity.id, displayName = entity.name)
        }
    }

    // ✅ MỚI: delegate qua tuyaManager.deleteDevice() — nhất quán với turnOn/turnOff/
    // getStatus ở trên (lớp này CHỈ bọc, không tự ý thay logic thật). Nếu TuyaManager.
    // deleteDevice() có gọi thêm API Cloud để huỷ liên kết thiết bị (ngoài xoá DB local),
    // logic đó vẫn chạy nguyên vẹn — file này không biết và không cần biết bên trong.
    override suspend fun deleteDevice(deviceId: String): DeviceActionResult {
        return try {
            tuyaManager.deleteDevice(deviceId)
            DeviceActionResult.Success
        } catch (e: Exception) {
            DeviceActionResult.Failure(e.message ?: "Lỗi không xác định khi xoá thiết bị")
        }
    }

    private suspend fun resolveName(deviceId: String): String? {
        return tuyaDeviceDao.getDeviceById(deviceId)?.name
    }
}