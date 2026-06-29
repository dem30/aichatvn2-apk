package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnPlugin @Inject constructor(
    private val trainingSkill: TrainingSkill,
    logger: Logger
) : BaseSkill("learn", "Học lệnh Quản gia", logger), Plugin {

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(), // Năng lực cơ bản mặc định
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "learn",
                description = "Phân tích và tự học câu trả lời dạng 'Học: câu hỏi → câu trả lời'",
                triggerPrefixes = listOf("Học:", "Dạy:"), // Tuyên bố luật kích hoạt động cho AgentKernel
                parameters = listOf(
                    PluginParameter("message", "string", "Câu lệnh dạng Học: hoặc Dạy:", true),
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "learn" -> handleLearn(params)
            else -> PluginResult.Failure("Action không hỗ trợ: $action")
        }
    }

    private suspend fun handleLearn(params: Map<String, Any>): PluginResult {
        val message = params["message"] as? String ?: return PluginResult.Failure("Thiếu câu lệnh")
        val username = params["username"] as? String ?: "default_user"

        val parts = message.substringAfter(":").split("→", "->").map { it.trim() }
        if (parts.size != 2) {
            return PluginResult.Failure("Cú pháp không hợp lệ. Vui lòng nhập dạng: Học: câu hỏi → câu trả lời")
        }

        val key = parts[0]
        val value = parts[1]

        val learnResult = trainingSkill.addQA(
            question = key,
            answer = value,
            type = "general",
            category = "general",
            username = username
        )

        return when (learnResult) {
            is PluginResult.Success -> PluginResult.Success(
                mapOf(
                    "message" to "✅ Đã học thành công: $key → $value",
                    "question" to key,
                    "answer" to value
                )
            )
            is PluginResult.Failure -> PluginResult.Failure("Lỗi khi ghi nhớ câu lệnh: ${learnResult.error}")
            else -> PluginResult.Failure("Không thể thực hiện tác vụ học lệnh")
        }
    }
}