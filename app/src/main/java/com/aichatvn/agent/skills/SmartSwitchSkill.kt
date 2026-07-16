package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartSwitchSkill @Inject constructor(
    private val tuyaManager: TuyaManager,
    private val database: AppDatabase,
    private val deviceRegistry: DeviceRegistry,
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("smart_switch", "Điều khiển thiết bị đóng ngắt", logger), Plugin {

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
        val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
        
        val deferredNodes = tuyaDevices.mapIndexed { index, dev ->
            async {
                val defaultX = 40f + (index % 2) * 160f
                val defaultY = 200f + (index / 2) * 160f

                val savedX = configProvider.getFloat("layout_x_${dev.id}", -1f)
                val savedY = configProvider.getFloat("layout_y_${dev.id}", -1f)
                val finalX = if (savedX >= 0f) savedX else defaultX
                val finalY = if (savedY >= 0f) savedY else defaultY

                val isOnline = dev.online 
                
                val isDeviceOn = try {
                    if (isOnline) {
                        tuyaManager.getStatus(dev.name)
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
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

    override suspend fun initialize() {
        syncToDeviceRegistry()
    }

    private suspend fun syncToDeviceRegistry() {
        try {
            val initialNodes = getDashboardNodes()
            deviceRegistry.registerNodes(manifest.id, initialNodes)
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
        val deviceName = params["device"] as? String
            ?: return failure("Thiếu tên thiết bị")
        val state = params["state"] as? Boolean
            ?: return failure("Thiếu trạng thái")

        return try {
            if (state) {
                tuyaManager.turnOn(deviceName)
            } else {
                tuyaManager.turnOff(deviceName)
            }

            val now = System.currentTimeMillis()

            // ✅ MỚI (Tuần 5 - Active Logging): Đồng bộ lập tức trạng thái mới vào world_state
            com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                database.worldStateDao(), "tuya", deviceName, "state", state.toString()
            )

            // ✅ MỚI (Tuần 5 - Active Logging): Ghi nhật ký sự kiện tương tác vật lý vào event_logs
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = now,
                    source = "tuya",
                    sourceId = deviceName,
                    eventType = "state_change",
                    value = state.toString(),
                    summary = "Thiết bị Tuya $deviceName đã được chuyển sang trạng thái: ${if (state) "Bật" else "Tắt"} thành công qua ứng dụng."
                )
            )
            
            deviceRegistry.updateNode(deviceName) { current ->
                current.copy(
                    online = true,
                    status = if (state) "Đang bật" else "Đang tắt",
                    lastSeen = now
                )
            }
            
            success("Đã ${if (state) "bật" else "tắt"} thiết bị $deviceName")
        } catch (e: Exception) {
            failure("Lỗi điều khiển thiết bị: ${e.message}")
        }
    }

    private suspend fun handleStatus(params: Map<String, Any>): AgentKernel.PluginResult {
        val deviceName = params["device"] as? String
            ?: return failure("Thiếu tên thiết bị")

        return try {
            val status = tuyaManager.getStatus(deviceName)
            success("Thiết bị $deviceName hiện đang ${if (status) "bật" else "tắt"}", mapOf("status" to status))
        } catch (e: Exception) {
            failure("Lỗi lấy trạng thái: ${e.message}")
        }
    }

    private suspend fun handleScan(): AgentKernel.PluginResult {
        return try {
            val devices = tuyaManager.scanDevices()
            success("Đã quét hoàn tất mạng. Tìm thấy ${devices.size} thiết bị.")
        } catch (e: Exception) {
            failure("Lỗi khi quét thiết bị: ${e.message}")
        }
    }

    override suspend fun shutdown() {}
}