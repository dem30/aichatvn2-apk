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
    private val database: AppDatabase,
    private val logger: Logger
) {
    suspend fun buildInitialQA(username: String = "default_user") {
        var intentCount = 0
        var aliasCount = 0
        
        val existingQAs = trainingSkill.getRawCachedQAList(username)
        val existingByQuestion = existingQAs.associateBy { it.question.trim().lowercase() }

        // ✅ ĐÃ SỬA: Lọc động thông qua thuộc tính PluginManifest thống nhất
        val targetPlugins = plugins.filter { it.manifest.autoGenerateQA && it.manifest.routable }
        val qaDao = database.qaDao()

        // 1. Đồng bộ hóa Intent QA động từ metadata của Plugin Action
        targetPlugins.forEach { plugin ->
            // ✅ ĐÃ SỬA: Đọc danh mục actions từ manifest
            plugin.manifest.actions.filter { it.enabled }.forEach { action ->
                if (action.examples.isEmpty()) return@forEach

                val finalTriggers = action.examples
                    .map { it.trim().lowercase() }
                    .distinctBy { it }

                // ✅ ĐÃ SỬA (fix bug bật/tắt cùng giá trị): trước đây schemaParams/jsonSchema được
                // build 1 LẦN DUY NHẤT cho cả action, dùng chung cho mọi example — nên "bật đèn"
                // và "tắt đèn" luôn nhận cùng 1 schema (state mặc định = false cho cả hai). Giờ
                // build RIÊNG cho từng câu hỏi, ưu tiên đọc override khai báo tại
                // action.exampleOverrides[question] trước khi rơi về defaultValue/placeholder.
                finalTriggers.forEach { question ->
                    val overrides = action.exampleOverrides[question] ?: emptyMap()

                    val schemaParams = JSONObject()
                    action.parameters.forEach { param ->
                        val value = overrides[param.name]
                            ?: param.defaultValue
                            ?: getDefaultPlaceholder(param)
                        schemaParams.put(param.name, value)
                    }

                    // ✅ ĐÃ SỬA: Lấy toàn bộ siêu dữ liệu phiên bản từ manifest
                    val jsonSchema = JSONObject().apply {
                        put("plugin", plugin.manifest.id)
                        put("pluginVersion", plugin.manifest.pluginVersion)
                        put("schemaVersion", plugin.manifest.schemaVersion)
                        put("action", action.name)
                        put("params", schemaParams)
                    }

                    val schemaString = jsonSchema.toString()
                    val existing = existingByQuestion[question]
                    
                    if (existing == null) {
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

        // 2. Tự động thu thập bootstrap QA khai báo cục bộ từ bên trong các Plugin độc lập
        // ✅ ĐÃ SỬA: Đọc trạng thái routable của plugin từ manifest
        plugins.filter { it.manifest.routable }.forEach { plugin ->
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

            // ✅ ĐÃ SỬA: trước đây chỉ so plugin/action/schemaVersion, KHÔNG so "params" — nên khi
            // sửa exampleOverrides (vd fix state:false -> đúng true/false theo từng câu), QA cũ
            // trong DB không tự refresh vì hàm này coi là "giống hệt". Giờ so thêm params.toString()
            // để bất kỳ thay đổi giá trị tham số nào cũng kích hoạt update lại QA hiện có.
            obj1.optString("plugin") == obj2.optString("plugin") &&
            obj1.optString("action") == obj2.optString("action") &&
            obj1.optString("schemaVersion") == obj2.optString("schemaVersion") &&
            obj1.optJSONObject("params")?.toString() == obj2.optJSONObject("params")?.toString()
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