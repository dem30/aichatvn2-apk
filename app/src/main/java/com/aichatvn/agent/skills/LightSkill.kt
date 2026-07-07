package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
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
class LightSkill @Inject constructor(
    private val hassManager: HassManager, // ✅ ĐÃ SỬA: Thay thế TuyaManager bằng HassManager
    private val database: AppDatabase,
    private val deviceRegistry: DeviceRegistry,
    logger: Logger
) : BaseSkill("light", "Điều khiển đèn", logger), Plugin {

    // ✅ ĐÃ SỬA: Đồng bộ hóa ví dụ và mô tả sang hệ thống Home Assistant
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true), // Tuyên bố năng lực Dashboard
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt đèn thông minh thông qua Home Assistant",
                examples = listOf("bật đèn", "tắt đèn"),
                tags = listOf("light", "switch", "relay", "device"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device"),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true, "boolean")
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái hiện tại của đèn trong Home Assistant",
                examples = listOf("trạng thái đèn", "kiểm tra đèn"),
                tags = listOf("status", "query", "sensor"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device")
                )
            ),
            PluginAction(
                name = "scan",
                description = "Đồng bộ hóa danh sách thiết bị thông minh từ Home Assistant",
                examples = listOf("quét thiết bị đèn", "tìm đèn mới"),
                tags = listOf("discovery", "scan", "network"),
                parameters = emptyList()
            )
        )
    )

    override suspend fun getDashboardNodes(): List<DeviceNode> = withContext(Dispatchers.IO) {
        val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
        
        val deferredNodes = tuyaDevices.mapIndexed { index, dev ->
            async {
                val xCoord = 40f + (index % 2) * 160f
                val yCoord = 200f + (index / 2) * 160f

                val isDeviceOnline = try {
                    hassManager.getStatus(dev.name) // ✅ ĐÃ SỬA: Gọi qua hassManager
                } catch (e: Exception) {
                    false
                }

                DeviceNode(
                    id = dev.id,
                    name = dev.name,
                    type = DeviceType.LIGHT,
                    pluginId = manifest.id,
                    defaultAction = "status",
                    defaultParams = mapOf("device" to dev.name),
                    supportedActions = listOf(
                        DeviceAction(
                            id = "set",
                            title = "Bật Đèn",
                            icon = "💡",
                            defaultParams = mapOf("state" to true)
                        ),
                        DeviceAction(
                            id = "set",
                            title = "Tắt Đèn",
                            icon = "🔌",
                            defaultParams = mapOf("state" to false)
                        ),
                        DeviceAction(
                            id = "status",
                            title = "Trạng thái",
                            icon = "ℹ️"
                        )
                    ),
                    x = xCoord,
                    y = yCoord,
                    online = isDeviceOnline,
                    icon = "💡",
                    ip = "192.168.1.${50 + index}",
                    battery = null,
                    status = if (isDeviceOnline) "Đang hoạt động" else "Mất kết nối",
                    room = "Phòng Khách"
                )
            }
        }
        deferredNodes.awaitAll()
    }

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("LightSkill", "execute: action=$action, params=$params")
        
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
            // ✅ ĐÃ SỬA: Chuyển tiếp lệnh gọi sang HassManager
            if (state) {
                hassManager.turnOn(deviceName)
            } else {
                hassManager.turnOff(deviceName)
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
            // ✅ ĐÃ SỬA: Chuyển tiếp lệnh gọi sang HassManager
            val status = hassManager.getStatus(deviceName)
            success("Thiết bị $deviceName hiện đang ${if (status) "bật" else "tắt"}", mapOf("status" to status))
        } catch (e: Exception) {
            failure("Lỗi lấy trạng thái: ${e.message}")
        }
    }

    private suspend fun handleScan(): AgentKernel.PluginResult {
        return try {
            // ✅ ĐÃ SỬA: Chuyển tiếp lệnh gọi sang HassManager
            val devices = hassManager.scanDevices()
            success("Đã đồng bộ hoàn tất. Tìm thấy ${devices.size} thiết bị hoạt động.")
        } catch (e: Exception) {
            failure("Lỗi khi đồng bộ thiết bị: ${e.message}")
        }
    }

    override suspend fun shutdown() {}
}