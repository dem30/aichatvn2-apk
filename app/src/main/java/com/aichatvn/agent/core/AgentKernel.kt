package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.core.execution.IntentExecutor
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.SearchMatch
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil

// ✅ Nhập khẩu các file tiện ích mới bạn vừa tạo trong thư mục utils
import com.aichatvn.agent.utils.AccessManager
import com.aichatvn.agent.utils.PromptGuard
import com.aichatvn.agent.utils.ToolCallParser
import com.aichatvn.agent.utils.ToolExecutor
import com.aichatvn.agent.utils.ToolCall

import kotlinx.coroutines.CancellationException // ✅ Khắc phục lỗi nuốt coroutine exception
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

data class DiagnosticTier(
    val tierName: String,
    val tierNum: Int,
    val matched: Boolean,
    val details: String,
    val score: Double = 0.0
)

data class CodeReference(
    val fileName: String,
    val functionName: String,
    val hardcodedRules: String,
    val businessLogic: String
)

data class TraceNode(
    val nodeId: String,
    val label: String,
    val input: String,
    val output: String,
    val matched: Boolean,
    val codeRef: CodeReference,
    val timestampNs: Long = System.nanoTime()
)

data class DiagnosticInfo(
    val query: String,
    val tiers: List<DiagnosticTier>,
    val resolvedIntents: List<String> = emptyList(),
    val resolvedAliases: Map<String, String> = emptyMap(),
    val intentMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val aliasMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val bestAliasMatches: Map<String, Pair<QAEntity, Double>> = emptyMap(),
    val intentThreshold: Float = 0.3f,
    val aliasThreshold: Float = 0.2f,
    val executionOutcome: String? = null,
    val traces: List<TraceNode> = emptyList(),
    val missingParams: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val askedQuestion: String? = null
)

data class LocalCandidate(
    val pluginId: String,
    val action: String,
    val description: String,
    val parameters: List<String>
)

data class Layer2Result(
    val plugin: Plugin,
    val intent: AgentKernel.Intent,
    val confidence: Double
)

data class NormalizedActionMetadata(
    val plugin: Plugin,
    val action: com.aichatvn.agent.core.plugin.PluginAction,
    val normalizedDescription: String,
    val normalizedExamples: List<String>,
    val normalizedTags: List<String>
)

data class PendingResultInfo(
    val plugin: Plugin,
    val actionName: String,
    val result: AgentKernel.PluginResult.NeedMoreInfo
)

sealed class Layer3Result {
    data class Single(val plugin: Plugin, val intent: AgentKernel.Intent) : Layer3Result()
    data class Nested(val wrapper: Plugin, val intent: AgentKernel.Intent) : Layer3Result()
    data class Multi(val intents: List<Pair<Plugin, AgentKernel.Intent>>) : Layer3Result()
    object NoMatch : Layer3Result()
}

enum class PipelineMode { EXECUTE, DIAGNOSTIC }

data class PipelineResult(
    val routerOutcome: AgentKernel.RouterOutcome?,
    val tiers: List<DiagnosticTier> = emptyList(),
    val finalOutcome: String? = null,
    val matchResult: TrainingSkill.MatchResult? = null,
    val traces: List<TraceNode> = emptyList()
)

data class ChatRequest(
    val message: String,
    val username: String = "default_user",
    val imageBase64: String? = null,
    val fileUrl: String? = null,
    val extraContext: String = "",
    val chatMode: String = "COMBINED",
    val allowDeviceControl: Boolean = true
)

data class ChatResponse(
    val responseText: String,
    val usedMode: String,
    val usedPluginId: String?,
    val imagePath: String? = null
)

