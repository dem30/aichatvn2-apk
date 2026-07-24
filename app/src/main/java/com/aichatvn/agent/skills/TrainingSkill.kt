package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class TrainingSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider,
    logger: Logger, 
) : BaseSkill("training", "Huấn luyện AI quản gia", logger), Plugin {

    
  companion object {
        private val SPACE_REGEX = Regex("\\s+")
        private const val MAX_QUERY_CACHE_SIZE = 500
    private val CHAT_CATALOG_TYPES = setOf("faq", "chat", "general")
    }

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(training = true),
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
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
    )

    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _qaList = MutableStateFlow<List<QAEntity>>(emptyList())
    val qaList: StateFlow<List<QAEntity>> = _qaList.asStateFlow()
    
    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats.asStateFlow()

    private val cacheMutex = Mutex()
    
    @Volatile
    private var cachedQAList: List<QAEntity> = emptyList()
    
    private val queryCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, MatchResult>(MAX_QUERY_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, MatchResult>?): Boolean {
                return size > MAX_QUERY_CACHE_SIZE
            }
        }
    )

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshQAList("default_user")
            } catch (e: Exception) {
                logger.e("TrainingSkill", "Không thể tải sớm cachedQAList lúc khởi chạy", e)
            }
        }
    }

    private fun invalidateCache() {
        queryCache.clear()
    }

    data class MatchResult(
        val intentMatches: List<Pair<QAEntity, Double>>,
        val aliasMatches: List<Pair<QAEntity, Double>>,
        val bestAliasMatches: Map<String, Pair<QAEntity, Double>> = emptyMap()
    )

    fun getRawCachedQAList(username: String): List<QAEntity> {
        return cachedQAList.filter { it.createdBy == username }
    }

    suspend fun countQAByType(type: String): Int {
        return cachedQAList.count { it.type == type }
    }

    fun isIntentQA(qa: QAEntity): Boolean {
        return qa.type == "intent"
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
            is PluginResult.Success -> {
                val data = result.data as? Map<*, *>
                val imported = data?.get("imported") as? Int ?: 0
                val skipped = data?.get("skipped") as? Int ?: 0
                val message = "Import: $imported Q&As đã xử lý, $skipped bỏ qua"
                PluginResult.Success(mapOf("message" to message, "imported" to imported, "skipped" to skipped))
            }
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
        val qas = cachedQAList
        val categories = qas.groupBy { it.category }.mapValues { it.value.size }
        _stats.value = mapOf(
            "total" to qas.size,
            "categories" to categories,
            "lastUpdated" to System.currentTimeMillis()
        )
    }
    
    suspend fun refreshQAList(username: String) {
        cacheMutex.withLock {
            val userQAs = database.qaDao().getAllQAs(username)
            val systemQAs = if (username != "default_user") {
                database.qaDao().getAllQAs("default_user")
            } else {
                emptyList()
            }
            cachedQAList = (userQAs + systemQAs).distinctBy { it.id }
            _qaList.value = cachedQAList
            invalidateCache()
        }
        updateStats()
    }
    
    suspend fun getQAsPaginated(page: Int, pageSize: Int, username: String): PluginResult {
        return try {
            val allQAs = cachedQAList.filter { it.createdBy == username }
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
            val allQAs = cachedQAList.filter { it.createdBy == username && it.category != "auto_init" }
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
            val existingQAs = cachedQAList.filter { it.createdBy == username }
            val existingByQuestion = existingQAs.associateBy { it.question.trim().lowercase() }

            var imported = 0
            var skipped = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val question = obj.getString("question").trim()
                val answer = obj.getString("answer").trim()
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
        username: String
    ): PluginResult {
        return addQA(
            question = question,
            answer = answer,
            type = type,
            category = "general",
            username = username
        )
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
                question = question.trim(),
                answer = answer.trim(),
                category = category,
                type = type,
                createdBy = username,
                createdAt = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis()
            )
            cacheMutex.withLock {
                database.qaDao().insertQA(qa)
            }
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
                question = question?.trim() ?: existing.question,
                answer = answer?.trim() ?: existing.answer,
                type = type ?: existing.type,
                category = category ?: existing.category,
                timestamp = System.currentTimeMillis()
            )
            cacheMutex.withLock {
                database.qaDao().updateQA(updated)
            }
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
            cacheMutex.withLock {
                database.qaDao().deleteQA(id, username)
            }
            refreshQAList(username)
            PluginResult.Success(mapOf("message" to "QA deleted"))
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Delete QA failed")
        }
    }
    
    suspend fun deleteAllQAs(username: String): PluginResult {
        return try {
            cacheMutex.withLock {
                database.qaDao().deleteAllQAs(username)
            }
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


    

    

// Tìm nhanh trong catalogue chat — KHÔNG gộp alias/intent, 1 lượt lọc + sort duy nhất.
suspend fun fuzzyMatchChatCatalog(
    query: String,
    username: String,
    threshold: Float? = null
): List<SearchMatch> {
    return try {
        val normalizedQuery = normalizeVietnamese(query)
        val activeThreshold = threshold
            ?: configProvider.getFloat(AppConfigDefaults.GLOBAL_CHAT_QA_THRESHOLD, 0.8f)

        cachedQAList
            .asSequence()
            // ✅ SỬA BUG: trước đây lọc `it.type in CHAT_CATALOG_TYPES` — nhưng `type` chỉ nhận
            // "intent"/"alias" (dùng cho router điều khiển thiết bị, xem fuzzyMatchCategorized()),
            // KHÔNG BAO GIỜ trùng với {"qa","chat","general"}. Hậu quả: catalogue Chat không bao giờ
            // khớp được bất kỳ Q&A nào, dù đã huấn luyện đúng "Danh mục (semanticType)" = chat/general.
            // Field đúng cần lọc là `category` (chính là "Danh mục (semanticType)" trên UI Huấn luyện
            // Q&A). Đồng thời vẫn loại type == "intent" (Q&A dành riêng cho lệnh điều khiển thiết bị)
            // để không lộ câu trả lời nội bộ vào chat khách hàng.
            .filter {
                (it.createdBy == username || it.createdBy == "default_user") &&
                    it.type != "intent" &&
                    it.category in CHAT_CATALOG_TYPES
            }
            .map { qa -> SearchMatch(qa, calculateSimilarity(normalizedQuery, qa.question)) }
            .filter { it.similarity >= activeThreshold }
            .sortedByDescending { it.similarity }
            .toList()
    } catch (e: Exception) {
        logger.e("TrainingSkill", "Lỗi fuzzyMatchChatCatalog: ${e.message}", e)
        emptyList()
    }
}

    

    suspend fun fuzzyMatchCategorized(
        query: String, 
        username: String, 
        intentThreshold: Float? = null,
        aliasThreshold: Float? = null
    ): MatchResult {
        val normalizedQuery = normalizeVietnamese(query)
        
        val configIntentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val configAliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)

        val activeIntentThreshold = intentThreshold ?: configIntentThreshold
        val activeAliasThreshold = aliasThreshold ?: configAliasThreshold

        val cacheKey = "$username:$normalizedQuery:$activeIntentThreshold:$activeAliasThreshold"
        
        val cachedResult = queryCache[cacheKey]
        if (cachedResult != null) return cachedResult

        return try {
          
            val allQAs = cachedQAList.filter { it.createdBy == username || it.createdBy == "default_user" }
            val intents = mutableListOf<Pair<QAEntity, Double>>()
            val aliases = mutableListOf<Pair<QAEntity, Double>>()

            for (qa in allQAs) {
                if (qa.type == "intent" && (qa.answer.length > 500 || qa.category in setOf("story", "knowledge", "document"))) {
                    continue
                }

                val maxSimilarity = calculateSimilarity(normalizedQuery, qa.question).toDouble()

                if (qa.type == "intent") {
                    if (maxSimilarity >= activeIntentThreshold) {
                        intents.add(qa to maxSimilarity)
                    }
                } else {
                    if (maxSimilarity >= activeAliasThreshold) {
                        aliases.add(qa to maxSimilarity)
                    }
                }
            }

            val bestAliasMatches = aliases
                .filter { it.second >= activeAliasThreshold } 
                .groupBy { it.first.category }
                .mapValues { entry -> entry.value.maxByOrNull { it.second } }
                .filterValues { it != null }
                .mapValues { it.value!! }

            val finalResult = MatchResult(
                intentMatches = intents.sortedByDescending { it.second },
                aliasMatches = aliases.sortedByDescending { it.second },
                bestAliasMatches = bestAliasMatches
            )
            
            queryCache[cacheKey] = finalResult
            finalResult
        } catch (e: Exception) {
            logger.e("TrainingSkill", "Fuzzy match categorized failed: ${e.message}", e)
            MatchResult(emptyList(), emptyList(), emptyMap())
        }
    }

    // ✅ ĐÃ SỬA: Ủy quyền sang VietnameseTextNormalizer (nguồn chân lý duy nhất) thay vì
    // tự viết lại logic bỏ dấu — xem chi tiết lý do trong VietnameseTextNormalizer.kt.
    // Giữ nguyên tên hàm + chữ ký private để không phải sửa các lời gọi normalizeVietnamese(...)
    // khác trong chính file này (calculateSimilarity, fuzzyMatchChatCatalog, fuzzyMatchCategorized...).
    private fun normalizeVietnamese(text: String): String =
        com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(text)

    private fun calculateSimilarity(s1: String, s2: String): Float {
        val clean1 = normalizeVietnamese(s1)
        val clean2 = normalizeVietnamese(s2)
        if (clean1.isEmpty() || clean2.isEmpty()) return 0f
        if (clean1 == clean2) return 1f
        
        val tokens1 = clean1.split(SPACE_REGEX).toSet()
        val tokens2 = clean2.split(SPACE_REGEX).toSet()
        val intersectionSize = tokens1.intersect(tokens2).size
        
        if (intersectionSize == 0) return 0f

        val maxLen = maxOf(clean1.length, clean2.length)
        val minLen = minOf(clean1.length, clean2.length)

        if (clean1.contains(clean2) || clean2.contains(clean1)) {
            val simpson = intersectionSize.toFloat() / minOf(tokens1.size, tokens2.size)
            val lengthRatio = minLen.toFloat() / maxLen.toFloat()
            return (simpson * 0.7f) + (lengthRatio * 0.3f)
        }

        val lenDiff = if (clean1.length > clean2.length) clean1.length - clean2.length else clean2.length - clean1.length
        if (maxLen > 10 && lenDiff.toFloat() / maxLen > 0.7f) {
            return 0f
        }
        
        val union = tokens1.union(tokens2).size.toFloat()
        val jaccard = if (union > 0) intersectionSize.toFloat() / union else 0f
        
        val levDistance = levenshteinDistance(clean1, clean2)
        val levSim = 1f - (levDistance.toFloat() / maxLen)
        
        return (jaccard * 0.4f) + (levSim * 0.6f)
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