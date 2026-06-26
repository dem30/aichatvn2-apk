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
        val existing = trainingSkill.countQAByCategory("auto_init")
        if (existing > 0) {
            logger.i("QAInitBuilder", "Intent QA đã có ($existing rows), skip.")
            return
        }

        var count = 0
        // Loại bỏ các plugin hệ thống/cấu hình để chỉ giữ lại các plugin dịch vụ thực tế
        val targetPlugins = plugins.filter { it.id != "training" && it.id != "global" }

        // 1. Sinh Intent QA tiêu chuẩn cho từng Action của từng Plugin
        targetPlugins.forEach { plugin ->
            val triggers = plugin.getQATriggers()
            plugin.getActions().forEach { action ->
                val actionTriggers = triggers[action.name] ?: emptyList()

                // Chuyển ngữ thân thiện
                val friendlyAction = getFriendlyActionName(action.name)
                val friendlyPlugin = getFriendlyPluginName(plugin.id)
                
                val primaryTrigger = "yêu cầu $friendlyAction $friendlyPlugin"
                val alternativeTrigger = "$friendlyAction $friendlyPlugin"
                val defaultTrigger = actionTriggers.firstOrNull() ?: "${plugin.id} ${action.name}"

                val json = JSONObject().apply {
                    put("plugin", plugin.id)
                    put("action", action.name)
                    put("params", JSONObject(defaultParams(action)))
                }

                // Gom nhóm các biến thể câu hỏi duy nhất
                val uniqueQuestions = listOf(primaryTrigger, alternativeTrigger, defaultTrigger)
                    .map { it.trim().lowercase() }
                    .distinct()

                uniqueQuestions.forEach { question ->
                    trainingSkill.addQA(
                        question = question,
                        answer   = json.toString(),
                        category = "auto_init",
                        username = username
                    )
                    count++
                }
            }
        }

        // 2. Sinh các Intent QA kết hợp đặc biệt với Plugin Lên Lịch (Schedule)
        val schedulePlugin = plugins.firstOrNull { it.id == "schedule" || it.id == "scheduler" }
        if (schedulePlugin != null) {
            val scheduleAction = schedulePlugin.getActions().firstOrNull {
                it.name == "add" || it.name == "create" || it.name == "schedule"
            } ?: schedulePlugin.getActions().firstOrNull()

            if (scheduleAction != null) {
                // Lấy danh sách các action từ các plugin khác (trừ chính nó) để kết hợp đặt lịch
                targetPlugins.filter { it.id != "schedule" && it.id != "scheduler" }.forEach { otherPlugin ->
                    otherPlugin.getActions().forEach { otherAction ->
                        val friendlyAction = getFriendlyActionName(otherAction.name)
                        val friendlyPlugin = getFriendlyPluginName(otherPlugin.id)

                        // Các mẫu câu hỏi lên lịch tự nhiên trong Tiếng Việt
                        val questions = listOf(
                            "lên lịch $friendlyAction $friendlyPlugin",
                            "hẹn giờ $friendlyAction $friendlyPlugin",
                            "đặt lịch $friendlyAction $friendlyPlugin"
                        )

                        // Thiết kế cấu hình tham số dự phòng (đầy đủ cả dạng lồng nhau và dạng phẳng)
                        val combinedParams = JSONObject().apply {
                            put("name", "Lịch trình $friendlyAction $friendlyPlugin")
                            put("expression", "0 */15 * * * ?") // Mặc định chu kỳ 15 phút một lần
                            put("cron", "0 */15 * * * ?")
                            
                            // Tương thích dạng phẳng (Flat)
                            put("plugin", otherPlugin.id)
                            put("action", otherAction.name)
                            put("params", JSONObject(defaultParams(otherAction)))

                            // Tương thích dạng lồng nhau (Target Nested)
                            put("targetPlugin", otherPlugin.id)
                            put("targetAction", otherAction.name)
                            put("targetParams", JSONObject(defaultParams(otherAction)))
                        }

                        val json = JSONObject().apply {
                            put("plugin", schedulePlugin.id)
                            put("action", scheduleAction.name)
                            put("params", combinedParams)
                        }

                        questions.forEach { question ->
                            trainingSkill.addQA(
                                question = question,
                                answer   = json.toString(),
                                category = "auto_init",
                                username = username
                            )
                            count++
                        }
                    }
                }
            }
        }

        logger.d("QAInitBuilder", "✅ Intent QA init hoàn tất đầy đủ: $count entries")
    }

    private fun defaultParams(action: PluginAction): Map<String, String> =
        action.parameters
            .filter { it.required }
            .associate { param ->
                param.name to when (param.name.lowercase()) {
                    "to", "email"        -> "example@gmail.com"
                    "device", "deviceid" -> ""
                    "cameraid"           -> ""
                    else                 -> ""
                }
            }

    private fun getFriendlyActionName(action: String): String {
        return when (action.lowercase()) {
            "scan" -> "quét"
            "send", "sendemail", "send_email" -> "gửi"
            "turn_on", "turnon", "on" -> "bật"
            "turn_off", "turnoff", "off" -> "tắt"
            "capture", "snapshot", "take_photo", "takephoto" -> "chụp ảnh"
            "add", "create" -> "thêm"
            "delete", "remove" -> "xóa"
            "status", "check" -> "kiểm tra trạng thái"
            else -> action
        }
    }

    private fun getFriendlyPluginName(pluginId: String): String {
        return when (pluginId.lowercase()) {
            "camera" -> "camera"
            "email", "resend" -> "email"
            "tuya", "smartlife", "tuyadevice" -> "thiết bị tuya"
            "schedule", "scheduler" -> "lịch trình"
            "groq", "ai" -> "trợ lý ai"
            else -> pluginId
        }
    }
}