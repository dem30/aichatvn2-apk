package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
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
    private val configProvider: AppConfigProvider,
    logger: Logger, 
) : BaseSkill("training", "Huấn luyện AI quản gia", logger), Plugin {

    override val visibleInQuickBar: Boolean = false

    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _qaList = MutableStateFlow<List<QAEntity>>(emptyList())
    val qaList: StateFlow<List<QAEntity>> = _qaList.asStateFlow()
    
    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats.asStateFlow()

    data class MatchResult(
        val intentMatches: List<Pair<QAEntity, Double>>,
        val aliasMatches: List<Pair<QAEntity, Double>>
    )

    suspend fun countQAByType(type: String): Int {
        return try {
            database.qaDao().getAllQAs("default_user").count { it.type == type }
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Lỗi đếm QA theo type: ${e.message}", e)
            0
        }
    }

    fun isIntentQA(qa: QAEntity): Boolean {
        return qa.type == "intent"
    }

    // ==================== PLUGIN IMPLEMENTATION ====================

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "add",
                description = "Thêm câu hỏi và câu trả lời",
                parameters = listOf(
                    PluginParameter("question", "string", "Câu hỏi", true),
                    PluginParameter("answer", "string", "Câu trả lời", true),
                    PluginParameter("type", "string", "Loại QA (intent/alias)", true),
                    PluginParameter("category", "string", "Danh mục", false),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "search",
                description = "Tìm kiếm Q&A",
                parameters = listOf(
                    PluginParameter("query", "string", "Từ khóa", true),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê Q&A",
                parameters = listOf(
                    PluginParameter("page", "number", "Trang", false),
                    PluginParameter("pageSize", "number", "Số lượng", false),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "delete",
                description = "Xóa Q&A",
                parameters = listOf(
                    PluginParameter("id", "string", "ID", true),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "delete_all",
                description = "Xóa tất cả Q&A",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "export",
                description = "Xuất Q&A ra JSON",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "import",
                description = "Nhập Q&A từ JSON",
                parameters = listOf(
                    PluginParameter("json", "string", "Dữ liệu JSON", true),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            ),
            PluginAction(
                name = "stats",
                description = "Thống kê",
                parameters = emptyList()
            )
        )
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        
        return when (action) {
            "add" -> handleAdd(params, username)
            "search" -> handleSearch(params, username)
            "list" -> handleList(params, username)
            "delete" -> handleDelete(params, username)
            "delete_all" -> handleDeleteAll(params, username)
            "export" -> handleExport(params, username)
            "import" -> handleImport(params, username)
            "stats" -> handleStats()
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleAdd(params: Map<String, Any>, username: String): PluginResult {
        val question = params["question"] as? String ?: return PluginResult.Failure("Thiếu câu hỏi")
        val answer = params["answer"] as? String ?: return PluginResult.Failure("Thiếu câu trả lời")
        val type = params["type"] as? String ?: "alias"
        val category = params["category"] as? String ?: "general"
        val result = addQA(question, answer, type, category, username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Thêm thất bại")
        }
    }

    private suspend fun handleSearch(params: Map<String, Any>, username: String): PluginResult {
        val query = params["query"] as? String ?: return PluginResult.Failure("Thiếu từ khóa")
        val result = searchQAs(query, username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Tìm kiếm thất bại")
        }
    }

    private suspend fun handleList(params: Map<String, Any>, username: String): PluginResult {
        val page = (params["page"] as? Number)?.toInt() ?: 1
        val pageSize = (params["pageSize"] as? Number)?.toInt() ?: 20
        val result = getQAsPaginated(page, pageSize, username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Lấy danh sách thất bại")
        }
    }

    private suspend fun handleDelete(params: Map<String, Any>, username: String): PluginResult {
        val id = params["id"] as? String ?: return PluginResult.Failure("Thiếu ID")
        val result = deleteQA(id, username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Xóa thất bại")
        }
    }

    private suspend fun handleDeleteAll(params: Map<String, Any>, username: String): PluginResult {
        val result = deleteAllQAs(username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Xóa tất cả thất bại")
        }
    }

    private suspend fun handleExport(params: Map<String, Any>, username: String): PluginResult {
        val result = exportQAs(username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Xuất dữ liệu thất bại")
        }
    }

    private suspend fun handleImport(params: Map<String, Any>, username: String): PluginResult {
        val jsonString = params["json"] as? String ?: return PluginResult.Failure("Thiếu dữ liệu JSON")
        val result = importQAs(jsonString, username)
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Nhập dữ liệu thất bại")
        }
    }

    private suspend fun handleStats(): PluginResult {
        updateStats()
        return PluginResult.Success(_stats.value)
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
    
    suspend fun getQAsPaginated(page: Int, pageSize: Int, username: String): PluginResult {
        return try {
            val allQAs = database.qaDao().getAllQAs(username)
            val start = (page - 1) * pageSize
            val end = minOf(start + pageSize, allQAs.size)
            val paginated = if (start < allQAs.size) allQAs.subList(start, end) else emptyList()
            
            PluginResult.Success(
                mapOf(
                    "qas" to paginated,
                    "total" to allQAs.size,
                    "page" to page,
                    "pageSize" to pageSize
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Get QAs failed")
        }
    }
    
    suspend fun exportQAs(username: String): PluginResult {
        return try {
            val allQAs = database.qaDao().getAllQAs(username)
                .filter { it.category != "auto_init" }
            val jsonArray = JSONArray()
            for (qa in allQAs) {
                val obj = JSONObject().apply {
                    put("id", qa.id)
                    put("question", qa.question)
                    put("answer", qa.answer)
                    put("category", qa.category)
                    put("type", qa.type)
                    put("createdBy", qa.createdBy)
                    put("createdAt", qa.createdAt)
                    put("timestamp", qa.timestamp)
                }
                jsonArray.put(obj)
            }
            PluginResult.Success(
                mapOf(
                    "json" to jsonArray.toString(2),
                    "count" to allQAs.size
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Export QAs failed")
        }
    }
    
    suspend fun importQAs(jsonString: String, username: String): PluginResult {
        return try {
            val jsonArray = JSONArray(jsonString)
            val existingQAs = database.qaDao().getAllQAs(username)
            val existingByQuestion = existingQAs.associateBy { it.question.trim().lowercase() }

            var imported = 0
            var skipped = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val question = obj.getString("question")
                val answer = obj.getString("answer")
                val type = obj.optString("type", "alias")
                val category = obj.optString("category", "general")

                if (category == "auto_init") { skipped++; continue }

                val key = question.trim().lowercase()
                val existing = existingByQuestion[key]
                when {
                    existing == null -> {
                        database.qaDao().insertQA(
                            QAEntity(
                                id = UUID.randomUUID().toString(),
                                question = question,
                                answer = answer,
                                category = category,
                                type = type,
                                createdBy = username,
                                createdAt = System.currentTimeMillis(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        imported++
                    }
                    existing.answer != answer || existing.type != type -> {
                        database.qaDao().updateQA(
                            existing.copy(
                                answer = answer,
                                type = type,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        imported++
                    }
                    else -> skipped++
                }
            }
            refreshQAList(username)
            PluginResult.Success(
                mapOf(
                    "message" to "Import: $imported Q&As đã xử lý, $skipped bỏ qua",
                    "imported" to imported,
                    "skipped" to skipped
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Import QAs failed")
        }
    }
    
    suspend fun addQA(
        question: String, 
        answer: String, 
        type: String, 
        category: String, 
        username: String
    ): PluginResult {
        return try {
            val qa = QAEntity(
                id = UUID.randomUUID().toString(),
                question = question,
                answer = answer,
                category = category,
                type = type,
                createdBy = username,
                createdAt = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis()
            )
            database.qaDao().insertQA(qa)
            refreshQAList(username)
            PluginResult.Success(
                mapOf(
                    "message" to "Đã thêm Q&A",
                    "qa" to qa
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Add QA failed")
        }
    }
    
    suspend fun updateQA(
        id: String, 
        question: String?, 
        answer: String?, 
        type: String?, 
        category: String?, 
        username: String
    ): PluginResult {
        return try {
            val existing = database.qaDao().getQAById(id, username) ?: return PluginResult.Failure("QA not found")
            
            val updated = existing.copy(
                question = question ?: existing.question,
                answer = answer ?: existing.answer,
                type = type ?: existing.type,
                category = category ?: existing.category,
                timestamp = System.currentTimeMillis()
            )
            database.qaDao().updateQA(updated)
            refreshQAList(username)
            PluginResult.Success(
                mapOf(
                    "message" to "Đã cập nhật Q&A",
                    "qa" to updated
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Update QA failed")
        }
    }
    
    suspend fun deleteQA(id: String, username: String): PluginResult {
        return try {
            database.qaDao().deleteQA(id, username)
            refreshQAList(username)
            PluginResult.Success(mapOf("message" to "QA deleted"))
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Delete QA failed")
        }
    }
    
    suspend fun deleteAllQAs(username: String): PluginResult {
        return try {
            database.qaDao().deleteAllQAs(username)
            refreshQAList(username)
            PluginResult.Success(mapOf("message" to "All QAs deleted"))
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Delete all QAs failed")
        }
    }
    
    suspend fun searchQAs(query: String, username: String): PluginResult {
        return try {
            val matches = fuzzyMatchCategorized(query, username)
            val combined = (matches.intentMatches + matches.aliasMatches).map { it.first }
            PluginResult.Success(
                mapOf(
                    "results" to combined,
                    "count" to combined.size
                )
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Search QAs failed")
        }
    }

    suspend fun fuzzyMatchCategorized(
        query: String, 
        username: String, 
        threshold: Float? = null
    ): MatchResult {
        return try {
            val configThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.5f)
            val activeThreshold = threshold ?: configThreshold

            val allQAs = database.qaDao().getAllQAs(username)
            val intents = mutableListOf<Pair<QAEntity, Double>>()
            val aliases = mutableListOf<Pair<QAEntity, Double>>()

            for (qa in allQAs) {
                val questionSimilarity = calculateSimilarity(query.lowercase(), qa.question.lowercase())
                val answerSimilarity = calculateSimilarity(query.lowercase(), qa.answer.lowercase())
                val maxSimilarity = maxOf(questionSimilarity, answerSimilarity).toDouble()

                if (maxSimilarity >= activeThreshold) {
                    if (qa.type == "intent") {
                        intents.add(qa to maxSimilarity)
                    } else {
                        aliases.add(qa to maxSimilarity)
                    }
                }
            }

            MatchResult(
                intentMatches = intents.sortedByDescending { it.second },
                aliasMatches = aliases.sortedByDescending { it.second }
            )
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Fuzzy match categorized failed: ${e.message}", e)
            MatchResult(emptyList(), emptyList())
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