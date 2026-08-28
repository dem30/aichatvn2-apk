package com.aichatvn.agent.devices

import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceType
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

    // ✅ MỚI (Nhất quán Dashboard): chuyển nguyên logic từ SmartSwitchSkill.getDashboardNodes()
    // bản cũ (phần thuần Tuya) sang đây — bao gồm cả resolveDeviceVisual() (giờ private local,
    // KHÔNG dùng chung với MqttDeviceController vì đọc TuyaDeviceEntity cụ thể). PHẦN BỊ CẮT
    // khỏi bản gốc: gán x/y layout và detectAndBroadcastPhysicalChange() — 2 việc đó dùng
    // chung mọi giao thức, SmartSwitchSkill.getDashboardNodes() làm SAU khi gộp kết quả từ
    // tất cả controller, tránh mỗi driver phải tự biết configProvider layout_x_*/layout_y_*.
    //
    // pluginId hardcode "smart_switch" — ĐÚNG convention đã dùng khắp codebase (MqttScreen.kt,
    // TuyaScreen.kt đều hardcode y hệt chuỗi này khi gọi agentKernel.executePluginAction），
    // KHÔNG phải làm tắt: đổi tên plugin xử lý action đóng ngắt là thay đổi lớn ảnh hưởng toàn
    // hệ thống, nằm ngoài phạm vi việc thống nhất Dashboard này.
    override suspend fun buildDashboardNodes(): List<DeviceNode> {
        val tuyaDevices = tuyaDeviceDao.getAllDevices()
        val onlineDeviceIds = tuyaDevices.filter { it.online }.map { it.id }
        val batchStates = try {
            getStatusBatch(onlineDeviceIds)
        } catch (e: Exception) {
            emptyMap()
        }

        return tuyaDevices.map { dev ->
            val isOnline = dev.online
            val isDeviceOn = if (isOnline) batchStates[dev.id]?.isOn ?: false else false
            val (deviceIcon, deviceLabel, deviceType) = resolveDeviceVisual(dev)

            DeviceNode(
                id = dev.id,
                name = dev.name,
                type = deviceType,
                pluginId = "smart_switch",
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
                // ✅ SỬA (nhất quán, dứt điểm dữ liệu giả): trước đây "192.168.1.${50+index}"
                // — chuỗi BỊA theo thứ tự index, không phải IP thật. Đọc thẳng lastKnownIp đã
                // lưu (từ lần điều khiển Local gần nhất — xem TuyaManager fetchLocalKey côi
                // discoverIp()) — null nếu chưa từng điều khiển Local (thiết bị mới, hoặc luôn
                // đi qua Cloud). Bottom sheet Dashboard cần tự hiển thị "Chưa xác định" khi null,
                // KHÔNG bịa số để lấp chỗ trống.
                ip = dev.lastKnownIp ?: "",
                battery = null,
                status = if (isOnline) {
                    if (isDeviceOn) "Đang bật" else "Đang tắt"
                } else {
                    "Mất kết nối"
                },
                // ✅ SỬA (nhất quán, dứt điểm room hardcode): đọc thẳng cột room đã lưu (Entity.kt
                // + TuyaScreen dialog "Sửa thiết bị" mới) — null nếu chưa đặt tên, KHÔNG hardcode
                // "Phòng chung" nữa. Nơi nhóm hiển thị (DashboardScreen) tự hiển thị "Chưa phân
                // loại" khi gặp null.
                room = dev.room,
                isDimmable = dev.isDimmable,
                dimmerLevel = null
            )
        }
    }

    private fun resolveDeviceVisual(dev: com.aichatvn.agent.data.model.TuyaDeviceEntity): Triple<String, String, DeviceType> {
        val name = dev.name.lowercase()
        val category = dev.category.lowercase()
        return when {
            name.contains("bơm") || name.contains("pump")                    -> Triple("🚰", "Máy Bơm", DeviceType.PUMP)
            name.contains("giặt")                                            -> Triple("🧺", "Máy Giặt", DeviceType.SWITCH)
            name.contains("điều hòa") || name.contains("điều hoà") || name.contains("máy lạnh") -> Triple("❄️", "Điều Hòa", DeviceType.SWITCH)
            name.contains("hút bụi")                                         -> Triple("🧹", "Máy Hút Bụi", DeviceType.SWITCH)
            name.contains("tủ lạnh")                                         -> Triple("🧊", "Tủ Lạnh", DeviceType.SWITCH)
            name.contains("lò vi sóng")                                      -> Triple("♨️", "Lò Vi Sóng", DeviceType.SWITCH)
            name.contains("nóng lạnh") || name.contains("bình nóng")         -> Triple("🚿", "Bình Nóng Lạnh", DeviceType.SWITCH)
            category in setOf("fs", "fsd") || name.contains("quạt")          -> Triple("🌀", "Quạt", DeviceType.SWITCH)
            category in setOf("cz", "pc") || name.contains("ổ cắm") || name.contains("ổ điện") -> Triple("🔌", "Ổ Cắm", DeviceType.SWITCH)
            category == "kg" || name.contains("công tắc")                    -> Triple("🔘", "Công Tắc", DeviceType.SWITCH)
            else                                                             -> Triple("💡", "Đèn", DeviceType.LIGHT)
        }
    }
}