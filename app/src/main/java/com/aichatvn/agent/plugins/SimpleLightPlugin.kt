package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton

class SimpleLightPlugin @Inject constructor() : SimplePlugin(
    manifest = PluginManifest(
        id = "light",
        name = "Điều khiển đèn",
        version = "1.0.0",
        description = "Bật/tắt đèn thông minh trong nhà",
        keywords = listOf("đèn", "light", "bật đèn", "tắt đèn"),
        actions = listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt đèn",
                keywords = listOf("bật", "tắt", "mở"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên đèn", true),
                    PluginParameter("state", "boolean", "true: bật, false: tắt", true)
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái đèn",
                keywords = listOf("trạng thái", "kiểm tra", "thế nào"),
                parameters = listOf(
                    PluginParameter("device", "string", "Tên đèn", false)
                )
            )
        )
    )
) {
    
    private val devices = mapOf(
        "phòng khách" to true,
        "phòng ngủ" to false,
        "nhà bếp" to false
    )
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "set" -> {
                val device = params["device"] as? String
                    ?: return PluginResult.NeedMoreInfo(
                        listOf("device"),
                        "Bạn muốn bật/tắt đèn ở phòng nào? (${devices.keys.joinToString()})"
                    )
                
                val state = params["state"] as? Boolean
                    ?: return PluginResult.NeedMoreInfo(
                        listOf("state"),
                        "Bạn muốn bật hay tắt đèn $device?"
                    )
                
                val deviceKey = devices.keys.find { it.equals(device, ignoreCase = true) }
                if (deviceKey == null) {
                    return PluginResult.Failure("Không tìm thấy đèn tại '$device'. Có: ${devices.keys.joinToString()}")
                }
                
                // TODO: Gọi API thực tế ở đây
                eventBus.publishAndForget(PluginEvent(
                    type = "LIGHT_CHANGED",
                    source = manifest.id,
                    payload = mapOf("device" to deviceKey, "state" to state)
                ))
                
                PluginResult.Success(mapOf(
                    "message" to "✅ Đã ${if(state) "bật" else "tắt"} đèn $deviceKey"
                ))
            }
            
            "status" -> {
                val device = params["device"] as? String
                if (device != null) {
                    val deviceKey = devices.keys.find { it.equals(device, ignoreCase = true) }
                    if (deviceKey == null) {
                        PluginResult.Failure("Không tìm thấy đèn '$device'")
                    } else {
                        val isOn = devices[deviceKey] ?: false
                        PluginResult.Success(mapOf(
                            "device" to deviceKey,
                            "state" to isOn,
                            "message" to "Đèn $deviceKey đang ${if(isOn) "bật" else "tắt"}"
                        ))
                    }
                } else {
                    val statusList = devices.map { (name, isOn) ->
                        "$name: ${if(isOn) "🟢 Bật" else "⚫ Tắt"}"
                    }
                    PluginResult.Success(mapOf(
                        "devices" to statusList,
                        "message" to statusList.joinToString("\n")
                    ))
                }
            }
            
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }
}