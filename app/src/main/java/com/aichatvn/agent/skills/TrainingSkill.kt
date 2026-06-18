package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: Logger
) : BaseSkill("training", "Huấn luyện AI quản gia", logger), Plugin {
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _qaList = MutableStateFlow<List<QAEntity>>(emptyList())
    val qaList: StateFlow<List<QAEntity>> = _qaList.asStateFlow()
    
    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats.asStateFlow()

    // ==================== PLUGIN IMPLEMENTATION ====================

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "add",
                description = "Thêm câu hỏi và câu trả lời",
                parameters = listOf(
                    PluginParameter("question", "string", "Câu hỏi", true),
                    PluginParameter("answer", "string", "Câu trả lời", true),
                    PluginParameter("category", "string", "Danh mục", false)
                )
            ),
            PluginAction(
                name = "search",
                description = "Tìm kiếm Q&A",
                parameters = listOf(
                    PluginParameter("query", "string", "Từ khóa", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê Q&A",
                parameters = listOf(
                    PluginParameter("page", "number", "Trang", false),
                    PluginParameter("pageSize", "number", "Số lượng", false)
                )
            ),
            PluginAction(
                name = "delete",
                description = "Xóa Q&A",
                parameters = listOf(
                    PluginParameter("id", "string", "ID", true)
                )
            ),
            PluginAction(
                name = "stats",
                description = "Thống kê",
                parameters = emptyList()
            )
        )
    }

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        val username = params["username"] as? String ?: "default_user"
        
        return when (action) {
            "add" -> handleAdd(params, username)
            "search" -> handleSearch(params, username)
            "list" -> handleList(params, username)
            "delete" -> handleDelete(params, username)
            "stats" -> handleStats()
            else -> failure("Action không xác định: $action")
        }
    }

    private suspend fun handleAdd(params: Map<String, Any>, username: String): AgentKernel.PluginResult {
        val question = params["question"] as? String ?: return failure("Thiếu câu hỏi")
        val answer = params["answer"] as? String ?: return failure("Thiếu câu trả lời")
        val category = params["category"] as? String ?: "general"
        val result = addQA(question, answer, category, username)
        return if (result.success) {
            success("✅ Đã thêm: $question", mapOf("qa" to result.data))
        } else {
            failure(result.error ?: "Thêm thất bại")
        }
    }

    private suspend fun handleSearch(params: Map<String, Any>, username: String): AgentKernel.PluginResult {
        val query = params["query"] as? String ?: return failure("Thiếu từ khóa")
        val threshold = (params["threshold"] as? Number)?.toFloat() ?: 0.5f
        val result = fuzzyMatchQuestion(query, username, threshold)
        return if (result.success) {
            AgentKernel.PluginResult.Success(result.data ?: emptyList<Any>())
        } else {
            failure(result.error ?: "Tìm kiếm thất bại")
        }
    }

    private suspend fun handleList(params: Map<String, Any>, username: String): AgentKernel.PluginResult {
        val page = (params["page"] as? Number)?.toInt() ?: 1
        val pageSize = (params["pageSize"] as? Number)?.toInt() ?: 20
        val result = getQAsPaginated(page, pageSize, username)
        return if (result.success) {
            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any>
            AgentKernel.PluginResult.Success(data?.get("qas") ?: emptyList<Any>())
        } else {
            failure(result.error ?: "Lấy danh sách thất bại")
        }
    }

    private suspend fun handleDelete(params: Map<String, Any>, username: String): AgentKernel.PluginResult {
        val id = params["id"] as? String ?: return failure("Thiếu ID")
        val result = deleteQA(id, username)
        return if (result.success) {
            success("✅ Đã xóa")
        } else {
            failure(result.error ?: "Xóa thất bại")
        }
    }

    private suspend fun handleStats(): AgentKernel.PluginResult {
        updateStats()
        return AgentKernel.PluginResult.Success(_stats.value)
    }

    // ==================== CORE SKILL METHODS ====================
    
    override suspend fun initialize() {
        refreshQAList("default_user")
        updateStats()
    }
    
    override suspend fun shutdown() { }
    
    private fun updateStats() {
        val qas = _qaList.value
        val categories = qas.groupBy { it.category }.mapValues { it.value.size }
        _stats.value = mapOf(
            "total" to qas.size,
            "categories" to categories,
            "lastUpdated" to System.currentTimeMillis()
        )
    }
    
    suspend fun refreshQAList(username: String) {
        _qaList.value = database.qaDao().getAllQAs(username)
        updateStats()
    }
    
    suspend fun getQAsPaginated(page: Int, pageSize: Int, username: String): AgentResponse {
        return try {
            val allQAs = database.qaDao().getAllQAs(username)
            val start = (page - 1) * pageSize
            val end = minOf(start + pageSize, allQAs.size)
            val paginated = if (start < allQAs.size) allQAs.subList(start, end) else emptyList()
            
            AgentResponse(
                success = true,
                data = mapOf(
                    "qas" to paginated,
                    "total" to allQAs.size,
                    "page" to page,
                    "pageSize" to pageSize
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun exportQAs(username: String): AgentResponse {
        return try {
            val allQAs = database.qaDao().getAllQAs(username)
            val jsonArray = JSONArray()
            for (qa in allQAs) {
                val obj = JSONObject().apply {
                    put("id", qa.id)
                    put("question", qa.question)
                    put("answer", qa.answer)
                    put("category", qa.category)
                    put("createdBy", qa.createdBy)
                    put("createdAt", qa.createdAt)
                    put("timestamp", qa.timestamp)
                }
                jsonArray.put(obj)
            }
            AgentResponse(success = true, data = jsonArray.toString(2))
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun importQAs(jsonString: String, username: String): AgentResponse {
        return try {
            val jsonArray = JSONArray(jsonString)
            var imported = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val qa = QAEntity(
                    id = UUID.randomUUID().toString(),
                    question = obj.getString("question"),
                    answer = obj.getString("answer"),
                    category = obj.optString("category", "general"),
                    createdBy = username,
                    createdAt = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis()
                )
                database.qaDao().insertQA(qa)
                imported++
            }
            refreshQAList(username)
            AgentResponse(success = true, data = "Imported $imported Q&As")
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun addQA(question: String, answer: String, category: String, username: String): AgentResponse {
        return try {
            val qa = QAEntity(
                id = UUID.randomUUID().toString(),
                question = question,
                answer = answer,
                category = category,
                createdBy = username,
                createdAt = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis()
            )
            database.qaDao().insertQA(qa)
            refreshQAList(username)
            AgentResponse(success = true, data = qa)
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun updateQA(id: String, question: String?, answer: String?, category: String?, username: String): AgentResponse {
        return try {
            val existing = database.qaDao().getQAById(id, username)
            if (existing == null) {
                return AgentResponse(success = false, error = "QA not found")
            }
            
            val updated = existing.copy(
                question = question ?: existing.question,
                answer = answer ?: existing.answer,
                category = category ?: existing.category,
                timestamp = System.currentTimeMillis()
            )
            database.qaDao().updateQA(updated)
            refreshQAList(username)
            AgentResponse(success = true, data = updated)
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun deleteQA(id: String, username: String): AgentResponse {
        return try {
            database.qaDao().deleteQA(id, username)
            refreshQAList(username)
            AgentResponse(success = true, data = "QA deleted")
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun deleteAllQAs(username: String): AgentResponse {
        return try {
            database.qaDao().deleteAllQAs(username)
            refreshQAList(username)
            AgentResponse(success = true, data = "All QAs deleted")
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    suspend fun searchQAs(query: String, username: String): AgentResponse {
        return try {
            val results = database.qaDao().searchQAs(query, username)
            AgentResponse(success = true, data = results)
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    /**
     * ⭐ HÀM PHỐI HỢP CHÍNH VỚI CHATSKILL VÀ AGENTKERNEL
     * 
     * Tìm Q&A gần giống với câu hỏi
     * Được gọi từ:
     * - ChatSkill: xây dựng context cho Groq
     * - AgentKernel: xây dựng context cho LLM routing
     */
    suspend fun fuzzyMatchQuestion(query: String, username: String, threshold: Float = 0.5f): AgentResponse {
        return try {
            val allQAs = database.qaDao().getAllQAs(username)
            val matches = allQAs.mapNotNull { qa ->
                val questionSimilarity = calculateSimilarity(query.lowercase(), qa.question.lowercase())
                val answerSimilarity = calculateSimilarity(query.lowercase(), qa.answer.lowercase())
                val maxSimilarity = maxOf(questionSimilarity, answerSimilarity)
                if (maxSimilarity >= threshold) {
                    qa to maxSimilarity
                } else null
            }.sortedByDescending { it.second }.take(3)
            
            AgentResponse(
                success = true,
                data = matches.map { mapOf("qa" to it.first, "similarity" to it.second) }
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            AgentResponse(success = false, error = e.message)
        }
    }
    
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.contains(shorter)) return 1f
        val distance = levenshteinDistance(s1, s2)
        return 1f - (distance.toFloat() / maxOf(s1.length, s2.length))
    }
    
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}