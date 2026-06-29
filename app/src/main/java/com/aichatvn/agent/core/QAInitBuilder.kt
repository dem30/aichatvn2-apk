package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.utils.Logger
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Singleton
class QAInitBuilder @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val trainingSkill: TrainingSkill,
    private val database: AppDatabase, // ✅ ĐÃ TIÊM: Sử dụng để ghi đĩa trực tiếp không dọn cache giữa vòng lặp
    private val logger: Logger
) {
    suspend fun buildInitialQA(username: String = "default_user") {
        var intentCount = 0
        var aliasCount = 0
        
        val existingQAs = trainingSkill.getRawCachedQAList(username)
        val existingByQuestion = existingQAs.associateBy { it.question.trim().lowercase() }

        // ĐÃ SỬA: Lọc chặt chẽ chỉ tự động sinh câu hỏi cho các thiết bị điều khiển thực tế (routable == true)
        // Loại bỏ hoàn toàn các kỹ năng hệ thống bổ trợ (routable == false) như appconfig ra khỏi phễu sinh Intent thô
        val targetPlugins = plugins.filter { it.autoGenerateQA && it.routable }
        val qaDao = database.qaDao()

        // 1. Đồng bộ hóa Intent QA động từ metadata của Plugin Action
        targetPlugins.forEach { plugin ->
            plugin.getActions().filter { it.enabled }.forEach { action ->
                val schemaParams = JSONObject()
                action.parameters.forEach { param ->
                    schemaParams.put(param.name, param.defaultValue ?: getDefaultPlaceholder(param))
                }

                val jsonSchema = JSONObject().apply {
                    put("plugin", plugin.id)
                    put("pluginVersion", plugin.pluginVersion)
                    put("schemaVersion", plugin.schemaVersion)
                    put("action", action.name)
                    put("params", schemaParams)
                }

                // Chỉ dùng examples làm trigger intent QA
                if (action.examples.isEmpty()) return@forEach

                val finalTriggers = action.examples
                    .map { it.trim().lowercase() }
                    .distinctBy { it }

                finalTriggers.forEach { question ->
                    val schemaString = jsonSchema.toString()
                    val existing = existingByQuestion[question]
                    
                    if (existing == null) {
                        // Tối ưu hóa: Ghi trực tiếp SQLite bằng cấu trúc DAO thuần để ngăn dọn cache RAM lặp lại
                        val qa = QAEntity(
                            id = UUID.randomUUID().toString(),
                            question = question,
                            answer = schemaString,
                            type = "intent",
                            category = "auto_init",
                            createdBy = username,
                            createdAt = System.currentTimeMillis(),
                            timestamp = System.currentTimeMillis()
                        )
                        qaDao.insertQA(qa)
                        intentCount++
                    } else if (!isJsonStructureIdentical(existing.answer, schemaString)) {
                        val updated = existing.copy(
                            answer = schemaString,
                            timestamp = System.currentTimeMillis()
                        )
                        qaDao.updateQA(updated)
                        intentCount++
                    }
                }
            }
        }

        // 2. Tự động thu thập bootstrap QA khai báo cục bộ từ bên trong các Plugin độc lập (chỉ áp dụng cho các Plugin routable)
        plugins.filter { it.routable }.forEach { plugin ->
            plugin.getBootstrapQA()
                .filter { it.type != "intent" }
                .forEach { bootstrap ->
                    val questionKey = bootstrap.question.trim().lowercase()
                    val existing = existingByQuestion[questionKey]
                    if (existing == null) {
                        val qa = QAEntity(
                            id = UUID.randomUUID().toString(),
                            question = bootstrap.question,
                            answer = bootstrap.answer,
                            type = bootstrap.type,
                            category = bootstrap.category,
                            createdBy = username,
                            createdAt = System.currentTimeMillis(),
                            timestamp = System.currentTimeMillis()
                        )
                        qaDao.insertQA(qa)
                        aliasCount++
                    } else if (existing.answer != bootstrap.answer || existing.type != bootstrap.type) {
                        val updated = existing.copy(
                            answer = bootstrap.answer,
                            type = bootstrap.type,
                            timestamp = System.currentTimeMillis()
                        )
                        qaDao.updateQA(updated)
                        aliasCount++
                    }
                }
        }
        // Tối ưu hóa lớn: Chỉ kích hoạt đồng bộ hóa nạp bộ nhớ RAM đúng 1 lần duy nhất khi kết thúc toàn bộ chu kỳ ghi lô
        if (intentCount > 0 || aliasCount > 0) {
            trainingSkill.refreshQAList(username)
            logger.i("QAInitBuilder", "✅ Đồng bộ Metadata hoàn tất: $intentCount Intents, $aliasCount Aliases được cập nhật hoặc thêm mới.")
        } else {
            logger.i("QAInitBuilder", "Hệ thống metadata đã đồng bộ trùng khớp, không có cập nhật mới.")
        }
    }

    private fun isJsonStructureIdentical(jsonStr1: String, jsonStr2: String): Boolean {
        return try {
            val obj1 = JSONObject(jsonStr1)
            val obj2 = JSONObject(jsonStr2)
            
            obj1.optString("plugin") == obj2.optString("plugin") &&
            obj1.optString("action") == obj2.optString("action") &&
            obj1.optString("schemaVersion") == obj2.optString("schemaVersion")
        } catch (_: Exception) {
            false
        }
    }

    private fun getDefaultPlaceholder(param: PluginParameter): Any {
        if (param.placeholder.isNotBlank()) return param.placeholder
        return when (param.type.lowercase()) {
            "boolean" -> false
            "number"  -> 0
            else -> ""
        }
    }
}