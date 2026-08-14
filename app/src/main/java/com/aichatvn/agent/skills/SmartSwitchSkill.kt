package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.devices.DeviceActionResult
import com.aichatvn.agent.devices.DeviceController
import com.aichatvn.agent.devices.DeviceProtocol
import com.aichatvn.agent.devices.DeviceSummary
import com.aichatvn.agent.devices.DeviceRegistry as DeviceControlRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartSwitchSkill @Inject constructor(
    // ✅ SỬA (Tầng 2b — đồng bộ kiến trúc DeviceController): giữ lại TuyaManager CHỈ để
    // gọi scanDevices() (dò thiết bị mới từ tài khoản Tuya Cloud). "Quét/khám phá thiết bị"
    // là hành vi khác hẳn "điều khiển 1 thiết bị đã biết" — DeviceController KHÔNG có (và
    // cố tình không có) method này, vì MQTT không có khái niệm "quét tài khoản Cloud" giống
    // Tuya (xem docstring DeviceController.kt). Mọi hành động ĐIỀU KHIỂN thật
    // (turnOn/turnOff/getStatus/getStatusBatch) đã chuyển sang deviceControlRegistry bên dưới
    // — đây là nơi DUY NHẤT trong file này còn gọi thẳng TuyaManager.
    private val tuyaManager: TuyaManager,
    private val database: AppDatabase,
    // ✅ SỬA: đổi tên rõ ràng — đây là registry NODE cho sơ đồ Dashboard (DeviceNode, toạ độ,
    // trạng thái hiển thị), hoàn toàn khác deviceControlRegistry bên dưới. Đổi tên biến để
    // không còn 2 thứ trùng tên "DeviceRegistry" gây nhầm lẫn khi đọc code — hành vi giữ
    // nguyên 100%, chỉ đổi cách gọi trong file.
    private val dashboardDeviceRegistry: com.aichatvn.agent.ui.dashboard.DeviceRegistry,
    private val configProvider: AppConfigProvider,
    // ✅ MỚI: registry thật của kiến trúc DeviceController (xem devices/DeviceRegistry.kt) —
    // nơi DUY NHẤT tra cứu driver điều khiển thiết bị theo protocol, thay cho việc gọi thẳng
    // TuyaManager.turnOn/turnOff/getStatus/getStatusBatch như trước.
    private val deviceControlRegistry: DeviceControlRegistry,
    // ✅ MỚI: fallback gửi lệnh qua Gateway → Camera Node ở nhà khi Local thất bại và đang bật
    // Chế độ Local-only (LOCAL_ONLY_MODE_ENABLED) — xem handleSet().
    private val deviceCommandGatewayClient: DeviceCommandGatewayClient,
    // ✅ MỚI (Household Event Broadcast): publish tuya_state_change khi CHÍNH máy này vừa
    // điều khiển Tuya thành công trực tiếp (Local hoặc Cloud, KHÔNG qua Gateway relay) — xem
    // handleSet(). Role-agnostic, không gate DEVICE_ROLE (khác với vòng poll
    // syncTuyaDeviceStates() bên WebhookGatewayService).
    private val householdEventPublisher: HouseholdEventPublisher,
    logger: Logger
) : BaseSkill("smart_switch", "Điều khiển thiết bị đóng ngắt", logger), Plugin {

    // ✅ MỚI: 1 điểm tra cứu duy nhất cho controller Tuya trong file này — tránh lặp lại
    // deviceControlRegistry.controllerFor(DeviceProtocol.TUYA_CLOUD) ở 3-4 chỗ khác nhau.
    // CHỈ dùng cho handleScan() (quét/khám phá thiết bị mới — hành vi cố tình chỉ có ở Tuya,
    // xem docstring DeviceController.kt). handleSet()/handleStatus() KHÔNG dùng hàm này nữa —
    // xem resolveDevice() bên dưới.
    private fun tuyaController() = deviceControlRegistry.controllerFor(DeviceProtocol.TUYA_CLOUD)

    /**
     * ⚠️ SỬA LỖI KIẾN TRÚC: trước đây handleSet()/handleStatus() chỉ tra
     * database.tuyaDeviceDao() và chỉ gọi tuyaController() — khiến plugin "smart_switch" (tên
     * gọi ngụ ý dùng chung MỌI giao thức thiết bị đóng ngắt) thực chất chỉ điều khiển được
     * Tuya. Thiết bị MQTT gọi qua đây (MqttViewModel.toggleDevice() →
     * agentKernel.executePluginAction("smart_switch","set",...)) luôn nhận
     * "Không tìm thấy thiết bị" dù đã thêm đúng.
     *
     * Sửa lại đúng theo thiết kế gốc của DeviceController.kt: tra thiết bị theo id HOẶC tên
     * hiển thị bằng cách lặp deviceControlRegistry.all() — CÙNG pattern
     * DynamicOptionRegistry.kt (case "device"/"precondition_source") đã dùng — để thêm
     * protocol mới sau này (Zigbee2MQTT, ESPHome...) không phải sửa lại file này lần nữa.
     */
    private suspend fun resolveDevice(deviceKey: String): Pair<DeviceSummary, DeviceController>? {
        for (controller in deviceControlRegistry.all()) {
            val devices = try { controller.listDevices() } catch (e: Exception) { emptyList() }
            val match = devices.find { it.id == deviceKey }
                ?: devices.find { it.displayName.equals(deviceKey, ignoreCase = true) }
            if (match != null) return match to controller
        }
        return null
    }

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true),
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt thiết bị đóng ngắt (đèn, ổ cắm, quạt, máy bơm, máy giặt, điều hòa, máy giặt, robot hút bụi, tủ lạnh...)",
                examples = listOf(
                    "bật đèn", "tắt đèn",
                    "bật ổ cắm", "tắt ổ cắm",
                    "bật quạt", "tắt quạt",
                    "bật máy bơm", "tắt máy bơm"
                ),
                exampleOverrides = mapOf(
                    "bật đèn" to mapOf("state" to true),
                    "tắt đèn" to mapOf("state" to false),
                    "bật ổ cắm" to mapOf("state" to true),
                    "tắt ổ cắm" to mapOf("state" to false),
                    "bật quạt" to mapOf("state" to true),
                    "tắt quạt" to mapOf("state" to false),
                    "bật máy bơm" to mapOf("state" to true),
                    "tắt máy bơm" to mapOf("state" to false)
                ),
                tags = listOf(
                    "light", "switch", "socket", "fan", "pump", "relay", "device",
                    "máy giặt", "điều hòa", "điều hoà", "máy lạnh",
                    "máy hút bụi", "robot hút bụi", "tủ lạnh",
                    "lò vi sóng", "bình nóng lạnh", "quạt trần", "quạt cây", "máy sưởi"
                ),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device"),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true, "boolean")
                ),
                // ✅ MỚI (Tuần 5 - Phase 5): Ràng buộc thế giới thực mặc định để trống cho người dùng lên lịch tự đặt
                requiredWorldState = ""
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái hiện tại của thiết bị đóng ngắt",
                examples = listOf(
                    "trạng thái đèn", "kiểm tra đèn",
                    "trạng thái ổ cắm", "kiểm tra ổ cắm",
                    "trạng thái quạt", "kiểm tra quạt",
                    "trạng thái máy bơm", "kiểm tra máy bơm"
                ),
                tags = listOf("status", "query", "sensor"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device")
                )
            ),
            PluginAction(
                name = "scan",
                description = "Quét các thiết bị đóng ngắt thông minh (Tuya) trong mạng",
                examples = listOf("quét thiết bị đèn", "tìm đèn tuya mới", "quét thiết bị tuya", "tìm thiết bị mới"),
                tags = listOf("discovery", "scan", "network"),
                parameters = emptyList()
            )
        )
    )

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

    override suspend fun getDashboardNodes(): List<DeviceNode> = withContext(Dispatchers.IO) {
        // ✅ SỬA (bug "Refresh vẫn báo Mất kết nối"): trước đây hàm này chỉ đọc thẳng
        // dev.online từ Room — là giá trị cache được ghi lần cuối bởi scanDevices() (nền,
        // 10 phút/lần) hoặc bởi setDeviceState() khi bật/tắt. Bấm nút 🔄 Refresh trên
        // Dashboard KHÔNG hề gọi Tuya Cloud, chỉ đọc lại y nguyên cache đó — nên nếu cache
        // đang là false (mới mở app, chưa tới chu kỳ sync nền) thì Refresh mãi vẫn báo
        // "Mất kết nối" dù thiết bị vật lý đang online thật. Chỉ khi người dùng bật/tắt tay
        // (gọi API thật qua setDeviceState) thì cache mới được cập nhật đúng.
        //
        // Giờ chủ động sync cache trước khi build node, nhưng chỉ khi có ít nhất 1 thiết bị
        // đang bị đánh dấu offline trong cache — tránh tốn thêm 1 API call Tuya mỗi lần mở
        // Dashboard/chuyển tab khi mọi thứ đã online sẵn (trường hợp phổ biến nhất).
        try {
            val hasOfflineCached = database.tuyaDeviceDao().getAllDevices().any { !it.online }
            if (hasOfflineCached) {
                tuyaManager.scanDevices()
            }
        } catch (e: Exception) {
            // Không có UID/credentials, mất mạng, v.v. — bỏ qua, vẫn hiển thị Dashboard
            // với dữ liệu cache hiện có thay vì làm crash/treo màn hình.
            logger.e("SmartSwitchSkill", "Không sync được trạng thái online từ Tuya Cloud khi làm mới Dashboard: ${e.message}")
        }

        val tuyaDevices = database.tuyaDeviceDao().getAllDevices()

        // ✅ SỬA (tối ưu quota API): trước đây mỗi node async bên dưới tự gọi
        // tuyaManager.getStatus(dev.name) RIÊNG (N call cho N thiết bị, mỗi lần Dashboard
        // build/refresh/chuyển tab). Giờ gọi 1 lần getStatusBatch() cho TẤT CẢ thiết bị đang
        // online trước vòng lặp, các node bên dưới chỉ tra cứu trong map có sẵn — 1 call
        // (hoặc ceil(N/20) nếu >20 thiết bị) thay vì N call mỗi lần mở/làm mới Dashboard.
        val onlineDeviceIds = tuyaDevices.filter { it.online }.map { it.id }
        // ✅ SỬA: gọi qua controller.getStatusBatch() (trả về Map<String, DeviceStatus>)
        // thay vì tuyaManager.getStatusBatch() thẳng (trả về Map<String, Boolean>) — cùng 1
        // API batch bên dưới (TuyaDeviceController.getStatusBatch() vẫn gọi
        // tuyaManager.getStatusBatch() y hệt trước), chỉ đổi kiểu dữ liệu bọc ngoài.
        val batchStates = try {
            tuyaController()?.getStatusBatch(onlineDeviceIds) ?: emptyMap()
        } catch (e: Exception) {
            logger.e("SmartSwitchSkill", "Lỗi lấy trạng thái hàng loạt cho Dashboard: ${e.message}")
            emptyMap()
        }

        val deferredNodes = tuyaDevices.mapIndexed { index, dev ->
            async {
                val defaultX = 40f + (index % 2) * 160f
                val defaultY = 200f + (index / 2) * 160f

                val savedX = configProvider.getFloat("layout_x_${dev.id}", -1f)
                val savedY = configProvider.getFloat("layout_y_${dev.id}", -1f)
                val finalX = if (savedX >= 0f) savedX else defaultX
                val finalY = if (savedY >= 0f) savedY else defaultY

                val isOnline = dev.online 

                // Thiếu key trong batchStates (offline, lỗi call, hoặc không dò được switch
                // code) -> coi là tắt để hiển thị an toàn, giống hành vi cũ khi getStatus() lỗi.
                val isDeviceOn = if (isOnline) {
                    batchStates[dev.id]?.isOn ?: false
                } else {
                    false
                }

                // ✅ MỚI: Dashboard vừa hỏi trạng thái BẬT/TẮT thật từ Tuya Cloud ở trên (miễn phí,
                // không tốn thêm request nào) — tranh thủ ghi luôn vào world_state ngay tại đây,
                // thay vì chỉ trông chờ vòng lặp nền WebhookGatewayService.syncTuyaDeviceStates()
                // (10 phút/lần) mới cập nhật. Nhờ vậy HouseManagerSkillImpl.startWorldStateReactiveObserver()
                // (đang lắng nghe world_state đổi để kích hoạt Nhóm kịch bản) phản ứng gần như NGAY
                // khi người dùng mở app/bấm 🔄 làm mới Dashboard, thay vì phải đợi tới 10 phút.
                // Chỉ ghi khi thực sự đổi (so với world_state hiện có) — tránh kích hoạt observer
                // 2 lần thừa cho cùng 1 lần thiết bị đổi trạng thái (vòng lặp nền vẫn sẽ tự no-op
                // vì lúc đó world_state đã khớp rồi).
                if (isOnline) {
                    detectAndBroadcastPhysicalChange(dev.id, dev.name, isDeviceOn)
                }

                val (deviceIcon, deviceLabel, deviceType) = resolveDeviceVisual(dev)

                DeviceNode(
                    id = dev.id,
                    name = dev.name,
                    type = deviceType,
                    pluginId = manifest.id,
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
                    x = finalX,
                    y = finalY,
                    online = isOnline,
                    icon = deviceIcon,
                    ip = "192.168.1.${50 + index}",
                    battery = null,
                    status = if (isOnline) {
                        if (isDeviceOn) "Đang bật" else "Đang tắt"
                    } else {
                        "Mất kết nối"
                    },
                    room = "Phòng chung" 
                )
            }
        }
        deferredNodes.awaitAll()
    }

    // ✅ MỚI: tách từ khối detect-lệch vốn nằm trong getDashboardNodes() — dùng chung cho cả
    // getDashboardNodes() (build đầy đủ node cho UI) lẫn pollLightweightStates() (vòng poll 5s
    // ở DashboardViewModel, chỉ cần biết ON/OFF, không build lại x/y/room/scene). So sánh với
    // world_state hiện có; nếu lệch, ghi world_state + eventLog NHƯ CŨ, và giờ publish thêm qua
    // household broadcast — trước đây khối này chỉ ghi world_state cho CHÍNH máy này, các máy
    // khác trong nhà không hề biết cho tới vòng poll nền 30 phút (WebhookGatewayService).
    private suspend fun detectAndBroadcastPhysicalChange(deviceId: String, deviceName: String, isDeviceOn: Boolean) {
        try {
            // ✅ SỬA: dùng controller.worldStateSource thay vì literal "tuya" —
            // với Tuya giá trị vẫn y hệt "tuya" (xem TuyaDeviceController.kt),
            // nên KHÔNG đổi hành vi/key nào đã lưu trong DB. Chỉ đổi nguồn sự
            // thật: nếu sau này driver Tuya đổi worldStateSource (không nên, xem
            // cảnh báo trong DeviceController.kt), chỗ này tự động theo, không
            // cần sửa lại file này lần nữa.
            val source = tuyaController()?.worldStateSource ?: "tuya"
            val existingState = database.worldStateDao().getState(source, deviceId)
            val oldState = existingState?.let {
                try {
                    org.json.JSONObject(it.attributesJson).optString("state", "false")
                } catch (e: Exception) {
                    "false"
                }
            }?.toBooleanStrictOrNull() ?: false

            if (isDeviceOn != oldState) {
                com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                    database.worldStateDao(), source, deviceId, "state", isDeviceOn.toString()
                )
                database.eventLogDao().insertLog(
                    com.aichatvn.agent.data.model.EventLogEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = source,
                        sourceId = deviceId,
                        eventType = "state_change",
                        value = isDeviceOn.toString(),
                        summary = "Thiết bị Tuya $deviceName đổi trạng thái ngoài ý muốn của app: ${if (isDeviceOn) "Bật" else "Tắt"} (phát hiện qua Dashboard)."
                    )
                )
                logger.i("SmartSwitchSkill", "🔁 Dashboard phát hiện biến động vật lý bên ngoài: $deviceName đổi trạng thái $oldState ➔ $isDeviceOn")

                // ✅ MỚI (Household Event Broadcast): báo cho MỌI máy khác cùng household_id
                // biết ngay qua SSE — không phải chỉ ghi world_state cho riêng máy này. Cùng
                // hàm publishTuyaStateChange() mà handleSet() dùng khi CHÍNH app điều khiển
                // thành công (xem đầu file) — với server, đây chỉ là 1 sự kiện tuya_state_change
                // khác, dedup theo eventId như mọi household event khác.
                householdEventPublisher.publishTuyaStateChange(deviceId, deviceName, isDeviceOn)
            }
        } catch (e: Exception) {
            logger.e("SmartSwitchSkill", "Lỗi đồng bộ world_state từ Dashboard cho $deviceName: ${e.message}")
        }
    }

    // ✅ MỚI: dùng cho vòng poll nhanh (5s) ở DashboardViewModel khi Dashboard đang mở — CHỈ đọc
    // trạng thái ON/OFF qua getStatusBatch() (đã Local-first, xem TuyaManager.getStatusBatch()),
    // KHÔNG build lại x/y/room/scene/supportedActions như getDashboardNodes() đầy đủ. Nhẹ hơn
    // nhiều cho việc gọi lặp lại mỗi 5 giây.
    override suspend fun pollLightweightStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val tuyaDevices = database.tuyaDeviceDao().getAllDevices().filter { it.online }
        if (tuyaDevices.isEmpty()) return@withContext emptyMap()

        val batchStates = try {
            tuyaController()?.getStatusBatch(tuyaDevices.map { it.id }) ?: emptyMap()
        } catch (e: Exception) {
            logger.e("SmartSwitchSkill", "Lỗi poll trạng thái nhanh: ${e.message}")
            emptyMap()
        }

        val result = mutableMapOf<String, Boolean>()
        for (dev in tuyaDevices) {
            val isOn = batchStates[dev.id]?.isOn ?: continue
            result[dev.id] = isOn
            detectAndBroadcastPhysicalChange(dev.id, dev.name, isOn)
        }
        result
    }

    override suspend fun initialize() {
        syncToDeviceRegistry()
    }

    private suspend fun syncToDeviceRegistry() {
        try {
            val initialNodes = getDashboardNodes()
            dashboardDeviceRegistry.registerNodes(manifest.id, initialNodes)
            logger.i("SmartSwitchSkill", "Khởi tạo sơ đồ đèn lên bản sao số thành công.")
        } catch (e: Exception) {
            logger.e("SmartSwitchSkill", "Khởi tạo sơ đồ đèn lên bản sao số thất bại", e)
        }
    }

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("SmartSwitchSkill", "execute: action=$action, params=$params")
        return when (action) {
            "set" -> handleSet(params)
            "status" -> handleStatus(params)
            "scan" -> handleScan()
            else -> failure("Action không xác định: $action")
        }
    }



    
    private suspend fun handleSet(params: Map<String, Any>): AgentKernel.PluginResult {
        val deviceKey = params["device"] as? String
            ?: return failure("Thiếu tên thiết bị")
        val state = params["state"] as? Boolean
            ?: return failure("Thiếu trạng thái")

        return try {
            // ✅ SỬA (Bug B+C+D, rồi tổng quát hoá đa giao thức): chuẩn hóa deviceKey (có thể
            // là id hoặc tên, tùy nơi gọi) thành (DeviceSummary, controller đúng giao thức)
            // NGAY từ đầu qua resolveDevice() — để world_state và DeviceRegistry luôn dùng
            // đúng MỘT định danh (id), khớp với "<worldStateSource>.<id>.state=..." mà
            // PreconditionGuardDialog lưu, và khớp với key trong DeviceRegistry.nodeMap (vốn
            // được index theo dev.id, không phải theo tên). KHÔNG còn hardcode tuyaDeviceDao —
            // thiết bị MQTT (hay giao thức mới sau này) resolve đúng qua đây.
            val (summary, controller) = resolveDevice(deviceKey)
                ?: return failure("Không tìm thấy thiết bị '$deviceKey'")
            val deviceId = summary.id
            val deviceName = summary.displayName

            // ✅ MỚI: khi Chế độ Local-only đang bật, TuyaManager.turnOn()/turnOff() không còn
            // fallback Cloud nữa — nó throw thẳng nếu Local thất bại (xem TuyaManager.
            // setDeviceState()). Thử fallback THỨ HAI khi đó: gửi lệnh qua Gateway tới Camera
            // Node ở nhà, CHỜ xác nhận thực thi thật (không chỉ "đã gửi") — xem
            // DeviceCommandGatewayClient.sendDeviceCommandToHomeAndAwaitResult(). Camera Node
            // tự gọi lại turnOn()/turnOff() của CHÍNH NÓ (đang cùng LAN với thiết bị), tức là
            // lệnh vẫn được thực thi bằng đường LOCAL thật, chỉ có bước "chuyển tiếp" đi qua
            // Gateway.
            //
            // Khi Local-only đang TẮT (mặc định), tuyaManager.turnOn()/turnOff() không đổi hành
            // vi cũ (vẫn tự fallback Cloud như trước) — nhánh Failure bên dưới trong trường hợp
            // đó chỉ xảy ra khi lỗi thật sự (cả Local lẫn Cloud đều thất bại), không có gì để
            // thử thêm qua gateway, nên trả lỗi gốc như hành vi cũ.
            var viaGateway = false
            // ✅ MỚI: chỉ có ý nghĩa khi viaGateway=true — true = Camera Node đã báo về xác nhận
            // thực thi thật; false = hết thời gian chờ mà chưa có xác nhận (lệnh CÓ THỂ vẫn đang
            // được xử lý, không phải chắc chắn thất bại).
            var gatewayConfirmed = true

            // ✅ SỬA (Tầng 2b): trước đây bắt exception từ tuyaManager.turnOn()/turnOff() để
            // quyết định fallback Gateway. DeviceController.turnOn()/turnOff() KHÔNG ném
            // exception theo đúng thiết kế (xem DeviceController.kt — bọc lỗi mạng/offline
            // thành DeviceActionResult.Failure thay vì crash), nên đổi điều kiện fallback từ
            // "bắt được exception" sang "result là Failure" — TƯƠNG ĐƯƠNG 100% hành vi cũ, vì
            // code cũ cũng chỉ dùng cờ LOCAL_ONLY_MODE_ENABLED để quyết định có thử Gateway
            // hay không, không hề phân biệt loại exception nào ném ra.
            val result = if (state) controller.turnOn(deviceId) else controller.turnOff(deviceId)

            if (result is DeviceActionResult.Failure) {
                // ✅ MỚI: Gateway relay CHỈ có nhánh xử lý cho Tuya —
                // WebhookGatewayService.handleIncomingDeviceCommand() hiện chỉ
                // when("tuya" -> ...) (mục 5.3/9.1 tài liệu kiến trúc). Giao thức khác (MQTT,
                // OEM sau này...) chưa có nhánh Gateway relay — thử gọi vẫn sẽ chỉ nhận về
                // "device_type không được hỗ trợ" từ Gateway, nên trả lỗi gốc thẳng luôn thay
                // vì tốn 1 round-trip mạng vô ích. Khi ai đó thêm nhánh MQTT vào
                // WebhookGatewayService (xem mục 9.3 tài liệu), điều kiện dưới đây tự mở ra
                // nếu đổi thành so khớp danh sách protocol hỗ trợ thay vì == TUYA_CLOUD.
                if (controller.protocol != DeviceProtocol.TUYA_CLOUD) {
                    return failure("Lỗi điều khiển thiết bị: ${result.reason}")
                }

                val localOnlyEnabled = configProvider.getBoolean(AppConfigDefaults.LOCAL_ONLY_MODE_ENABLED)
                if (!localOnlyEnabled) {
                    return failure("Lỗi điều khiển thiết bị: ${result.reason}")
                }

                val homeDeviceCode = configProvider.getString(AppConfigDefaults.HOME_CAMERA_NODE_DEVICE_CODE).trim()
                if (homeDeviceCode.isBlank()) {
                    return failure(
                        "Lỗi điều khiển thiết bị: Local thất bại và chưa cấu hình Mã Camera Node ở nhà để gửi lệnh dự phòng qua Gateway."
                    )
                }

                val gatewayResult = deviceCommandGatewayClient.sendDeviceCommandToHomeAndAwaitResult(
                    deviceType = "tuya",
                    command = org.json.JSONObject().apply {
                        put("deviceName", deviceKey)
                        put("action", if (state) "on" else "off")
                    }
                )
                if (!gatewayResult.success) {
                    // gatewayResult.confirmed luôn true ở nhánh này (thất bại KHÔNG XÁC NHẬN
                    // không tồn tại — timeout luôn trả success=true dè dặt, xem doc-comment
                    // sendDeviceCommandToHomeAndAwaitResult()), nên đây là thất bại THẬT: hoặc
                    // Gateway từ chối ngay, hoặc Camera Node đã báo về thực thi lỗi.
                    return failure("Lỗi điều khiển thiết bị: Local thất bại, gửi qua Gateway cũng thất bại: ${gatewayResult.message}")
                }
                viaGateway = true
                gatewayConfirmed = gatewayResult.confirmed
            }

            val now = System.currentTimeMillis()

            // ✅ SỬA: dùng controller.worldStateSource thay vì literal "tuya" — với Tuya giá
            // trị vẫn y hệt "tuya" (xem TuyaDeviceController.kt), nên KHÔNG đổi key nào đã
            // lưu trong DB (các precondition "tuya.<id>.state=..." vẫn khớp bình thường); với
            // MQTT giá trị là "mqtt" (xem MqttDeviceController.worldStateSource).
            com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                database.worldStateDao(), controller.worldStateSource, deviceId, "state", state.toString()
            )

            // ✅ MỚI (Household Event Broadcast): publish tuya_state_change — ĐÚNG 1 lần, tại
            // điểm xác nhận thành công CUỐI CÙNG, và CHỈ khi:
            // (1) controller.worldStateSource == "tuya" — MQTT ngoài phạm vi broadcast này
            //     (xem household-event-broadcast-plan.md mục 0, MqttDeviceController tiếp tục
            //     ghi world_state qua cơ chế hiện có, không đụng tới ở đây);
            // (2) !viaGateway — nếu lệnh vừa đi qua Gateway relay (Local thất bại, Local-only
            //     bật), CHÍNH Camera Node đã tự publish rồi khi nó thực thi thành công (xem
            //     WebhookGatewayService.handleTuyaCommand()) — publish thêm ở đây sẽ TRÙNG lần
            //     phát cho cùng 1 hành động (2 originDeviceCode khác nhau cho cùng 1 sự kiện).
            if (controller.worldStateSource == "tuya" && !viaGateway) {
                householdEventPublisher.publishTuyaStateChange(deviceId, deviceName, state)
            }

            // ✅ MỚI (Tuần 5 - Active Logging): Ghi nhật ký sự kiện tương tác vật lý vào event_logs
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = now,
                    source = controller.worldStateSource,
                    sourceId = deviceName,
                    eventType = "state_change",
                    value = state.toString(),
                    // ✅ SỬA: bỏ chữ "Tuya" cứng trong câu tóm tắt — plugin này giờ dùng chung
                    // cho mọi giao thức, không riêng Tuya.
                    summary = when {
                        viaGateway && gatewayConfirmed ->
                            "Thiết bị $deviceName đã được Camera Node ở nhà xác nhận chuyển sang trạng thái: ${if (state) "Bật" else "Tắt"} (qua Gateway, Local trực tiếp thất bại)."
                        viaGateway ->
                            "Thiết bị $deviceName: đã gửi lệnh ${if (state) "Bật" else "Tắt"} qua Gateway tới Camera Node ở nhà (Local trực tiếp thất bại) — CHƯA có xác nhận thực thi thật, có thể vẫn đang xử lý."
                        else ->
                            "Thiết bị $deviceName đã được chuyển sang trạng thái: ${if (state) "Bật" else "Tắt"} thành công qua ứng dụng."
                    }
                )
            )
            
            // ✅ SỬA (Bug D): DeviceRegistry.nodeMap được index theo dev.id — truyền
            // deviceName vào đây trước đây khiến updateNode() luôn no-op (không tìm thấy
            // node), nên trạng thái Dashboard không cập nhật tức thời sau khi bật/tắt. Với
            // thiết bị chưa từng lên Dashboard (vd MQTT — xem getDashboardNodes() chỉ đọc
            // tuyaDeviceDao(), ngoài phạm vi sửa lần này), updateNode() no-op an toàn, không lỗi.
            dashboardDeviceRegistry.updateNode(deviceId) { current ->
                current.copy(
                    online = true,
                    status = if (state) "Đang bật" else "Đang tắt",
                    lastSeen = now
                )
            }
            
            when {
                viaGateway && gatewayConfirmed ->
                    success("Đã ${if (state) "bật" else "tắt"} $deviceName qua Camera Node ở nhà (Local trực tiếp không kết nối được)")
                viaGateway ->
                    success("Đã gửi lệnh ${if (state) "bật" else "tắt"} $deviceName tới Camera Node ở nhà, nhưng chưa nhận được xác nhận thực thi — kiểm tra lại nếu cần chắc chắn")
                else ->
                    success("Đã ${if (state) "bật" else "tắt"} thiết bị $deviceName")
            }
        } catch (e: Exception) {
            failure("Lỗi điều khiển thiết bị: ${e.message}")
        }
    }


    

    private suspend fun handleStatus(params: Map<String, Any>): AgentKernel.PluginResult {
        val deviceKey = params["device"] as? String
            ?: return failure("Thiếu tên thiết bị")

        return try {
            // ✅ SỬA (đồng bộ với handleSet): resolveDevice() thay cho tra thẳng
            // tuyaDeviceDao() — cùng 1 cách resolve cho cả 2 hàm, cùng hoạt động đúng với
            // mọi giao thức đã đăng ký (Tuya, MQTT...), không riêng Tuya.
            val (summary, controller) = resolveDevice(deviceKey)
                ?: return failure("Không tìm thấy thiết bị '$deviceKey'")

            val status = controller.getStatus(summary.id)
                ?: return failure("Không lấy được trạng thái thiết bị '${summary.displayName}'")

            success(
                "Thiết bị ${summary.displayName} hiện đang ${if (status.isOn) "bật" else "tắt"}",
                mapOf("status" to status.isOn)
            )
        } catch (e: Exception) {
            failure("Lỗi lấy trạng thái: ${e.message}")
        }
    }

    private suspend fun handleScan(): AgentKernel.PluginResult {
        // ⚠️ Cố tình vẫn gọi thẳng tuyaManager — xem comment ở constructor: "quét/khám phá
        // thiết bị mới" không thuộc hợp đồng DeviceController.
        return try {
            val devices = tuyaManager.scanDevices()
            success("Đã quét hoàn tất mạng. Tìm thấy ${devices.size} thiết bị.")
        } catch (e: Exception) {
            failure("Lỗi khi quét thiết bị: ${e.message}")
        }
    }

    override suspend fun shutdown() {}
}