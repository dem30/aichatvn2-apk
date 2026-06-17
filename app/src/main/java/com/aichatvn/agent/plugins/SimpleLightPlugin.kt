package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.*
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleLightPlugin @Inject constructor(
    private val logger: Logger  // ✅ Inject logger
) : Plugin {  // ❌ Không extends SimplePlugin nữa, implement Plugin trực tiếp
    
    // ✅ eventBus được inject qua constructor (không dùng lateinit)
    // Nhưng Plugin interface không có eventBus, nên chúng ta tự inject
    
    override val manifest = PluginManifest(
        id = "light",
        name = "Điều khiển đèn",
        version = "1.0.0",
        description = "Bật/tắt đèn thông minh trong nhà",
        keywords = listOf("đèn", "light", "bật đèn", "tắt đèn"),
        actions = listOf(
            PluginAction(
                name = "set",
                description = "Bật hoặc tắt đèn",
                keywords = listOf("bật", "tắt", "mở", "on", "off"),
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
    
    private val devices = mutableMapOf(
        "phòng khách" to false,
        "phòng ngủ" to false,
        "nhà bếp" to false
    )
    
    override suspend fun initialize(context: PluginContext) {
        // Khởi tạo plugin
        logger.i("SimpleLightPlugin", "Initialized")
    }
    
    override suspend fun shutdown() {
        logger.i("SimpleLightPlugin", "Shutdown")
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        logger.d("SimpleLightPlugin", "execute: action=$action, params=$params")
        
        return when (action) {
            "set" -> handleSet(params)
            "status" -> handleStatus(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }
    
    private suspend fun handleSet(params: Map<String, Any>): PluginResult {
        val device = params["device"] as? String
            ?: return PluginResult.NeedMoreInfo(
                missingParams = listOf("device"),
                question = "Bạn muốn bật/tắt đèn ở phòng nào? (${devices.keys.joinToString()})"
            )
        
        val state = params["state"] as? Boolean
            ?: return PluginResult.NeedMoreInfo(
                missingParams = listOf("state"),
                question = "Bạn muốn bật hay tắt đèn $device?"
            )
        
        val deviceKey = devices.keys.find { it.equals(device, ignoreCase = true) }
        if (deviceKey == null) {
            return PluginResult.Failure("Không tìm thấy đèn tại '$device'. Có: ${devices.keys.joinToString()}")
        }
        
        // Cập nhật trạng thái
        devices[deviceKey] = state
        logger.i("SimpleLightPlugin", "💡 $deviceKey → ${if (state) "BẬT" else "TẮT"}")
        
        // ✅ eventBus được inject trong PluginContext hoặc qua DI
        // Vì Plugin interface không có eventBus, chúng ta lấy từ context
        val eventBus = context.eventBus
        
        eventBus?.publishAndForget(
            PluginEvent(
                type = "LIGHT_CHANGED",
                source = manifest.id,
                payload = mapOf(
                    "device" to deviceKey,
                    "state" to state,
                    "action" to "set"
                )
            )
        )
        
        return PluginResult.Success(
            data = mapOf(
                "message" to "✅ Đã ${if(state) "bật" else "tắt"} đèn $deviceKey",
                "device" to deviceKey,
                "state" to state
            )
        )
    }
    
    private suspend fun handleStatus(params: Map<String, Any>): PluginResult {
        val device = params["device"] as? String
        
        return if (device != null) {
            val deviceKey = devices.keys.find { it.equals(device, ignoreCase = true) }
            if (deviceKey == null) {
                PluginResult.Failure("Không tìm thấy đèn '$device'")
            } else {
                val isOn = devices[deviceKey] ?: false
                PluginResult.Success(
                    data = mapOf(
                        "device" to deviceKey,
                        "state" to isOn,
                        "message" to "Đèn $deviceKey đang ${if(isOn) "bật" else "tắt"}"
                    )
                )
            }
        } else {
            val statusList = devices.map { (name, isOn) ->
                "$name: ${if(isOn) "🟢 Bật" else "⚫ Tắt"}"
            }
            PluginResult.Success(
                data = mapOf(
                    "devices" to statusList,
                    "message" to statusList.joinToString("\n")
                )
            )
        }
    }
}