package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginContext
import com.aichatvn.agent.core.plugin.PluginEvent
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginResult
import com.aichatvn.agent.skills.TrainingSkill
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingPlugin @Inject constructor(
    private val trainingSkill: TrainingSkill
) : Plugin {
    
    override val manifest = PluginManifest(
        id = "training",
        name = "Training Plugin",
        version = "1.0.0",
        description = "Quản lý Q&A training cho chatbot",
        author = "AIChatVN2",
        keywords = listOf("qa", "training", "học", "câu hỏi", "trả lời"),
        actions = listOf(
            PluginAction(
                name = "add",
                description = "Thêm câu hỏi và câu trả lời mới",
                keywords = listOf("thêm", "add", "học thêm"),
                parameters = listOf(
                    PluginParameter("question", "string", "Câu hỏi", true),
                    PluginParameter("answer", "string", "Câu trả lời", true),
                    PluginParameter("category", "string", "Danh mục (chat, camera, faq)", false)
                )
            ),
            PluginAction(
                name = "search",
                description = "Tìm kiếm Q&A",
                keywords = listOf("tìm", "search", "tìm kiếm"),
                parameters = listOf(
                    PluginParameter("query", "string", "Từ khóa tìm kiếm", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê danh sách Q&A",
                keywords = listOf("liệt kê", "danh sách", "list"),
                parameters = listOf(
                    PluginParameter("page", "number", "Trang số", false),
                    PluginParameter("pageSize", "number", "Số lượng mỗi trang", false)
                )
            )
        ),
        publishes = listOf("QA_ADDED", "QA_DELETED")
    )
    
    override suspend fun initialize(context: PluginContext) {
        trainingSkill.initialize()
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        val username = "default_user"
        
        return try {
            when (action) {
                "add" -> {
                    val question = params["question"] as? String 
                        ?: return PluginResult.Failure("Thiếu câu hỏi")
                    val answer = params["answer"] as? String 
                        ?: return PluginResult.Failure("Thiếu câu trả lời")
                    val category = params["category"] as? String ?: "chat"
                    val result = trainingSkill.addQA(question, answer, category, username)
                    if (result.success) {
                        PluginResult.Success(mapOf("message" to "✅ Đã thêm Q&A: $question"))
                    } else {
                        PluginResult.Failure(result.error ?: "Thêm Q&A thất bại")
                    }
                }
                "search" -> {
                    val query = params["query"] as? String 
                        ?: return PluginResult.Failure("Thiếu từ khóa tìm kiếm")
                    val result = trainingSkill.searchQAs(query, username)
                    if (result.success) {
                        PluginResult.Success(result.data ?: emptyList<Any>())
                    } else {
                        PluginResult.Failure(result.error ?: "Tìm kiếm thất bại")
                    }
                }
                "list" -> {
                    val page = (params["page"] as? Number)?.toInt() ?: 1
                    val pageSize = (params["pageSize"] as? Number)?.toInt() ?: 20
                    val result = trainingSkill.getQAsPaginated(page, pageSize, username)
                    if (result.success) {
                        @Suppress("UNCHECKED_CAST")
                        val data = result.data as? Map<String, Any>
                        PluginResult.Success(data?.get("qas") ?: emptyList<Any>())
                    } else {
                        PluginResult.Failure(result.error ?: "Lấy danh sách thất bại")
                    }
                }
                else -> PluginResult.Failure("Action không xác định: $action")
            }
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }
    
    override suspend fun onEvent(event: PluginEvent) {
        // Xử lý event sau
    }
    
    override suspend fun shutdown() {
        trainingSkill.shutdown()
    }
}