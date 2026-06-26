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
        val existingIntents = trainingSkill.countQAByType("intent")
        if (existingIntents > 0) {
            logger.i("QAInitBuilder", "Hệ thống đã có $existingIntents Intent QA, bỏ qua khởi tạo.")
            return
        }

        var intentCount = 0
        var aliasCount = 0
        val targetPlugins = plugins.filter { it.id != "training" && it.id != "global" }

        // 1. Sinh Intent QA tiêu chuẩn cho tất cả các Action của từng Plugin
        targetPlugins.forEach { plugin ->
            plugin.getActions().forEach { action ->
                val schemaParams = JSONObject()
                action.parameters.forEach { param ->
                    schemaParams.put(param.name, getDefaultPlaceholder(param.name, param.type))
                }

                val jsonSchema = JSONObject().apply {
                    put("plugin", plugin.id)
                    put("action", action.name)
                    put("params", schemaParams)
                }

                // Chuyển ngữ tự nhiên để làm Trigger Question cho Intent
                val friendlyAction = getFriendlyActionName(action.name)
                val friendlyPlugin = getFriendlyPluginName(plugin.id)

                val triggers = listOf(
                    "yêu cầu $friendlyAction $friendlyPlugin",
                    "$friendlyAction $friendlyPlugin",
                    "${plugin.id} ${action.name}",
                    friendlyAction,
                    friendlyPlugin
                ).map { it.trim().lowercase() }.distinct()

                triggers.forEach { question ->
                    trainingSkill.addQA(
                        question = question,
                        answer = jsonSchema.toString(),
                        type = "intent",
                        category = "auto_init",
                        username = username
                    )
                    intentCount++
                }
            }
        }

        // 2. Sinh Intent QA đặc biệt cho Plugin Lên Lịch (Generic composite schedule)
        val schedulePlugin = plugins.firstOrNull { it.id == "schedule" || it.id == "scheduler" }
        if (schedulePlugin != null) {
            val scheduleAction = schedulePlugin.getActions().firstOrNull {
                it.name == "add" || it.name == "create" || it.name == "schedule"
            } ?: schedulePlugin.getActions().firstOrNull()

            if (scheduleAction != null) {
                val scheduleTriggers = listOf("lên lịch", "đặt lịch", "hẹn giờ", "tạo lịch trình")
                val jsonSchema = JSONObject().apply {
                    put("plugin", schedulePlugin.id)
                    put("action", scheduleAction.name)
                    put("params", JSONObject().apply {
                        put("pluginId", "")
                        put("action", "")
                        put("cron", "")
                        put("intervalMinutes", 0)
                        put("params", JSONObject())
                    })
                }

                scheduleTriggers.forEach { trigger ->
                    trainingSkill.addQA(
                        question = trigger,
                        answer = jsonSchema.toString(),
                        type = "intent",
                        category = "auto_init",
                        username = username
                    )
                    intentCount++
                }
            }
        }

        // 3. Khởi tạo một số Alias QA mẫu theo từng Semantic Type để người dùng trải nghiệm ngay
        val defaultAliases = listOf(
            Triple("cổng", "camera_1", "camera"),
            Triple("sân trước", "camera_2", "camera"),
            Triple("đèn phòng khách", "relay_1", "device"),
            Triple("đèn sân vườn", "relay_2", "device"),
            Triple("sếp", "boss@gmail.com", "email"),
            Triple("tôi", "me@gmail.com", "email")
        )

        defaultAliases.forEach { (question, answer, semanticType) ->
            trainingSkill.addQA(
                question = question,
                answer = answer,
                type = semanticType, // Trường type lưu Semantic Type của Alias
                category = "default_alias",
                username = username
            )
            aliasCount++
        }

        logger.d("QAInitBuilder", "✅ Khởi tạo hoàn tất: $intentCount Intents, $aliasCount Aliases")
    }

    private fun getDefaultPlaceholder(name: String, type: String): Any {
        return when (type.lowercase()) {
            "boolean" -> true
            "number"  -> 0
            else -> {
                when (name.lowercase()) {
                    "to", "email", "recipient" -> "example@gmail.com"
                    "device", "deviceid", "device_id" -> "device_1"
                    "camera", "cameraid", "camera_id" -> "camera_1"
                    else -> ""
                }
            }
        }
    }

    private fun getFriendlyActionName(action: String): String {
        return when (action.lowercase()) {
            "scan" -> "quét"
            "send", "sendemail" -> "gửi"
            "turn_on", "turnon", "on", "power" -> "bật"
            "turn_off", "turnoff", "off" -> "tắt"
            "capture", "snapshot" -> "chụp ảnh"
            "add", "create" -> "thêm"
            "delete" -> "xóa"
            "status" -> "kiểm tra"
            else -> action
        }
    }

    private fun getFriendlyPluginName(pluginId: String): String {
        return when (pluginId.lowercase()) {
            "camera" -> "camera"
            "email", "resend" -> "email"
            "tuya" -> "thiết bị"
            "schedule" -> "lịch trình"
            "notification" -> "thông báo"
            else -> pluginId
        }
    }
}