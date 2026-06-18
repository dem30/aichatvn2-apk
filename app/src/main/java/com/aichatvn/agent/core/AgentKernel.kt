package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AgentKernel v5 - LLM Routing + QA Context
 * 
 * 1. Search QA context từ TrainingSkill
 * 2. Gọi Groq để phân tích intent (có QA context)
 * 3. Tìm plugin phù hợp
 * 4. Execute
 */
@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,  // ✅ THÊM: để search QA
    private val logger: Logger
) {

    suspend fun process(userMessage: String): PluginResult {
        logger.d("AgentKernel", "Processing: '$userMessage'")
        
        // ✅ BƯỚC 1: TÌM QA CONTEXT
        val qaContext = buildQAContext(userMessage)
        if (qaContext.isNotEmpty()) {
            logger.d("AgentKernel", "Found QA context:\n$qaContext")
        }
        
        // ✅ BƯỚC 2: LLM Routing - CÓ QA CONTEXT
        val intent = resolveIntentWithLLM(userMessage, qaContext)
        if (intent == null) {
            return PluginResult.Failure("Không hiểu yêu cầu")
        }
        
        logger.d("AgentKernel", "Intent resolved: plugin=${intent.pluginId}, action=${intent.action}, params=${intent.params}")
        
        // BƯỚC 3: Find plugin
        val plugin = findPlugin(intent)
        if (plugin == null) {
            return PluginResult.Failure("Không tìm thấy plugin cho: ${intent.pluginId}")
        }
        
        // BƯỚC 4: Execute
        return try {
            plugin.execute(intent.action, intent.params)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute error: ${e.message}", e)
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }

    /**
     * ✅ HÀM PHỐI HỢP VỚI TRAININGSKILL
     * 
     * Gọi trainingSkill.fuzzyMatchQuestion() để tìm Q&A liên quan
     * Trả về context để đưa vào LLM prompt
     */
    private suspend fun buildQAContext(message: String): String {
        try {
            val result = trainingSkill.fuzzyMatchQuestion(message, "default_user", 0.5f)
            if (!result.success || result.data == null) return ""
            
            @Suppress("UNCHECKED_CAST")
            val matches = result.data as? List<Map<String, Any>> ?: return ""
            if (matches.isEmpty()) return ""
            
            return matches.joinToString("\n") { match ->
                val qa = match["qa"] as? QAEntity
                val similarity = match["similarity"] as? Float ?: 0f
                if (qa != null) {
                    "📚 ${qa.question} → ${qa.answer} (độ tương tự: ${String.format("%.2f", similarity)})"
                } else ""
            }
        } catch (e: Exception) {
            logger.e("AgentKernel", "buildQAContext error: ${e.message}", e)
            return ""
        }
    }

    /**
     * ✅ SỬA: THÊM QA CONTEXT VÀO PROMPT
     * 
     * QA context giúp Groq hiểu:
     * - "anh B" → "bvb@gmail.com"
     * - "camera cổng" → "camera_01"
     * - "báo cáo 7h" → "gửi email hàng ngày"
     */
    private suspend fun resolveIntentWithLLM(message: String, qaContext: String = ""): Intent? {
        val availablePlugins = getAvailablePluginsDescription()
        
        val prompt = buildString {
            append("Bạn là bộ phân tích ý định cho AI quản gia.\n\n")
            append("DANH SÁCH PLUGIN:\n")
            append(availablePlugins)
            append("\n\n")
            
            if (qaContext.isNotEmpty()) {
                append("📚 KIẾN THỨC ĐÃ HỌC (từ Q&A):\n")
                append(qaContext)
                append("\n\n")
                append("⚠️ QUAN TRỌNG: Dùng kiến thức trên để map tên riêng.\n")
                append("Ví dụ: 'anh B' trong Q&A = 'bvb@gmail.com' thì tham số 'to' phải là 'bvb@gmail.com'.\n\n")
            }
            
            append("CÂU: \"$message\"\n\n")
            append("Trả về JSON thuần túy:\n")
            append("{\n")
            append("  \"plugin\": \"tên_plugin\",\n")
            append("  \"action\": \"tên_action\",\n")
            append("  \"params\": { ... }\n")
            append("}")
        }
        
        return try {
            val response = groqClient.chat(
                message = prompt,
                extraContext = "Bạn là AI phân tích intent, chỉ trả về JSON.",
                history = emptyList(),
                imageUrl = null
            )
            parseIntentResponse(response, message)
        } catch (e: Exception) {
            logger.e("AgentKernel", "LLM routing error: ${e.message}", e)
            null
        }
    }

    private fun parseIntentResponse(response: String, originalMessage: String): Intent? {
        return try {
            val cleaned = response
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = JSONObject(cleaned)
            
            val pluginId = json.optString("plugin", "").takeIf { it.isNotEmpty() }
            val action = json.optString("action", "").takeIf { it.isNotEmpty() }
            
            if (pluginId == null || action == null) {
                logger.w("AgentKernel", "Invalid intent response: plugin or action missing")
                return null
            }
            
            Intent(
                pluginId = pluginId,
                action = action,
                params = json.optJSONObject("params")?.toMap() ?: emptyMap()
            )
            
        } catch (e: Exception) {
            logger.e("AgentKernel", "Parse intent error: ${e.message}, response: $response")
            null
        }
    }

    private fun getAvailablePluginsDescription(): String {
        if (plugins.isEmpty()) {
            return "Không có plugin nào được cài đặt."
        }
        
        return plugins.joinToString("\n\n") { plugin ->
            buildString {
                append("📦 ${plugin.id} - ${plugin.name}\n")
                append("Actions: ${plugin.getActions().joinToString { it.name }}")
            }
        }
    }

    private fun findPlugin(intent: Intent): Plugin? {
        return plugins.find { it.id == intent.pluginId }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is org.json.JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        list.add(
                            when (item) {
                                is JSONObject -> item.toMap()
                                else -> item
                            }
                        )
                    }
                    list
                }
                else -> value
            }
        }
        return map
    }

    data class Intent(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any> = emptyMap()
    )

    sealed class PluginResult {
        data class Success(val data: Any) : PluginResult()
        data class Failure(val error: String) : PluginResult()
        data class NeedMoreInfo(val missingParams: List<String>, val question: String) : PluginResult()
    }
}