package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingSkill @Inject constructor(
    @ApplicationContext private val context: Context
    , private val logger: Logger
) : BaseAgentSkill {
    
    override val skillName = "TrainingSkill"
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _qaList = MutableStateFlow<List<QAEntity>>(emptyList())
    val qaList: StateFlow<List<QAEntity>> = _qaList.asStateFlow()
    
    override suspend fun initialize() {
        refreshQAList("default_user")
    }
    
    override suspend fun shutdown() { }
    
    suspend fun refreshQAList(username: String) {
        _qaList.value = database.qaDao().getAllQAs(username)
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
                    category = obj.optString("category", "chat"),
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
    
    suspend fun fuzzyMatchQuestion(query: String, username: String, threshold: Float = 0.6f): AgentResponse {
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