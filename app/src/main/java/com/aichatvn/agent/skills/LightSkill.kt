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
import com.aichatvn.agent.ui.dashboard.DeviceAction // Thêm Import
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LightSkill @Inject constructor(
    private val tuyaManager: TuyaManager,
    private val database: AppDatabase,
    logger: Logger
) : BaseSkill("light", "Điều khiển đèn", logger), Plugin, DashboardProvider {

    override val routable: Boolean = true
    override val visibleOnDashboard: Boolean = true
    override val autoGenerateQA: Boolean = true

    override suspend fun getDashboardNodes(): List<DeviceNode> {
        val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
        return tuyaDevices.mapIndexed { index, dev ->
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
                
                // 1. Hành động mặc định khi tap vào node
                defaultAction = "status",
                
                // 2. Tham số định danh gốc của thiết bị (LightSkill yêu cầu khóa "device")
                defaultParams = mapOf("device" to dev.name),
                
                // 3. Khai báo danh sách hành động tương thích để UI tự động vẽ các nút bấm chức năng
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
                battery = 100
            )
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

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "set",
                description = "Bật/tắt đèn",
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device"),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true, "boolean")
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái đèn",
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true, "device")
                )
            ),
            PluginAction(
                name = "scan",
                description = "Quét thiết bị đèn",
                parameters = emptyList()
            )
        )
    }

    override fun getQATriggers(): Map<String, List<String>> = mapOf(
        "set"    to listOf("bật đèn", "tắt đèn", "mở đèn", "tắt relay", "bật relay"),
        "status" to listOf("trạng thái đèn", "kiểm tra đèn", "đèn đang bật không"),
        "scan"   to listOf("quét thiết bị", "tìm đèn", "scan relay")
    )

    private suspend fun handleScan(): AgentKernel.PluginResult {
        return try {
            val devices = tuyaManager.scanDevices()
            success("✅ Đã tìm thấy ${devices.size} thiết bị")
        } catch (e: Exception) {
            failure("Lỗi quét: ${e.message}")
        }
    }

    private suspend fun handleSet(params: Map<String, Any>): AgentKernel.PluginResult {
        val device = params["device"] as? String
            ?: return needMoreInfo(listOf("device"), "Thiết bị nào?")

        val state = params["state"] as? Boolean
            ?: return needMoreInfo(listOf("state"), "Bật hay tắt?")
        
        return try {
            if (state) {
                tuyaManager.turnOn(device)
            } else {
                tuyaManager.turnOff(device)
            }
            success(
                message = "✅ Đã ${if(state) "bật" else "tắt"} $device",
                data = mapOf("device" to device, "state" to state)
            )
        } catch (e: Exception) {
            failure("Lỗi: ${e.message}")
        }
    }

    private suspend fun handleStatus(params: Map<String, Any>): AgentKernel.PluginResult {
        val device = params["device"] as? String
            ?: return needMoreInfo(listOf("device"), "Thiết bị nào?")
        
        return try {
            val status = tuyaManager.getStatus(device)
            success(
                message = "$device đang ${if(status) "bật" else "tắt"}",
                data = mapOf("device" to device, "state" to status)
            )
        } catch (e: Exception) {
            failure("Lỗi: ${e.message}")
        }
    }
}