package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.ui.dashboard.DashboardProvider
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
    private val deviceRegistry: DeviceRegistry, // ✅ ĐÃ TIÊM: Để thực hiện đẩy sự kiện trạng thái thời gian thực
    logger: Logger
) : BaseSkill("light", "Điều khiển đèn", logger), Plugin, DashboardProvider {

    override val routable: Boolean = true
    override val visibleOnDashboard: Boolean = true
    override val autoGenerateQA: Boolean = true

    override suspend fun getDashboardNodes(): List<DeviceNode> = withContext(Dispatchers.IO) {
        val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
        
        val deferredNodes = tuyaDevices.mapIndexed { index, dev ->
            async {
                val xCoord = 40f + (index % 2) * 160f
                val yCoord = 200f + (index / 2) * 160f

                val isDeviceOnline = try {
                    tuyaManager.getStatus(dev.name)
                } catch (e: Exception) {
                    false
                }

                DeviceNode(
                    id = dev.id,
                    name = dev.name,
                    type = DeviceType.LIGHT,
                    pluginId = id,
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
                    battery = null, // Nguồn điện trực tiếp
                    status = if (isDeviceOnline) "Đang hoạt động" else "Mất kết nối",
                    room = "Phòng Khách" // Gán metadata Room cho Digital Twin
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

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt đèn thông minh",
                examples = listOf("bật đèn","tắt đèn"),
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
                examples = listOf("quét thiết bị đèn", "tìm đèn tuya mới"), // Action không tham số -> Cho phép ví dụ
                                tags = listOf("discovery", "scan", "network"),
                parameters = emptyList()
            )
        )
    }

    override suspend fun shutdown() {}
}