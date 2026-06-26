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
        plugins.filter { it.visibleInQuickBar }.forEach { plugin ->
            val triggers = plugin.getQATriggers()
            plugin.getActions().forEach { action ->
                val actionTriggers = triggers[action.name]
                    ?: listOf("${plugin.id} ${action.name}")

                // Lấy trigger đầu tiên làm trigger chính (Primary Trigger)
                // Đảm bảo mỗi action chỉ có duy nhất 1 bản ghi QA Intent đại diện trong DB
                val primaryTrigger = actionTriggers.firstOrNull() ?: "${plugin.id} ${action.name}"

                val json = JSONObject().apply {
                    put("plugin", plugin.id)
                    put("action", action.name)
                    put("params", JSONObject(defaultParams(action)))
                }
                
                trainingSkill.addQA(
                    question = primaryTrigger,
                    answer   = json.toString(),
                    category = "auto_init",
                    username = username
                )
                count++
            }
        }
        logger.d("QAInitBuilder", "✅ Intent QA init hoàn tất (mỗi action một câu đại diện): $count entries")
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
}