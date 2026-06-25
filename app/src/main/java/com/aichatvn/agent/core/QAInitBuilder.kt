package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.utils.Logger
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Singleton
class QAInitBuilder @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val trainingSkill: TrainingSkill,
    private val logger: Logger
) {
    suspend fun buildInitialQA(username: String = "default_user") {
        // Skip nếu đã có Intent QA trong DB
        val existing = trainingSkill.countQAByCategory("auto_init")
        if (existing > 0) {
            logger.i("QAInitBuilder", "Intent QA đã có ($existing rows), skip.")
            return
        }

        plugins.forEach { plugin ->
            plugin.getActions().forEach { action ->
                vietnameseTriggers(plugin.id, action.name).forEach { trigger ->
                    val json = JSONObject().apply {
                        put("plugin", plugin.id)
                        put("action", action.name)
                        put("params", JSONObject(defaultParams(action)))
                    }
                    trainingSkill.addQA(
                        question = trigger,
                        answer   = json.toString(),
                        category = "auto_init",
                        username = username
                    )
                }
            }
        }
        logger.d("QAInitBuilder", "✅ Intent QA init xong")
    }

    private fun vietnameseTriggers(pluginId: String, action: String): List<String> =
        when ("$pluginId/$action") {
            "light/set"        -> listOf("bật đèn", "tắt đèn", "mở đèn", "đóng đèn")
            "light/status"     -> listOf("trạng thái đèn", "kiểm tra đèn", "đèn đang bật không")
            "light/scan"       -> listOf("quét đèn", "scan thiết bị", "tìm relay")
            "camera/scan"      -> listOf("chụp camera", "snapshot", "quét camera")
            "camera/list_cameras" -> listOf("danh sách camera", "liệt kê camera")
            "camera/set_smart_mode" -> listOf("bật ai camera", "tắt ai camera", "smart mode")
            "email/send"       -> listOf("gửi email", "gửi thư", "send mail")
            "schedule/add"     -> listOf("thêm lịch", "đặt lịch", "tạo lịch tự động")
            "schedule/list"    -> listOf("danh sách lịch", "xem lịch")
            "schedule/delete"  -> listOf("xóa lịch", "huỷ lịch")
            "notification/send" -> listOf("gửi thông báo", "push notification")
            else               -> listOf("$pluginId $action")
        }

    private fun defaultParams(action: PluginAction): Map<String, String> =
        action.parameters
            .filter { it.required }
            .associate { param ->
                param.name to when (param.name.lowercase()) {
                    "to", "email"    -> "example@gmail.com"
                    "device","deviceid" -> "device_1"
                    "cameraid"       -> "camera_1"
                    else             -> ""
                }
            }
}