package com.aichatvn.agent.skills

import android.content.Context
import android.util.Base64
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.skills.base.BaseSkill
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionPlugin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groqClient: GroqClientTool,
    private val database: AppDatabase,
    logger: Logger
) : BaseSkill("vision", "Xử lý thị giác AI", logger), Plugin {

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(vision = true), // Tuyên bố năng lực thị giác động
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "analyze",
                description = "Phân tích hình ảnh bằng AI thông qua Groq",
                parameters = listOf(
                    PluginParameter("message", "string", "Câu hỏi kèm theo", true),
                    PluginParameter("username", "string", "Tên người dùng", true),
                    PluginParameter("imageBase64", "string", "Ảnh base64", false),
                    PluginParameter("fileUrl", "string", "Đường dẫn file ảnh", false),
                    PluginParameter("extraContext", "string", "Bối cảnh bổ sung", false)
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "analyze" -> handleAnalyze(params)
            else -> PluginResult.Failure("Action không hỗ trợ: $action")
        }
    }

    private suspend fun handleAnalyze(params: Map<String, Any>): PluginResult {
        val message = params["message"] as? String ?: return PluginResult.Failure("Thiếu câu hỏi")
        val username = params["username"] as? String ?: "default_user"
        val imageBase64 = params["imageBase64"] as? String
        val fileUrl = params["fileUrl"] as? String
        val extraContext = params["extraContext"] as? String ?: ""

        val imageDataUrl = if (!imageBase64.isNullOrEmpty()) {
            "data:image/jpeg;base64,$imageBase64"
        } else if (!fileUrl.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(fileUrl)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        "data:image/jpeg;base64,$b64"
                    } else null
                } catch (e: Exception) { null }
            }
        } else null

        val historySnapshot = withContext(Dispatchers.IO) {
            try {
                database.chatMessageDao().getMessages(username, 6)
                    .reversed()
                    .map { mapOf("role" to it.role, "content" to it.content) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        return try {
            val responseText = withTimeout(30_000L) {
                groqClient.chat(
                    message = message,
                    extraContext = extraContext,
                    history = historySnapshot,
                    imageUrl = imageDataUrl
                )
            }
            PluginResult.Success(mapOf("response" to responseText))
        } catch (e: TimeoutCancellationException) {
            PluginResult.Failure("Xin lỗi, hệ thống phân tích hình ảnh AI đang bận. Vui lòng thử lại sau.")
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi phân tích hình ảnh: ${e.message}")
        }
    }
}