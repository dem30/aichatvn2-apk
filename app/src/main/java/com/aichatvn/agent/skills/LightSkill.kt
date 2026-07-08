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
class LightSkill @Inject constructor(
    private val tuyaManager: TuyaManager,
    private val database: AppDatabase,
    private val deviceRegistry: DeviceRegistry,
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("light", "Điều khiển đèn", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true),
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt đèn thông minh",
                examples = listOf("bật đèn", "tắt đèn"),
                exampleOverrides = mapOf(
                    "bật đèn" to mapOf("state" to true),
                    "tắt đèn" to mapOf("state" to false)
                ),
                tags = listOf("light", "switch", "relay", "device"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device"),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true, "boolean")
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái hiện tại của đèn",
                examples = listOf("trạng thái đèn", "kiểm tra đèn"),
                tags = listOf("status", "query", "sensor"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device")
                )
            ),
            PluginAction(
                name = "scan",
                description = "Quét các thiết bị đèn thông minh trong mạng",
                examples = listOf("quét thiết bị đèn", "tìm đèn tuya mới"),
                tags = listOf("discovery", "scan", "network"),
                parameters = emptyList()
            )
        )
    )

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

                DeviceNode(
                    id = dev.id,
                    name = dev.name,
                    type = DeviceType.LIGHT,
                    pluginId = manifest.id,
                    defaultAction = "status",
                    defaultParams = mapOf("device" to dev.id),
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
                    x = finalX,
                    y = finalY,
                    online = isOnline,
                    icon = "💡",
                    ip = "192.168.1.${50 + index}",
                    battery = null,
                    status = if (isOnline) {
                        if (isDeviceOn) "Đang bật" else "Đang tắt"
                    } else {
                        "Mất kết nối"
                    },
                    room = "Phòng Khách"
                )
            }
        }
        deferredNodes.awaitAll()
    }

    // ✅ ĐÃ THÊM: Triển khai hàm khởi tạo vòng đời để tự động nạp thiết bị khi app mở lại từ nền
    override suspend fun initialize() {
        syncToDeviceRegistry()
    }

    private suspend fun syncToDeviceRegistry() {
        try {
            val initialNodes = getDashboardNodes()
            deviceRegistry.registerNodes(initialNodes)
            logger.i("LightSkill", "Khởi tạo sơ đồ đèn lên bản sao số thành công.")
        } catch (e: Exception) {
            logger.e("LightSkill", "Khởi tạo sơ đồ đèn lên bản sao số thất bại", e)
        }
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
            if (state) {
                tuyaManager.turnOn(deviceName)
            } else {
                tuyaManager.turnOff(deviceName)
            }
            
            deviceRegistry.updateNode(deviceName) { current ->
                current.copy(
                    online = true,
                    status = if (state) "Đang bật" else "Đang tắt",
                    lastSeen = System.currentTimeMillis()
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