@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,
    private val routingPipeline: RoutingPipeline,
    private val intentExecutor: IntentExecutor,
    
    // ✅ TIÊM TRỰC TIẾP CÁC LỚP TIỆN ÍCH DỄ MỞ RỘNG (De-couple hoàn toàn)
    private val accessManager: AccessManager,
    private val promptGuard: PromptGuard,
    private val toolCallParser: ToolCallParser,
    private val toolExecutor: ToolExecutor,
    
    private val logger: Logger
) {
    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.manifest.routable }

    suspend fun search(
        query: String,
        username: String,
        threshold: Float? = null
    ): List<SearchMatch> {
        return trainingSkill.fuzzyMatchChatCatalog(query, username, threshold)
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        val message = request.message
        val username = request.username
        val extraContext = request.extraContext
        val imageBase64 = request.imageBase64
        val fileUrl = request.fileUrl

        if (message.isBlank() && imageBase64.isNullOrEmpty() && fileUrl.isNullOrEmpty()) {
            return ChatResponse("", "empty_message_guard", null)
        }

        val expiredNotification = chatHistoryManager.popExpiredNotificationMessage(plugins)

        if (request.allowDeviceControl) {
            val lockedPluginId = chatHistoryManager.getLockedPlugin() // ✅ Kiểm tra trạng thái khóa bảo vệ
            
            for (plugin in plugins) {
                // ✅ VÁ LỖ HỔNG: Nếu đang khóa plugin A, bỏ qua việc quét trigger prefix của plugin B, C...
                if (lockedPluginId != null && plugin.manifest.id != lockedPluginId) continue
                
                for (action in plugin.manifest.actions) {
                    if (!action.enabled) continue
                    val matchedPrefix = action.triggerPrefixes.find { prefix ->
                        message.startsWith(prefix, ignoreCase = true)
                    }
                    if (matchedPrefix != null) {
                        val result = executePluginAction(
                            pluginId = plugin.manifest.id,
                            action = action.name,
                            params = mapOf("message" to message, "username" to username)
                        )
                        val responseText = when (result) {
                            is PluginResult.Success -> {
                                val data = result.data as? Map<*, *>?
                                data?.get("message") as? String ?: "✅ Đã thực hiện câu lệnh tự động."
                            }
                            is PluginResult.Failure -> result.error
                            else -> "❌ Có lỗi phát sinh khi xử lý câu lệnh hệ thống."
                        }
                        val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
                        return ChatResponse(finalMsg, action.name, plugin.manifest.id)
                    }
                }
            }
        }

        if (!imageBase64.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
            val visionPlugin = plugins.find { it.manifest.capabilities.vision }
            if (visionPlugin != null) {
                val actionName = visionPlugin.manifest.actions.firstOrNull()?.name ?: "analyze"
                
                val optimizedMessage = if (message.isBlank()) {
                    "Hãy mô tả nội dung của hình ảnh này bằng tiếng Việt."
                } else {
                    "$message (Hãy trả lời hoàn toàn bằng tiếng Việt)"
                }

                val visionResult = executePluginAction(
                    pluginId = visionPlugin.manifest.id,
                    action = actionName,
                    params = mapOf(
                        "message" to optimizedMessage,
                        "username" to username,
                        "imageBase64" to (imageBase64 ?: ""),
                        "fileUrl" to (fileUrl ?: ""),
                        "extraContext" to extraContext
                    )
                )

                val responseText = when (visionResult) {
                    is PluginResult.Success -> {
                        val data = visionResult.data as? Map<*, *>?
                        data?.get("response") as? String ?: "Không có phản hồi từ mô hình thị giác."
                    }
                    is PluginResult.Failure -> visionResult.error
                    else -> "❌ Gặp lỗi trong quá trình phân tích hình ảnh."
                }
                val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
                return ChatResponse(finalMsg, actionName, visionPlugin.manifest.id)
            } else {
                return ChatResponse("⚠️ Thiết bị hiện không hỗ trợ phân tích hình ảnh (chưa cài đặt Vision Plugin).", "vision_error", null)
            }
        }

        val outcome = if (request.allowDeviceControl) tryDeviceCommand(message, username) else null
        if (outcome is RouterOutcome.Matched) {
            val deviceResult = outcome.result.result
            val responseText = when (deviceResult) {
                is PluginResult.Success -> {
                    val data = deviceResult.data as? Map<*, *>?
                    data?.get("message") as? String ?: "✅ Đã thực hiện thành công."
                }
                is PluginResult.Failure -> deviceResult.error
                is PluginResult.NeedMoreInfo -> deviceResult.question
            }
            val imagePath = (deviceResult as? PluginResult.Success)?.data
                ?.let { (it as? Map<*, *>)?.get("imagePath") as? String }
            val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
            return ChatResponse(finalMsg, "device_control", outcome.result.pluginId, imagePath)
        }

        val routerFailed = outcome is RouterOutcome.RouterFailed
        val usedMode = request.chatMode
        val usedPluginId = if (routerFailed) "router_error" else null

        // ✅ Tận dụng PromptGuard vừa tạo để dựng prompt bảo vệ sạch sẽ
        val guard = promptGuard.buildGuard(routerFailed, (outcome as? RouterOutcome.RouterFailed)?.reason)

        var responseText = try {
            when (usedMode.lowercase()) {
                "qa" -> {
                    withTimeout(15_000L) { // Timeout Pass 1 độc lập
                        val matches = search(message, username)
                        val qa = matches.firstOrNull()?.qa
                        qa?.answer ?: "Không tìm thấy câu trả lời phù hợp trong danh sách huấn luyện."
                    }
                }

                "groq" -> {
                    val historySnapshot = buildHistorySnapshot(username)

                    val cleanContext = buildString {
                        append(guard)
                        if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                    }

                    // Timeout Pass 1 độc lập
                    val responsePass1 = withTimeout(15_000L) {
                        groqClient.chat(
                            message = message,
                            extraContext = cleanContext,
                            history = historySnapshot,
                            imageUrl = null
                        )
                    }

                    // Chặn và kiểm tra thực thi gọi Tool hai lượt tuần tự
                    interceptAndExecuteToolCall(message, responsePass1, username, cleanContext, historySnapshot)
                }

                else -> {
                    val matches = search(message, username)
                    val perfectMatch = matches.firstOrNull()?.qa

                    if (perfectMatch != null) {
                        perfectMatch.answer
                    } else {
                        val historySnapshot = buildHistorySnapshot(username)
                        
                        val qaContext = buildQAContextForAgent(message, username)
                        val fullContext = buildString {
                            append(guard)
                            if (qaContext.isNotEmpty()) append("\n\n$qaContext")
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                        }

                        // Timeout Pass 1 độc lập
                        val responsePass1 = withTimeout(15_000L) {
                            groqClient.chat(
                                message = message,
                                extraContext = fullContext,
                                history = historySnapshot,
                                imageUrl = null
                            )
                        }

                        // Chặn và kiểm tra thực thi gọi Tool hai lượt tuần tự
                        interceptAndExecuteToolCall(message, responsePass1, username, fullContext, historySnapshot)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // ✅ Khôi phục coroutine cancellation, bảo vệ vòng đời ứng dụng
        } catch (e: Exception) {
            logger.e("AgentKernel", "❌ Lỗi hệ thống trong luồng chat: ${e.message}", e)
            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
        }
        
        if (expiredNotification != null) {
            responseText = "$expiredNotification\n\n$responseText"
        }
        return ChatResponse(responseText, usedMode, usedPluginId)
    }

    /**
     * ✅ TỐI ƯU HOÀN HẢO (Two-Pass Interceptor):
     * Phối hợp các class trong thư mục `utils` để kiểm tra tool, kiểm tra quyền, thực thi và gọi Groq lượt 2.
     * Tích hợp Tool Loop Guard khống chế độ sâu lặp tối đa = 1 để chống lỗi hệ thống.
     */
    private suspend fun interceptAndExecuteToolCall(
        originalMessage: String,
        responseRaw: String,
        username: String,
        baseContext: String,
        historySnapshot: List<Map<String, String>>,
        toolDepth: Int = 0 // Khống chế độ sâu lặp (Tool Loop Guard)
    ): String {
        // 1. Phân tích an toàn tìm kiếm chuỗi JSON gọi tool qua ToolCallParser của bạn
        val toolCall = toolCallParser.parse(responseRaw) ?: return responseRaw

        // 2. Chốt chặn chống lặp vô tận (Tool Loop Guard)
        if (toolDepth >= 1) {
            logger.w("AgentKernel", "⚠️ [Tool Loop Guard] Đã đạt giới hạn lặp tool (depth=$toolDepth). Ngắt lặp!")
            return "Hệ thống phát hiện yêu cầu tìm kiếm lặp lại quá nhiều lần, xin lỗi vì chưa thể hoàn thành chi tiết này."
        }

        // 3. Chốt chặn bảo mật (De-couple thông qua AccessManager của bạn)
        if (!accessManager.canReadEventLog(username)) {
            logger.w("AgentKernel", "⚠️ Chốt chặn bảo mật chặn đứng yêu cầu đọc nhật ký từ: '$username'")
            return "Dạ, để bảo vệ quyền riêng tư của gia đình, nhật ký hoạt động camera và thiết bị chỉ có thể được truy cập bởi tài khoản Quản trị viên (Chủ nhà) trên ứng dụng nội bộ thôi ạ."
        }

        logger.i("AgentKernel", "📥 [Two-Pass Intercepted] AI yêu cầu gọi Tool: '${toolCall.tool}'")

        // 4. Ủy quyền thực thi Tool qua ToolExecutor tập trung của bạn
        val toolResult = toolExecutor.execute(toolCall)
        
        if (!toolResult.success) {
            return toolResult.payload // Nếu thực thi tool thất bại (hoặc lỗi), trả thông báo lỗi thân thiện
        }

        // 5. Đóng gói cấu trúc Tag tiêu chuẩn <SYSTEM_MEMORY> gửi kèm ngữ cảnh cho AI
        val enrichedContext = buildString {
            append(baseContext)
            append("\n\n")
            append("<SYSTEM_MEMORY>\n")
            append(toolResult.payload)
            append("\n\n👉 CHỈ THỊ: Hãy sử dụng dữ liệu thực tế chính xác trong tag <SYSTEM_MEMORY> này để trả lời đầy đủ câu hỏi của người dùng. TUYỆT ĐỐI không được bịa đặt mốc thời gian không có trong tag.")
            append("\n</SYSTEM_MEMORY>")
        }

        logger.i("AgentKernel", "🚀 [Two-Pass Second Call] Đang gửi lại dữ liệu thực tế lên Groq lượt 2 (Timeout: 15 giây)...")

        // 6. Lượt gọi Groq 2 (Second Pass) mang theo dữ liệu thật với Timeout riêng biệt 15 giây
        return try {
            withTimeout(15_000L) {
                val responsePass2 = groqClient.chat(
                    message = originalMessage,
                    extraContext = enrichedContext,
                    history = historySnapshot,
                    imageUrl = null
                )
                
                // Đệ quy tự vệ chống lặp
                interceptAndExecuteToolCall(
                    originalMessage = originalMessage,
                    responseRaw = responsePass2,
                    username = username,
                    baseContext = enrichedContext,
                    historySnapshot = historySnapshot,
                    toolDepth = toolDepth + 1
                )
            }
        } catch (e: CancellationException) {
            throw e // ✅ Khôi phục coroutine cancellation, bảo vệ vòng đời ứng dụng
        } catch (e: Exception) {
            logger.e("AgentKernel", "⚠️ Gặp lỗi khi xử lý dữ liệu Two-Pass Loop, quay về Fallback lượt 1: ${e.message}", e)
            responseRaw
        }
    }

    // ✅ MỚI: Cắt ngắn lịch sử chat gửi kèm Groq để chống Token Bloat, thay vì gửi nguyên văn 6 tin nhắn.
    // ... (Bảo lưu nguyên vẹn truncateSmart, buildHistorySnapshot, buildQAContextForAgent, tryDeviceCommand, explainDeviceCommand, executePluginAction, process, isExitLockPhrase, parseYesNo, detectLockTrigger, handleLockConfirmation, getLockedPluginId, getLockedPluginName, Intent, DeviceCommandResult, RouterOutcome, PluginResult) ...
}