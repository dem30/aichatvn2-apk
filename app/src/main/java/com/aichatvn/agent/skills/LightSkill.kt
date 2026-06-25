package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction          // ✅ THÊM
import com.aichatvn.agent.core.plugin.PluginParameter       // ✅ THÊM
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LightSkill @Inject constructor(
    private val tuyaManager: TuyaManager,  // ✅ Chỉ gọi TuyaManager
    logger: Logger
) : BaseSkill("light", "Điều khiển đèn", logger), Plugin {

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
                    PluginParameter("device", "string", "Tên thiết bị", true),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true)
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái đèn",
                parameters = listOf(
                    PluginParameter("device", "string", "Tên thiết bị", true)
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
            val count = tuyaManager.scanDevices()
            success("✅ Đã tìm thấy $count thiết bị")
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