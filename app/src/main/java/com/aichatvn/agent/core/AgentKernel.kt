

package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.SearchMatch
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import com.aichatvn.agent.utils.DateTimeParser
import org.json.JSONObject
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

data class DiagnosticTier(
    val tierName: String,
    val tierNum: Int,
    val matched: Boolean,
    val details: String,
    val score: Double = 0.0
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
    val executionOutcome: String? = null
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
    val action: PluginAction,
    val normalizedDescription: String,
    val normalizedExamples: List<String>,
    val normalizedTags: List<String>
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
    val matchResult: TrainingSkill.MatchResult? = null
)

data class ChatRequest(
    val message: String,
    val username: String = "default_user",
    val imageBase64: String? = null,
    val fileUrl: String? = null,
    val extraContext: String = "",
    val chatMode: String = "COMBINED"
)

data class ChatResponse(
    val responseText: String,
    val usedMode: String,
    val usedPluginId: String?
)

@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,
    private val logger: Logger,
    private val dialogManager: DialogManager
) {

    companion object {
        private const val MAX_DEPTH = 3
    }

    private val actionCandidates: List<LocalCandidate> by lazy {
        plugins.flatMap { plugin ->
            plugin.manifest.actions.map { act ->
                LocalCandidate(
                    pluginId = plugin.manifest.id,
                    action = act.name,
                    description = act.description,
                    parameters = act.parameters.map { if (it.required) it.name else "${it.name}?" }
                )
            }
        }
    }

    private val normalizedActionMetadataList: List<NormalizedActionMetadata> by lazy {
        plugins.flatMap { plugin ->
            plugin.manifest.actions.map { action ->
                NormalizedActionMetadata(
                    plugin = plugin,
                    action = action,
                    normalizedDescription = StringSimilarityUtil.normalizeVietnamese(action.description),
                    normalizedExamples = action.examples.map { StringSimilarityUtil.normalizeVietnamese(it) },
                    normalizedTags = action.tags.map { StringSimilarityUtil.normalizeVietnamese(it) }
                )
            }
        }
    }

    private val resolverTable: Map<String, suspend (
        param: PluginParameter,
        currentValue: Any?,
        isPlh: Boolean,
        context: RoutingContext,
        secondaryIntentQA: QAEntity?,
        devicePlugins: List<Plugin>,
        excludeIntentId: String?,
        depth: Int
    ) -> Any?> = mapOf(
        "time" to { _, currentValue, _, context, _, _, _, _ ->
            context.localEntities["cron"] ?: currentValue ?: ""
        },
        "interval" to { _, currentValue, _, context, _, _, _, _ ->
            context.localEntities["intervalMinutes"] ?: currentValue ?: 0
        },
        "plugin_id" to { _, currentValue, isPlh, _, secondaryIntent, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolvePluginIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "action_id" to { _, currentValue, isPlh, _, secondaryIntent, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolveActionIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "params" to { param, currentValue, _, context, secondaryIntent, devicePlugins, excludeId, depth ->
            resolveNestedParams(param, currentValue, context, secondaryIntent, devicePlugins, excludeId, depth)
        }
    )

    private fun generateTraceId(): String = "TR-${System.currentTimeMillis() % 100000}-${(100..999).random()}"

    private fun PendingIntent.toPendingState(): PendingState = PendingState(
        isActive = true,
        pluginId = this.pluginId,
        action = this.action,
        missingParams = this.missingParams,
        askedQuestion = this.askedQuestion
    )

    private fun emptyPendingState(): PendingState = PendingState(isActive = false)

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.manifest.routable }

    suspend fun search(
        query: String,
        username: String,
        threshold: Float? = null
    ): List<SearchMatch> {
        return trainingSkill.fuzzyMatchQuestion(query, username, threshold)
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        val message = request.message
        val username = request.username
        val extraContext = request.extraContext
        val imageBase64 = request.imageBase64
        val fileUrl = request.fileUrl
        
        for (plugin in plugins) {
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
                            val data = result.data as? Map<*, *>
                            data?.get("message") as? String ?: "✅ Đã thực hiện câu lệnh tự động."
                        }
                        is PluginResult.Failure -> result.error
                        else -> "❌ Có lỗi phát sinh khi xử lý câu lệnh hệ thống."
                    }
                    return ChatResponse(responseText, action.name, plugin.manifest.id)
                }
            }
        }
        
        if (!imageBase64.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
            val visionPlugin = plugins.find { it.manifest.capabilities.vision }
            if (visionPlugin != null) {
                val actionName = visionPlugin.manifest.actions.firstOrNull()?.name ?: "analyze"
                val visionResult = executePluginAction(
                    pluginId = visionPlugin.manifest.id,
                    action = actionName,
                    params = mapOf(
                        "message" to message,
                        "username" to username,
                        "imageBase64" to (imageBase64 ?: ""),
                        "fileUrl" to (fileUrl ?: ""),
                        "extraContext" to extraContext
                    )
                )
                val responseText = when (visionResult) {
                    is PluginResult.Success -> {
                        val data = visionResult.data as? Map<*, *>
                        data?.get("response") as? String ?: "Không có phản hồi từ mô hình thị giác."
                    }
                    is PluginResult.Failure -> visionResult.error
                    else -> "❌ Gặp lỗi trong quá trình phân tích hình ảnh."
                }
                return ChatResponse(responseText, actionName, visionPlugin.manifest.id)
            } else {
                return ChatResponse("⚠️ Thiết bị hiện không hỗ trợ phân tích hình ảnh (chưa cài đặt Vision Plugin).", "vision_error", null)
            }
        }
        
        val outcome = tryDeviceCommand(message, username)
        if (outcome is RouterOutcome.Matched) {
            val responseText = when (val result = outcome.result.result) {
                is PluginResult.Success -> {
                    val data = result.data as? Map<*, *>
                    data?.get("message") as? String ?: "✅ Đã thực hiện thành công."
                }
                is PluginResult.Failure -> result.error
                is PluginResult.NeedMoreInfo -> result.question
            }
            return ChatResponse(responseText, "device_control", outcome.result.pluginId)
        }
        
        val routerFailed = outcome is RouterOutcome.RouterFailed
        val usedMode = request.chatMode
        val usedPluginId = if (routerFailed) "router_error" else null
        
        val ANTI_HALLUCINATION_GUARD =
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được."

        val qaContext = buildQAContextForAgent(message, username)
        val guard = if (routerFailed) {
            ANTI_HALLUCINATION_GUARD + "\n" +
                "⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định được chính xác (lý do nội bộ: ${(outcome as RouterOutcome.RouterFailed).reason}). Hãy báo cho user là lệnh CHƯA thực hiện được và hỏi họ nói rõ hơn, ĐỪNG khẳng định đã làm."
        } else {
            ANTI_HALLUCINATION_GUARD
        }
        val fullContext = buildString {
            append(guard)
            if (qaContext.isNotEmpty()) append("\n\n$qaContext")
            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
        }

        val responseText = try {
            withTimeout(30_000L) {
                when (usedMode.lowercase()) {
                    "qa" -> {
                        val matches = search(message, username, 0.6f)
                        val qa = matches.firstOrNull()?.qa
                        qa?.answer ?: "Không tìm thấy câu trả lời phù hợp trong danh sách huấn luyện."
                    }
                    else -> {
                        val historySnapshot = try {
                            database.chatMessageDao().getMessages(username, 6)
                                .reversed()
                                .map { mapOf("role" to it.role, "content" to it.content) }
                        } catch (e: Exception) {
                            emptyList()
                        }
                        groqClient.chat(
                            message = message,
                            extraContext = fullContext,
                            history = historySnapshot,
                            imageUrl = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
        }
        
        return ChatResponse(responseText, usedMode, usedPluginId)
    }

    private suspend fun buildQAContextForAgent(message: String, username: String): String {
        val matches = search(message, username, 0.7f)
        if (matches.isEmpty()) return ""
        return matches.joinToString("\n") { match ->
            "📚 Q: ${match.qa.question}\n   A: ${match.qa.answer} (độ tương tự: ${String.format("%.2f", match.similarity)})"
        }
    }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = generateTraceId()
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")

        // ── KHÓA ĐIỀU KHIỂN: (a) Đang trong hàng đợi chờ xác nhận Có/Không ──
        chatHistoryManager.pendingLockRequest?.let { pluginId ->
            return handleLockConfirmation(userMessage, pluginId)
        }

        // ── KHÓA ĐIỀU KHIỂN: (b) Đang khóa cứng điều khiển 1 plugin duy nhất ──
        chatHistoryManager.getLockedPlugin()?.let { lockedId ->
            if (isExitLockPhrase(userMessage)) {
                chatHistoryManager.unlockPlugin()
                val matchedPlugin = plugins.find { it.manifest.id == lockedId }
                val displayName = matchedPlugin?.manifest?.name ?: lockedId
                return RouterOutcome.Matched(
                    DeviceCommandResult(lockedId, PluginResult.Success(mapOf("message" to "✅ Đã thoát chế độ điều khiển riêng cho \"$displayName\".")))
                )
            }
            // Gọi chạy pipeline và ép buộc chỉ cho phép Plugin đang bị khóa hoạt động
            val result = runPipeline(userMessage, username, PipelineMode.EXECUTE, traceId, forcedPluginIds = listOf(lockedId))
            val outcome = result.routerOutcome
            return if (outcome == null || outcome is RouterOutcome.NotACommand) {
                RouterOutcome.Matched(
                    DeviceCommandResult(lockedId, PluginResult.Failure("⚠️ Tôi không hiểu lệnh điều khiển riêng cho \"$lockedId\". Vui lòng nói lại, hoặc gõ \"thoát\" để quay lại chat thường."))
                )
            } else {
                outcome
            }
        }

        // ── KHÓA ĐIỀU KHIỂN: (c) Phát hiện câu lệnh kích hoạt khóa điều khiển ──
        detectLockTrigger(userMessage)?.let { targetPluginId ->
            chatHistoryManager.setPendingLockRequest(targetPluginId)
            val matchedPlugin = plugins.find { it.manifest.id == targetPluginId }
            val displayName = matchedPlugin?.manifest?.name ?: targetPluginId
            return RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Bạn muốn vào chế độ điều khiển riêng biệt cho \"$displayName\", đúng không?")))
            )
        }

        // ── Luồng xử lý bình thường thông thường khi không có khóa điều khiển ──
        val result = runPipeline(userMessage, username, PipelineMode.EXECUTE, traceId)
        return result.routerOutcome ?: RouterOutcome.RouterFailed("Pipeline execution error")
    }

    private suspend fun runPipeline(
        userMessage: String,
        username: String,
        mode: PipelineMode,
        traceId: String,
        forcedPluginIds: List<String>? = null // THÊM: Cho phép ép buộc danh sách plugin cụ thể
    ): PipelineResult {
        val devicePlugins = plugins.filter { 
            it.manifest.routable && (forcedPluginIds == null || it.manifest.id in forcedPluginIds) // SỬA: Lọc theo danh sách ép buộc nếu có
        }
        if (devicePlugins.isEmpty()) {
            return PipelineResult(routerOutcome = RouterOutcome.NotACommand)
        }

        val simulatedTiers = mutableListOf<DiagnosticTier>()
        var finalOutcome: String? = null
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        val tier2HighConf = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.80f)

        // ── TẦNG 0a: CANCEL RESOLVER ──
        val currentPendingForCancel = chatHistoryManager.getActivePendingIntents().firstOrNull()
        val pendingStateForCancel = currentPendingForCancel?.toPendingState() ?: emptyPendingState()
        val cancelDecision = dialogManager.resolveCancel(userMessage, pendingStateForCancel)

        if (cancelDecision is CancelDecision.CancelPending) {
            val cancelledPluginId = cancelDecision.pluginId ?: "không xác định"
            val cancelledAction = cancelDecision.action ?: "lệnh trước đó"

            if (mode == PipelineMode.EXECUTE) {
                if (cancelDecision.pluginId != null && cancelDecision.action != null) {
                    chatHistoryManager.removePendingIntent(cancelDecision.pluginId, cancelDecision.action)
                } else {
                    chatHistoryManager.clearPendingIntent()
                }
                val replyText = "✅ Đã huỷ lệnh \"$cancelledAction\" của \"$cancelledPluginId\"."
                chatHistoryManager.addTurn(userMessage, replyText)
                logger.d("AgentKernel", "[$traceId] [Tầng 0] Cancel Resolver xác nhận huỷ pending: $cancelledPluginId.$cancelledAction")
                return PipelineResult(
                    routerOutcome = RouterOutcome.Matched(
                        DeviceCommandResult(cancelledPluginId, PluginResult.Success(mapOf("message" to replyText)))
                    )
                )
            } else {
                finalOutcome = "✅ Đã huỷ lệnh trước đó của \"$cancelledPluginId.$cancelledAction\" thành công."
            }
        }

        // ── TẦNG 0b: PRONOUN RESOLVER ──
        val pronounResult = dialogManager.resolvePronoun(userMessage, username, System.currentTimeMillis())
        val resolvedMessage = pronounResult.rewrittenMessage
        if (pronounResult.wasResolved) {
            logger.d("AgentKernel", "[$traceId] [Tầng 0] Pronoun Resolver: \"${pronounResult.resolvedFrom}\" -> \"${pronounResult.resolvedTo}\". Câu sau khi viết lại: \"$resolvedMessage\"")
        }

        // ── TẦNG 1: PENDING RESOLVER ──
        val pendings = chatHistoryManager.getActivePendingIntents()
        val isT1Matched = pendings.isNotEmpty() && cancelDecision !is CancelDecision.CancelPending

        if (mode == PipelineMode.EXECUTE) {
            if (pendings.isNotEmpty()) {
                logger.d("AgentKernel", "[$traceId] [Tầng 1] Phát hiện ${pendings.size} tiến trình dở dang Pending Intents")
                val results = mutableListOf<String>()
                var allSucceeded = true

                // CHỈ XỬ LÝ LỆNH DỞ DANG ĐẦU TIÊN để thực hiện tuần tự hóa (Sequential Slot-Filling)
                val activePending = pendings.first()
                val resolvedResult = tryResolvePendingIntent(activePending, userMessage, devicePlugins, traceId, mode)
                if (resolvedResult != null) {
                    val r = resolvedResult.result
                    val msg = when (r) {
                        is PluginResult.Success -> {
                            chatHistoryManager.removePendingIntent(activePending.pluginId, activePending.action)
                            (r.data as? Map<*, *>)?.get("message") as? String ?: "✅ Thực hiện thành công"
                        }
                        is PluginResult.Failure -> {
                            chatHistoryManager.removePendingIntent(activePending.pluginId, activePending.action)
                            "❌ Lỗi: ${r.error}"
                        }
                        is PluginResult.NeedMoreInfo -> {
                            allSucceeded = false
                            r.question
                        }
                    }
                    results.add(msg)
                }

                if (results.isNotEmpty()) {
                    val combined = results.distinct().joinToString("\n")
                    val finalResult = if (allSucceeded) {
                        // Nếu lệnh dở dang hiện tại đã xử lý xong, kiểm tra xem trong hàng đợi còn lệnh dở dang tiếp theo nào không
                        val remainingPendings = chatHistoryManager.getActivePendingIntents()
                        if (remainingPendings.isNotEmpty()) {
                            // Lấy lệnh dở dang tiếp theo và lập tức hỏi người dùng thay vì kết thúc luồng chat
                            val nextPending = remainingPendings.first()
                            val combinedMsg = "$combined\n\n⚠️ Tiếp theo: ${nextPending.askedQuestion}"
                            PluginResult.NeedMoreInfo(nextPending.missingParams, combinedMsg)
                        } else {
                            PluginResult.Success(mapOf("message" to combined))
                        }
                    } else {
                        val remainingPending = chatHistoryManager.getActivePendingIntents()
                        PluginResult.NeedMoreInfo(remainingPending.flatMap { it.missingParams }, combined)
                    }
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult("multi_pending", finalResult))
                    )
                }
            }
        } else {
            if (isT1Matched && finalOutcome == null) {
                // Đã tối ưu truyền biến 'mode' ở dạng DIAGNOSTIC xuống bộ Pending Resolver kiểm thử đầu tiên
                val pendingResult = tryResolvePendingIntent(pendings.first(), userMessage, devicePlugins, traceId, mode)
                finalOutcome = if (pendingResult != null) {
                    when (val r = pendingResult.result) {
                        is PluginResult.Success -> "✅ [Tầng 1 Chạy Thật Thành Công] ${(r.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                        is PluginResult.Failure -> "❌ [Tầng 1 Chạy Thật Thất Bại] ${r.error}"
                        is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 1 Yêu Cầu Nhập Thêm] ${r.question} (Danh sách thiếu: ${r.missingParams.joinToString()})"
                    }
                } else {
                    "🔵 [Tầng 1 Bỏ Qua] Tiến trình dở dang không liên quan đến câu trả lời."
                }
            }

            val t1Details = buildString {
                if (pronounResult.wasResolved) {
                    append("• [Tầng 0] Pronoun: Thay thế đại từ thành công \"${pronounResult.resolvedFrom}\" ➔ \"${pronounResult.resolvedTo}\". Câu xử lý tiếp theo: \"$resolvedMessage\"\n")
                }
                if (cancelDecision is CancelDecision.CancelPending) {
                    append("• [Tầng 0] Cancel: Xác nhận hủy tiến trình dở dang: ${cancelDecision.pluginId}.${cancelDecision.action}\n")
                }
                if (pendings.isNotEmpty()) {
                    append("• Phát hiện ${pendings.size} tiến trình chưa hoàn thành: ")
                    append(pendings.joinToString { "${it.pluginId}.${it.action}" })
                } else {
                    append("• Không phát hiện hàng đợi dở dang nào đang chờ.")
                }
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 1: Trạng thái lệnh dở dang (Pending Intents)",
                    tierNum = 1,
                    matched = isT1Matched || cancelDecision is CancelDecision.CancelPending,
                    details = t1Details
                )
            )
        }

        // KHỞI TẠO ROUTING CONTEXT (TÍNH TOÁN 1 LẦN DUY NHẤT VỚI ALIAS THRESHOLD ĐỘNG)
        val matchResult = trainingSkill.fuzzyMatchCategorized(
            resolvedMessage, 
            username, 
            intentThreshold = intentThreshold,
            aliasThreshold = aliasThreshold // Sửa đổi: Áp dụng aliasThreshold cấu hình động thay vì 0.0f cứng
        )

        val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
        val clauses = clauseSeparator.split(resolvedMessage).map { it.trim() }.filter { it.isNotBlank() }

        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(resolvedMessage)?.value?.let { localEntities["email"] = it }
        DateTimeParser.parseVietnameseTime(resolvedMessage)?.let { localEntities["cron"] = it }
        DateTimeParser.parseVietnameseInterval(resolvedMessage)?.let { localEntities["intervalMinutes"] = it }

        val context = RoutingContext(
            originalQuery = userMessage,
            resolvedQuery = resolvedMessage,
            username = username,
            clauses = clauses,
            globalMatchResult = matchResult,
            localEntities = localEntities
        )

        // ── TẦNG 2A: MÀNG LỌC PHỦ ĐỊNH CỤC BỘ ──
        val lowerMsg = resolvedMessage.lowercase().trim()
        val negationWords = setOf("không", "chưa", "đừng", "hủy", "huỷ", "không muốn", "đừng có")
        val hasNegation = negationWords.any { word -> 
            Regex("(?<!\\p{L})$word(?!\\p{L})").containsMatchIn(lowerMsg)
        }

        if (hasNegation) {
            if (mode == PipelineMode.EXECUTE) {
                logger.d("AgentKernel", "[$traceId] ⚠️ Phát hiện từ khóa phủ định -> Bypass thẳng xuống Tầng 5 (LLM) để hiểu đúng ngữ nghĩa.")
                return PipelineResult(
                    routerOutcome = executeTier3LlmRouting(context, devicePlugins, traceId),
                    matchResult = matchResult
                )
            } else {
                if (finalOutcome == null) {
                    finalOutcome = "🔵 [Màng lọc Phủ định Cục bộ] Phát hiện từ khóa phủ định trong câu lệnh. Hệ thống bypass toàn bộ bộ lọc tĩnh xuống Tầng 5 (LLM Fallback) để đảm bảo hiểu đúng ngữ nghĩa."
                }
                simulatedTiers.add(DiagnosticTier("Tầng 2: So khớp Ý định tĩnh (Exact/Fuzzy Intent Match)", 2, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
                simulatedTiers.add(DiagnosticTier("Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)", 3, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
                simulatedTiers.add(DiagnosticTier("Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)", 4, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
                simulatedTiers.add(DiagnosticTier("Tầng 5: Phân loại thông minh bằng AI (LLM Fallback)", 5, true, "• [KÍCH HOẠT] Đã chuyển xuống LLM xử lý."))
                return PipelineResult(
                    routerOutcome = null,
                    tiers = simulatedTiers,
                    finalOutcome = finalOutcome,
                    matchResult = matchResult
                )
            }
        }

        // ── TẦNG 2: SO KHỚP Ý ĐỊNH TĨNH ──
        val layer2Result = tryTier2SemanticSlotResolver(context, devicePlugins)
        val isT2Matched = layer2Result != null && layer2Result.confidence >= tier2HighConf

        if (mode == PipelineMode.EXECUTE) {
            if (layer2Result != null) {
                if (isT2Matched) {
                    val plugin = layer2Result.plugin
                    val intent = layer2Result.intent
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 2 Hit - Confidence: ${layer2Result.confidence}] ${intent.pluginId}.${intent.action}")
                    return try {
                        val result = executeIntent(plugin, intent, context, traceId)
                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result)),
                            matchResult = matchResult
                        )
                    } catch (e: Exception) {
                        logger.e("AgentKernel", "[$traceId] Tầng 2 execute error: ${e.message}", e)
                        PipelineResult(
                            routerOutcome = RouterOutcome.RouterFailed("Tầng 2 execute failed: ${e.message}"),
                            matchResult = matchResult
                        )
                    }
                } else {
                    logger.d("AgentKernel", "[$traceId] ⚠️ [Tầng 2 Low Confidence: ${layer2Result.confidence}] -> Chuyển sang Tầng 3")
                }
            }
        } else {
            if (isT2Matched && finalOutcome == null) {
                val executionResult = executeIntent(layer2Result!!.plugin, layer2Result.intent, context, traceId)
                finalOutcome = when (executionResult) {
                    is PluginResult.Success -> "✅ [Tầng 2 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                    is PluginResult.Failure -> "❌ [Tầng 2 Chạy Thật Thất Bại] ${executionResult.error}"
                    is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 2 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                }
            }
            val t2Details = when {
                layer2Result == null -> "• Không tìm thấy ý định mẫu nào khớp với nội dung đã nhập."
                isT2Matched -> "• Khớp ý định '${layer2Result.intent.pluginId}.${layer2Result.intent.action}' với điểm số cao (${String.format("%.2f", layer2Result.confidence)} >= $tier2HighConf). Lệnh được bypass thực thi trực tiếp."
                else -> "• Ý định '${layer2Result.intent.pluginId}.${layer2Result.intent.action}' chưa đạt điểm tin cậy thực thi nhanh (${String.format("%.2f", layer2Result.confidence)} < $tier2HighConf). Chuyển xuống Tầng 3."
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 2: So khớp Ý định tĩnh (Exact/Fuzzy Intent Match)",
                    tierNum = 2,
                    matched = isT2Matched,
                    score = layer2Result?.confidence ?: 0.0,
                    details = t2Details
                )
            )
        }

        // ── TẦNG 3: TÁCH MỆNH ĐỀ ĐA LỆNH & SLOT-FILLING ──
        val layer3Result = if (mode == PipelineMode.EXECUTE || !isT2Matched) {
            processLayer3ClauseEntitySpotter(context, devicePlugins, traceId)
        } else {
            Layer3Result.NoMatch
        }

        if (mode == PipelineMode.EXECUTE) {
            when (layer3Result) {
                is Layer3Result.Single -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Single] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                    val result = executeIntent(layer3Result.plugin, layer3Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer3Result.plugin.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Nested -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Nested] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                    val result = executeIntent(layer3Result.wrapper, layer3Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer3Result.wrapper.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Multi -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Multi] ${layer3Result.intents.size} actions")
                    val results = mutableListOf<String>()
                    layer3Result.intents.forEach { (plugin, intent) ->
                        try {
                            val r = executeIntent(plugin, intent, context, traceId)
                            val msg = when (r) {
                                is PluginResult.Success -> (r.data as? Map<*, *>)?.get("message") as? String ?: "✅ ${intent.pluginId}.${intent.action}"
                                is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action}: ${r.error}"
                                is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action}: ${r.question}"
                            }
                            results.add(msg)
                        } catch (e: Exception) {
                            results.add("❌ ${intent.pluginId}.${intent.action}: ${e.message}")
                        }
                    }
                    val combined = results.joinToString("\n")
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to combined)))),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.NoMatch -> { }
            }
        } else {
            if (layer3Result !is Layer3Result.NoMatch && finalOutcome == null) {
                finalOutcome = when (layer3Result) {
                    is Layer3Result.Single -> {
                        val executionResult = executeIntent(layer3Result.plugin, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = executeIntent(layer3Result.wrapper, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer3Result.intents.forEach { (plugin, intent) ->
                            val executionResult = executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
                            val msg = when (executionResult) {
                                is PluginResult.Success -> "✅ ${intent.pluginId}.${intent.action} thành công."
                                is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action} thất bại: ${executionResult.error}"
                                is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action} thiếu: ${executionResult.question}"
                            }
                            results.add(msg)
                        }
                        "✅ [Tầng 3 Chạy Thật Đa Lệnh]:\n" + results.joinToString("\n")
                    }
                    else -> null
                }
            }
            val t3Details = when (layer3Result) {
                is Layer3Result.Single -> {
                    val missing = ParameterResolver.getUnresolvedParams(layer3Result.intent.params, layer3Result.plugin, layer3Result.intent.action, plugins)
                    if (missing.isNotEmpty()) {
                        "• Tách thành công câu lệnh đơn '${layer3Result.intent.pluginId}.${layer3Result.intent.action}'. Phát hiện thiếu thông tin: [${missing.joinToString { getQuestionForMissingParam(it, layer3Result.plugin, layer3Result.intent.action) }}]"
                    } else {
                        "• Tách thành công câu lệnh đơn và điền đủ tham số: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
                    }
                }
                is Layer3Result.Nested -> "• Khớp bộ khung lập lịch (schedule) lồng hành động: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
                is Layer3Result.Multi -> "• Phân tích cú pháp đa hành động (song song/tuần tự): ${layer3Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
                else -> "• Không tìm thấy hoặc cấu trúc câu không chứa mệnh đề/từ khóa lập lịch đạt chuẩn hoặc độ bao phủ mệnh đề chưa đạt 80%."
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)",
                    tierNum = 3,
                    matched = layer3Result !is Layer3Result.NoMatch,
                    details = t3Details
                )
            )
        }

        // ── TẦNG 4: SO KHỚP MÔ TẢ & NHÃN PLUGIN ──
        val layer4Result = if (mode == PipelineMode.EXECUTE || (!isT2Matched && layer3Result is Layer3Result.NoMatch)) {
            tryTier2_5ActionMetadataMatcher(context, devicePlugins)
        } else {
            Layer3Result.NoMatch
        }

        if (mode == PipelineMode.EXECUTE) {
            when (layer4Result) {
                is Layer3Result.Single -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Single] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                    val result = executeIntent(layer4Result.plugin, layer4Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer4Result.plugin.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Nested -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Nested] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                    val result = executeIntent(layer4Result.wrapper, layer4Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer4Result.wrapper.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Multi -> {
                    logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Multi] ${layer4Result.intents.size} actions")
                    val results = mutableListOf<String>()
                    layer4Result.intents.forEach { (plugin, intent) ->
                        try {
                            val r = executeIntent(plugin, intent, context, traceId)
                            val msg = when (r) {
                                is PluginResult.Success -> "✅ ${intent.pluginId}.${intent.action} thành công."
                                is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action} thất bại: ${r.error}"
                                is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action} thiếu: ${r.question}"
                            }
                            results.add(msg)
                        } catch (e: Exception) {
                            results.add("❌ ${intent.pluginId}.${intent.action}: ${e.message}")
                        }
                    }
                    val combined = results.joinToString("\n")
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to combined)))),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.NoMatch -> { }
            }

            logger.d("AgentKernel", "[$traceId] 🔵 [Tầng 4 Miss] -> LLM")
            return PipelineResult(
                routerOutcome = executeTier3LlmRouting(context, devicePlugins, traceId),
                matchResult = matchResult
            )
        } else {
            if (layer4Result !is Layer3Result.NoMatch && finalOutcome == null) {
                finalOutcome = when (layer4Result) {
                    is Layer3Result.Single -> {
                        val executionResult = executeIntent(layer4Result.plugin, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = executeIntent(layer4Result.wrapper, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer4Result.intents.forEach { (plugin, intent) ->
                            val executionResult = executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
                            val msg = when (executionResult) {
                                is PluginResult.Success -> "✅ ${intent.pluginId}.${intent.action} thành công."
                                is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action} thất bại: ${executionResult.error}"
                                is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action} thiếu: ${executionResult.question}"
                            }
                            results.add(msg)
                        }
                        "✅ [Tầng 4 Chạy Thật Đa Lệnh]:\n" + results.joinToString("\n")
                    }
                    else -> null
                }
            }
            val t4Details = when (layer4Result) {
                is Layer3Result.Single -> "• Khớp 1 lệnh thông qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
                is Layer3Result.Nested -> "• Khớp cấu trúc lập lịch qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
                is Layer3Result.Multi -> "• Khớp đa lệnh qua Meta Manifest: ${layer4Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
                else -> "• Không khớp với mô tả action hoặc nhãn ví dụ trong Manifest của các Plugin hoặc độ bao phủ mệnh đề chưa đạt 80%."
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)",
                    tierNum = 4,
                    matched = layer4Result !is Layer3Result.NoMatch,
                    details = t4Details
                )
            )

            // ── TẦNG 5: KHÔNG GỌI THẬT TRONG DIAGNOSTIC ──
            val isT5Matched = !isT2Matched && layer3Result is Layer3Result.NoMatch && layer4Result is Layer3Result.NoMatch
            if (isT5Matched && finalOutcome == null) {
                finalOutcome = "🔵 [Bypass Tầng 5] Đã chuyển xuống Tầng 5 để LLM phân giải tự do. Hệ thống không tự động chạy thật ở tầng này nhằm tiết kiệm token API Groq."
            }
            val t5Details = if (isT5Matched) {
                "• Không khớp bất kỳ mẫu tĩnh hay heuristic nào. Câu lệnh sẽ được gửi lên Groq LLM để phân rã tự do."
            } else {
                "• Bypass (Bỏ qua gọi LLM) nhằm tiết kiệm tài nguyên do các tầng heuristic phía trên đã giải xong."
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 5: Phân loại thông minh bằng AI (LLM Fallback)",
                    tierNum = 5,
                    matched = isT5Matched,
                    details = t5Details
                )
            )

            return PipelineResult(
                routerOutcome = null,
                tiers = simulatedTiers,
                finalOutcome = finalOutcome,
                matchResult = matchResult
            )
        }
    }

    private suspend fun processLayer3ClauseEntitySpotter(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        traceId: String
    ): Layer3Result {
        val intentQAs = trainingSkill.getRawCachedQAList(context.username)
            .filter { it.type == "intent" }
            .sortedByDescending { it.question.length }

        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()
        
        for (clause in context.clauses) {
            val clauseNorm = StringSimilarityUtil.normalizeVietnamese(clause)
            
            // 1. Trích xuất tất cả Alias xuất hiện trong mệnh đề này
            val matchedAliases = context.globalMatchResult.aliasMatches.filter { 
                val aliasNorm = StringSimilarityUtil.normalizeVietnamese(it.first.question)
                clauseNorm.contains(aliasNorm) 
            }
            
            // 2. Trích xuất tất cả Intent không gối lên nhau trong mệnh đề này
            val matchedIntents = mutableListOf<QAEntity>()
            var tempClause = clauseNorm
            val sortedIntents = intentQAs.sortedByDescending { it.question.length }
            for (qa in sortedIntents) {
                val qNorm = StringSimilarityUtil.normalizeVietnamese(qa.question)
                if (qNorm.isBlank()) continue
                if (tempClause.contains(qNorm)) {
                    matchedIntents.add(qa)
                    tempClause = tempClause.replace(qNorm, " ".repeat(qNorm.length))
                }
            }

            // 3. Tính toán tỷ lệ bao phủ của Heuristic tĩnh
            val totalMatchedLength = matchedIntents.sumOf { it.question.length } + 
                                     matchedAliases.sumOf { it.first.question.length }
            
            if (clause.isEmpty()) continue
            val coverageRatio = totalMatchedLength.toDouble() / clause.length

            if (coverageRatio < 0.80) {
                logger.d("AgentKernel", "[$traceId] ⚠️ Tỷ lệ bao phủ mệnh đề '$clause' quá thấp (${String.format("%.2f", coverageRatio)} < 0.80). Bỏ qua Tầng 3.")
                continue
            }

            val uniqueTypes = matchedAliases.map { it.first.category }.distinct()
            val hasDuplicateTypes = matchedAliases.size != uniqueTypes.size

            val isIntentDouble = matchedIntents.size == 1 && matchedAliases.size > 1 && uniqueTypes.size == 1
            val isMultiIntent = matchedIntents.size > 1 && !hasDuplicateTypes

            when {
                isIntentDouble -> {
                    val singleIntentQA = matchedIntents.first()
                    val rootJson = try { JSONObject(singleIntentQA.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        
                        for (alias in matchedAliases) {
                            val resolvedParams = resolveParametersWithMeta(
                                parameters = targetAction.parameters,
                                inputParams = rootParams,
                                context = context.copy(globalMatchResult = matchResultCopyForSingleAlias(context.globalMatchResult, alias.first)),
                                excludeIntentId = singleIntentQA.id,
                                depth = 0
                            )
                            resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                        }
                    }
                }
                
                isMultiIntent -> {
                    for (qa in matchedIntents) {
                        val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                        val rootPluginId = rootJson?.optString("plugin") ?: ""
                        val rootActionName = rootJson?.optString("action") ?: ""
                        val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                        val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                        
                        if (targetPlugin != null && targetAction != null) {
                            val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                            val resolvedParams = resolveParametersWithMeta(
                                parameters = targetAction.parameters,
                                inputParams = rootParams,
                                context = context,
                                excludeIntentId = qa.id,
                                depth = 0
                            )
                            resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                        }
                    }
                }

                matchedIntents.size == 1 && matchedAliases.size <= 1 -> {
                    val qa = matchedIntents.first()
                    val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            context = context,
                            excludeIntentId = qa.id,
                            depth = 0
                        )
                        resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                    }
                }
            }
        }

        return when {
            resolvedIntents.isEmpty() -> Layer3Result.NoMatch
            resolvedIntents.size == 1 -> {
                val (plugin, intent) = resolvedIntents.first()
                Layer3Result.Single(plugin, intent)
            }
            else -> Layer3Result.Multi(resolvedIntents)
        }
    }

    private fun matchResultCopyForSingleAlias(
        original: TrainingSkill.MatchResult, 
        forcedAlias: QAEntity
    ): TrainingSkill.MatchResult {
        val updatedBest = original.bestAliasMatches.toMutableMap()
        updatedBest[forcedAlias.category] = forcedAlias to 1.0
        return original.copy(bestAliasMatches = updatedBest)
    }

    private suspend fun tryTier2SemanticSlotResolver(
        context: RoutingContext,
        devicePlugins: List<Plugin>
    ): Layer2Result? {
        val dynamicMinScore = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)

        val wrapperIntentPair = context.globalMatchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .find { 
                try {
                    JSONObject(it.first.answer).optString("plugin") == "schedule"
                } catch (_: Exception) {
                    false
                }
            }

        val bestIntentPair = wrapperIntentPair ?: context.globalMatchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .firstOrNull() ?: return null

        val bestIntentQA = bestIntentPair.first
        val confidence = bestIntentPair.second

        val rootJson = try { JSONObject(bestIntentQA.answer) } catch (e: Exception) { null }
        val rootPluginId = rootJson?.optString("plugin") ?: ""
        val rootActionName = rootJson?.optString("action") ?: ""
        
        if (rootPluginId.isBlank() || rootActionName.isBlank()) return null
        
        val rootPlugin = devicePlugins.find { it.manifest.id == rootPluginId } ?: return null
        val rootAction = rootPlugin.manifest.actions.find { it.name == rootActionName } ?: return null

        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()

        val resolvedParams = resolveParametersWithMeta(
            parameters = rootAction.parameters,
            inputParams = rootParams,
            context = context,
            excludeIntentId = bestIntentQA.id,
            depth = 0
        )

        return Layer2Result(rootPlugin, Intent(rootPluginId, rootActionName, resolvedParams), confidence)
    }

    private suspend fun tryTier2_5ActionMetadataMatcher(
        context: RoutingContext,
        devicePlugins: List<Plugin>
    ): Layer3Result {
        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()

        for (clause in context.clauses) {
            val clauseNormalized = StringSimilarityUtil.normalizeVietnamese(clause)

            val matchedAliases = context.globalMatchResult.aliasMatches.filter { 
                val aliasNorm = StringSimilarityUtil.normalizeVietnamese(it.first.question)
                clauseNormalized.contains(aliasNorm) 
            }

            val matchedMetadata = mutableListOf<NormalizedActionMetadata>()
            var tempClause = clauseNormalized
            val sortedMetadata = normalizedActionMetadataList
                .filter { it.plugin.manifest.routable && it.action.enabled }
                .sortedByDescending { it.action.description.length }

            for (meta in sortedMetadata) {
                val descNorm = meta.normalizedDescription
                if (descNorm.isBlank()) continue
                if (tempClause.contains(descNorm)) {
                    matchedMetadata.add(meta)
                    tempClause = tempClause.replace(descNorm, " ".repeat(descNorm.length))
                } else {
                    val matchedEx = meta.normalizedExamples.find { ex -> ex.isNotBlank() && tempClause.contains(ex) }
                    if (matchedEx != null) {
                        matchedMetadata.add(meta)
                        tempClause = tempClause.replace(matchedEx, " ".repeat(matchedEx.length))
                    }
                }
            }

            val totalMatchedLength = matchedMetadata.sumOf { it.normalizedDescription.length } + 
                                     matchedAliases.sumOf { it.first.question.length }
            
            if (clause.isEmpty()) continue
            val coverageRatio = totalMatchedLength.toDouble() / clause.length

            if (coverageRatio < 0.80) {
                continue
            }

            val uniqueTypes = matchedAliases.map { it.first.category }.distinct()
            val hasDuplicateTypes = matchedAliases.size != uniqueTypes.size

            val isIntentDouble = matchedMetadata.size == 1 && matchedAliases.size > 1 && uniqueTypes.size == 1
            val isMultiIntent = matchedMetadata.size > 1 && !hasDuplicateTypes

            when {
                isIntentDouble -> {
                    val meta = matchedMetadata.first()
                    val plugin = meta.plugin
                    val action = meta.action
                    val schemaParams = mutableMapOf<String, Any>()
                    action.parameters.forEach { param ->
                        schemaParams[param.name] = param.defaultValue ?: ""
                    }
                    for (alias in matchedAliases) {
                        val resolvedParams = resolveParametersWithMeta(
                            parameters = action.parameters,
                            inputParams = schemaParams,
                            context = context.copy(globalMatchResult = matchResultCopyForSingleAlias(context.globalMatchResult, alias.first)),
                            excludeIntentId = null,
                            depth = 0
                        )
                        resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams))
                    }
                }
                
                isMultiIntent -> {
                    for (meta in matchedMetadata) {
                        val plugin = meta.plugin
                        val action = meta.action
                        val schemaParams = mutableMapOf<String, Any>()
                        action.parameters.forEach { param ->
                            schemaParams[param.name] = param.defaultValue ?: ""
                        }
                        val resolvedParams = resolveParametersWithMeta(
                            parameters = action.parameters,
                            inputParams = schemaParams,
                            context = context,
                            excludeIntentId = null,
                            depth = 0
                        )
                        resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams))
                    }
                }
                
                matchedMetadata.size == 1 && matchedAliases.size <= 1 -> {
                    val meta = matchedMetadata.first()
                    val plugin = meta.plugin
                    val action = meta.action
                    val schemaParams = mutableMapOf<String, Any>()
                    action.parameters.forEach { param ->
                        schemaParams[param.name] = param.defaultValue ?: ""
                    }
                    val resolvedParams = resolveParametersWithMeta(
                        parameters = action.parameters,
                        inputParams = schemaParams,
                        context = context,
                        excludeIntentId = null,
                        depth = 0
                    )
                    resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams))
                }
            }
        }

        return when {
            resolvedIntents.isEmpty() -> Layer3Result.NoMatch
            resolvedIntents.size == 1 -> {
                val (plugin, intent) = resolvedIntents.first()
                Layer3Result.Single(plugin, intent)
            }
            else -> Layer3Result.Multi(resolvedIntents)
        }
    }

    private suspend fun resolveParametersWithMeta(
        parameters: List<PluginParameter>,
        inputParams: Map<String, Any>,
        context: RoutingContext,
        excludeIntentId: String? = null,
        depth: Int = 0
    ): Map<String, Any> {
        if (depth > MAX_DEPTH) return emptyMap()

        val secondaryIntentQA = context.globalMatchResult.intentMatches
            .filter { it.first.type == "intent" }
            .map { it.first }
            .firstOrNull { it.id != excludeIntentId }

        val resolved = mutableMapOf<String, Any>()

        parameters.forEach { param ->
            val currentValue = inputParams[param.name]
            val isPlh = ParameterResolver.isPlaceholder(currentValue, param)

            val resolver = resolverTable[param.semanticType.lowercase()]
            if (resolver != null) {
                resolved[param.name] = resolver(
                    param, currentValue, isPlh, context,
                    secondaryIntentQA, plugins.toList(), excludeIntentId, depth
                ) ?: ""
            } else {
                resolved[param.name] = resolveAliasOrFallback(param, currentValue, isPlh, context)
            }
        }

        return resolved
    }

    private fun resolvePluginIdFromSecondary(secondaryIntent: QAEntity): String {
        return try { JSONObject(secondaryIntent.answer).optString("plugin", "") } catch (_: Exception) { "" }
    }

    private fun resolveActionIdFromSecondary(secondaryIntent: QAEntity): String {
        return try { JSONObject(secondaryIntent.answer).optString("action", "") } catch (_: Exception) { "" }
    }

    private suspend fun resolveNestedParams(
        param: PluginParameter,
        currentValue: Any?,
        context: RoutingContext,
        secondaryIntentQA: QAEntity?,
        devicePlugins: List<Plugin>,
        excludeIntentId: String?,
        depth: Int
    ): Any {
        if (secondaryIntentQA == null) return currentValue ?: emptyMap<String, Any>()
        return try {
            val secJson = JSONObject(secondaryIntentQA.answer)
            val secPluginId = secJson.optString("plugin", "")
            val secActionName = secJson.optString("action", "")
            val secParams = secJson.optJSONObject("params")?.toMap() ?: emptyMap()

            val secPlugin = devicePlugins.find { it.manifest.id == secPluginId }
            val secAction = secPlugin?.manifest?.actions?.find { it.name == secActionName }

            if (secAction != null) {
                resolveParametersWithMeta(
                    parameters = secAction.parameters,
                    inputParams = secParams,
                    context = context,
                    excludeIntentId = excludeIntentId,
                    depth = depth + 1
                )
            } else {
                secParams
            }
        } catch (e: Exception) {
            currentValue ?: emptyMap<String, Any>()
        }
    }

    private fun resolveAliasOrFallback(
        param: PluginParameter,
        currentValue: Any?,
        isPlh: Boolean,
        context: RoutingContext
    ): Any {
        var finalIsPlh = isPlh
        
        if (!finalIsPlh && currentValue != null) {
            val isAliasVal = context.globalMatchResult.aliasMatches.any { 
                it.first.category == param.semanticType && 
                it.first.question.equals(currentValue.toString().trim(), ignoreCase = true) 
            }
            if (isAliasVal) {
                finalIsPlh = true
            }
        }

        if (!finalIsPlh) return currentValue ?: ""

        val matchedAliasValue = aliasMatchesForType(context.globalMatchResult, param.semanticType)
        if (matchedAliasValue != null) return matchedAliasValue

        if (context.resolvedQuery.isNotBlank()) {
            val configAliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
            val containsMatch = context.globalMatchResult.aliasMatches
                .filter { it.first.category == param.semanticType && it.second >= configAliasThreshold }
                .sortedByDescending { it.first.question.length }
                .firstOrNull { context.resolvedQuery.contains(it.first.question, ignoreCase = true) }
                ?.first?.answer
            if (containsMatch != null) return containsMatch
        }

        val localMatch = context.localEntities[param.semanticType]
        if (localMatch != null) return localMatch

        return currentValue ?: ""
    }

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
    }

    private fun getQuestionForMissingParam(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null
    ): String {
        val actualKey = if (param.startsWith("params.")) param.removePrefix("params.") else param
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName }
        val paramMeta = targetAction?.parameters?.find { it.name == actualKey }

        if (paramMeta != null && paramMeta.description.isNotBlank()) {
            return "Bạn vui lòng cung cấp thông tin cho ${paramMeta.description} nhé?"
        }

        return when (actualKey) {
            "device", "device_id", "deviceId"          -> "Bạn muốn điều khiển thiết bị nào?"
            "camera", "camera_id", "cameraId"          -> "Bạn muốn xem camera nào?"
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId", "plugin_id"                    -> "Bạn muốn lên lịch cho chức năng nào?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$actualKey' nhé?"
        }
    }

    private suspend fun executeIntent(
        plugin: Plugin,
        intent: Intent,
        context: RoutingContext,
        traceId: String
    ): PluginResult {
        val normalizedParams = ParameterResolver.normalizeParams(intent.params, plugin, intent.action, plugins, context.resolvedQuery)
        val normalizedIntent = intent.copy(params = normalizedParams)

        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"] ?: normalizedIntent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val missing = ParameterResolver.getUnresolvedParams(normalizedIntent.params, plugin, normalizedIntent.action, plugins)

        val executionResult = if (missing.isNotEmpty()) {
            val question = getQuestionForMissingParam(missing.first(), plugin, normalizedIntent.action)
            PluginResult.NeedMoreInfo(missing, question)
        } else {
            try {
                logger.d("AgentKernel", "[$traceId] Execute Action Trực Tiếp: ${plugin.manifest.id}.${normalizedIntent.action}")
                plugin.execute(normalizedIntent.action, normalizedIntent.params)
            } catch (e: Exception) {
                logger.e("AgentKernel", "[$traceId] Execute error: ${e.message}", e)
                PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
            }
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.addPendingIntent(
                PendingIntent(
                    pluginId = plugin.manifest.id,
                    action = normalizedIntent.action,
                    knownParams = normalizedIntent.params + mapOf("_noProgressCount" to 0),
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question,
                    createdAt = System.currentTimeMillis()
                )
            )
            is PluginResult.Success -> {
                chatHistoryManager.removePendingIntent(plugin.manifest.id, intent.action)
                dialogManager.updateFocus(
                    "default_user",
                    ConversationFocus(
                        pluginId = plugin.manifest.id,
                        action = normalizedIntent.action,
                        deviceId = normalizedIntent.params["device"]?.toString()
                            ?: normalizedIntent.params["device_id"]?.toString()
                            ?: normalizedIntent.params["deviceId"]?.toString(),
                        cameraId = normalizedIntent.params["camera"]?.toString()
                            ?: normalizedIntent.params["camera_id"]?.toString()
                            ?: normalizedIntent.params["cameraId"]?.toString(),
                        scheduleId = normalizedIntent.params["schedule_id"]?.toString()
                            ?: normalizedIntent.params["scheduleId"]?.toString(),
                        params = normalizedIntent.params,
                        timestamp = System.currentTimeMillis(),
                        confidence = 1.0
                    )
                )
            }
            else -> chatHistoryManager.removePendingIntent(plugin.manifest.id, intent.action)
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success ->
                (executionResult.data as? Map<*, *>)?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(context.originalQuery, replyForHistory)

        return executionResult
    }

    private suspend fun tryResolvePendingIntent(
        pending: PendingIntent,
        userMessage: String,
        devicePlugins: List<Plugin>,
        traceId: String,
        mode: PipelineMode = PipelineMode.EXECUTE
    ): DeviceCommandResult? {
        val targetPlugin = devicePlugins.find { it.manifest.id == pending.pluginId } ?: run {
            chatHistoryManager.clearPendingIntent()
            return null
        }
        val targetAction = targetPlugin.manifest.actions.find { it.name == pending.action } ?: run {
            chatHistoryManager.clearPendingIntent()
            return null
        }

        val noProgressCount = pending.knownParams["_noProgressCount"]?.toString()?.toIntOrNull() ?: 0
        if (noProgressCount >= 2) {
            logger.w("AgentKernel", "[$traceId] ⚠️ Pending bị lặp lại không có tiến triển -> clear pending")
            chatHistoryManager.clearPendingIntent()
            return null
        }

        val lower = userMessage.trim().lowercase()
        val cancelWords = listOf("dừng", "hủy", "huỷ", "bỏ qua")
        if (cancelWords.any { lower.contains(it) }) {
            chatHistoryManager.clearPendingIntent()
            chatHistoryManager.addTurn(userMessage, "Đã huỷ lệnh trước đó.")
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.Success(mapOf("message" to "Đã huỷ lệnh trước đó."))
            )
        }

        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, "default_user", aliasThreshold = aliasThreshold)

        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
        DateTimeParser.parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
        DateTimeParser.parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

        val heuristicFilled = mutableMapOf<String, Any>()
        for (param in pending.missingParams) {
            val trimmed = userMessage.trim()
            val isNested = param.startsWith("params.")
            val actualKey = if (isNested) param.removePrefix("params.") else param

            val paramMeta = if (isNested) {
                val targetPluginId = pending.knownParams["plugin_id"]?.toString()
                    ?: pending.knownParams["pluginId"]?.toString()
                    ?: pending.knownParams["plugin"]?.toString() ?: ""
                val targetActionName = pending.knownParams["action_id"]?.toString()
                    ?: pending.knownParams["action"]?.toString()
                    ?: pending.knownParams["actionId"]?.toString() ?: ""
                val tPlugin = devicePlugins.find { it.manifest.id == targetPluginId }
                tPlugin?.manifest?.actions?.find { it.name == targetActionName }?.parameters?.find { it.name == actualKey }
            } else {
                targetAction.parameters.find { it.name == actualKey }
            }

            if (paramMeta == null) continue

            when (paramMeta.semanticType.lowercase()) {
                "plugin_id" -> {
                    val matchedIntent = matchResult.intentMatches
                        .filter { it.first.type == "intent" }
                        .map { it.first }
                        .firstOrNull()
                    if (matchedIntent != null) {
                        try {
                            val secJson = JSONObject(matchedIntent.answer)
                            heuristicFilled[param] = secJson.optString("plugin", "")
                            val secParams = secJson.optJSONObject("params")?.toMap() ?: emptyMap()
                            if (secParams.isNotEmpty()) {
                                heuristicFilled["params"] = secParams
                            }
                        } catch (_: Exception) {}
                    }
                }
                "action_id" -> {
                    val matchedIntent = matchResult.intentMatches
                        .filter { it.first.type == "intent" }
                        .map { it.first }
                        .firstOrNull()
                    if (matchedIntent != null) {
                        try {
                            val secJson = JSONObject(matchedIntent.answer)
                            heuristicFilled[param] = secJson.optString("action", "")
                        } catch (_: Exception) {}
                    }
                }
                "time" -> {
                    localEntities["cron"]?.let { heuristicFilled[param] = if (localEntities.containsKey("intervalMinutes")) "" else it }
                }
                "interval" -> {
                    localEntities["intervalMinutes"]?.let { heuristicFilled[param] = it }
                }
                else -> {
                    val matchedAliasVal = aliasMatchesForType(matchResult, paramMeta.semanticType)
                    if (matchedAliasVal != null) {
                        heuristicFilled[param] = matchedAliasVal
                    } else {
                        val isEmailParam = paramMeta.semanticType == "email" || actualKey == "email" || actualKey == "to" || actualKey == "recipient"
                        if (isEmailParam && localEntities.containsKey("email")) {
                            heuristicFilled[param] = localEntities["email"]!!
                        } else if (paramMeta.type.lowercase() == "string" && trimmed.isNotBlank()) {
                            val textParams = setOf("subject", "body", "message", "title")
                            if (actualKey in textParams) {
                                if (param == pending.missingParams.first()) {
                                    heuristicFilled[param] = trimmed
                                }
                            }
                        }
                    }
                }
            }
        }

        val currentAskedParam = pending.missingParams.firstOrNull()
        
        val fill = if (currentAskedParam != null && heuristicFilled.containsKey(currentAskedParam)) {
            logger.d("AgentKernel", "[$traceId] Heuristic tự xử lý thành công tham số '$currentAskedParam'. Bypass LLM.")
            heuristicFilled
        } else {
            if (mode == PipelineMode.DIAGNOSTIC) {
                logger.d("AgentKernel", "[$traceId] 🔵 [DIAGNOSTIC] Chặn gọi Groq thực tế cho Tầng 1 dở dang.")
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, "[Simulated] Đang chờ nhập thêm thông tin.")
                )
            }

            logger.d("AgentKernel", "[$traceId] Heuristic không khớp tự động được '$currentAskedParam'. Gọi LLM.")
            val configAliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
            val foundAliasesContext = matchResult.aliasMatches
                .filter { it.second >= configAliasThreshold }
                .joinToString("\n") { "  - \"${it.first.question}\" ánh xạ sang ID thực tế: \"${it.first.answer}\"" }

            val fillPrompt = buildString {
                append("<system>Output ONLY raw JSON, NO explanation.\n")
                append("Determine if user's input fills the missing parameter:\n")
                append("{\"params\": {${pending.missingParams.joinToString(",") { "\"$it\": \"value\"" }}}}\n")
                append("If the user wants to cancel or starts another task, output: {\"unrelated\": true}</system>\n")
                if (foundAliasesContext.isNotBlank()) {
                    append("<aliases_context>\n$foundAliasesContext\n</aliases_context>\n")
                }
                append("<missing>${pending.missingParams.joinToString(", ")}</missing>\n")
                append("<question>${pending.askedQuestion}</question>\n")
                append("<user_reply>$userMessage</user_reply>\n")
                append("<output>")
            }

            val parsedJson = try {
                withTimeout(10_000L) {
                    val rawJson = groqClient.routeIntent(fillPrompt)
                    val cleaned = rawJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    JSONObject(cleaned)
                }
            } catch (e: Exception) {
                null
            } ?: return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
            )

            if (parsedJson.optBoolean("unrelated", false)) {
                chatHistoryManager.clearPendingIntent()
                return null
            } else {
                parsedJson.optJSONObject("params")?.toMap() ?: emptyMap()
            }
        }

        val finalFilled = fill + heuristicFilled

        val flatFilled = finalFilled.filterKeys { !it.startsWith("params.") && it != "params" }
        val nestedFilled = finalFilled.filterKeys { it.startsWith("params.") }.mapKeys { it.key.removePrefix("params.") }

        @Suppress("UNCHECKED_CAST")
        val existingNested = (pending.knownParams["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value ?: "") } ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val mergedNested = (existingNested as Map<String, Any>) + nestedFilled + (finalFilled["params"] as? Map<String, Any> ?: emptyMap())

        val mergedParams = pending.knownParams + flatFilled +
            if (mergedNested.isNotEmpty()) mapOf("params" to mergedNested) else emptyMap()

        val normalizedMergedParams = ParameterResolver.normalizeParams(mergedParams, targetPlugin, pending.action, plugins, userMessage)

        val stillMissing = pending.missingParams.filter { key ->
            if (key.startsWith("params.")) {
                val nestedKey = key.removePrefix("params.")
                @Suppress("UNCHECKED_CAST")
                val nested = normalizedMergedParams["params"] as? Map<String, Any>
                val v = nested?.get(nestedKey)

                val targetPluginId = normalizedMergedParams["plugin_id"]?.toString()
                    ?: normalizedMergedParams["pluginId"]?.toString()
                    ?: normalizedMergedParams["plugin"]?.toString() ?: ""
                val targetActionName = normalizedMergedParams["action_id"]?.toString()
                    ?: normalizedMergedParams["action"]?.toString()
                    ?: normalizedMergedParams["actionId"]?.toString() ?: ""
                val tPlugin = devicePlugins.find { it.manifest.id == targetPluginId }
                val tAction = tPlugin?.manifest?.actions?.find { it.name == targetActionName }
                val param = tAction?.parameters?.find { it.name == nestedKey }

                ParameterResolver.isPlaceholder(v, param)
            } else {
                val v = normalizedMergedParams[key]
                val param = targetAction.parameters.find { it.name == key }
                ParameterResolver.isPlaceholder(v, param)
            }
        }

        if (stillMissing.isNotEmpty()) {
            val madeProgress = stillMissing.size < pending.missingParams.size
            val newNoProgressCount = if (madeProgress) 0 else noProgressCount + 1
            val question = getQuestionForMissingParam(stillMissing.first(), targetPlugin, pending.action)

            chatHistoryManager.addPendingIntent(
                pending.copy(
                    knownParams = normalizedMergedParams + mapOf("_noProgressCount" to newNoProgressCount),
                    missingParams = stillMissing,
                    askedQuestion = question,
                    createdAt = System.currentTimeMillis()
                )
            )
            chatHistoryManager.addTurn(userMessage, question)
            return DeviceCommandResult(
                pluginId = targetPlugin.manifest.id,
                result = PluginResult.NeedMoreInfo(stillMissing, question)
            )
        }

        chatHistoryManager.removePendingIntent(pending.pluginId, pending.action)
        val executionResult = try {
            targetPlugin.execute(pending.action, normalizedMergedParams)
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success -> (executionResult.data as? Map<*, *>)?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(userMessage, replyForHistory)

        return DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = executionResult)
    }

    private suspend fun executeTier3LlmRouting(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        traceId: String
    ): RouterOutcome {
        val configAliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)

        val foundAliases = context.globalMatchResult.aliasMatches
            .filter { it.second >= configAliasThreshold }
            .joinToString("\n") { "  - \"${it.first.question}\" ánh xạ sang \"${it.first.answer}\" (danh mục: ${it.first.category})" }

        val queryNormalized = StringSimilarityUtil.normalizeVietnamese(context.resolvedQuery)
        val matchedActions = normalizedActionMetadataList.filter { meta ->
            meta.plugin.manifest.routable && meta.action.enabled && (
                meta.normalizedDescription.contains(queryNormalized) || queryNormalized.contains(meta.normalizedDescription) ||
                meta.normalizedExamples.any { ex -> 
                    ex.length >= 5 && (queryNormalized.contains(ex) || ex.contains(queryNormalized)) 
                }
            )
        }

        val candidateLines = if (matchedActions.isNotEmpty()) {
            matchedActions.joinToString("\n") { meta ->
                val paramsInfo = meta.action.parameters.joinToString(", ") { p ->
                    "${p.name} (kiểu: ${p.type}, yêu cầu: ${p.required}, vai trò: ${p.semanticType})"
                }
                "  - ${meta.plugin.manifest.id}.${meta.action.name}: ${meta.action.description}. Cấu trúc tham số: [$paramsInfo]"
            }
        } else {
            actionCandidates
                .filter { c -> devicePlugins.any { it.manifest.id == c.pluginId } }
                .joinToString("\n") { c ->
                    "  - ${c.pluginId}.${c.action}(${c.parameters.joinToString(",")})"
                }
        }

        val shortHistory = chatHistoryManager.getRecentTurnsAsText()
        val lastDevice = chatHistoryManager.lastMentionedDeviceId ?: "none"

        val routerPrompt = buildString {
            append("<sys>Intent Formatter. Chỉ xuất JSON: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("- Khớp thông điệp người dùng vào một trong các ứng viên (candidates) được tìm thấy bên dưới.\n")
            append("- Nếu là hội thoại thông thường, xuất: {\"plugin\":\"chat\",\"action\":\"none\"}\n")
            append("- Dựa vào danh bạ aliases được tìm thấy gửi kèm để gán chính xác tham số ID.\n")
            append("- Tuyệt đối không giải thích thêm, chỉ xuất JSON thô.</sys>\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            if (foundAliases.isNotEmpty()) append("<aliases>\n$foundAliases\n</aliases>\n")
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>${context.resolvedQuery}</input>\n")
            append("<output>")
        }

        val routerResultJson = try {
            withTimeout(15_000L) { groqClient.routeIntent(routerPrompt) }
        } catch (e: Exception) {
            return RouterOutcome.RouterFailed("Tầng 5 LLM timeout/error: ${e.message}")
        }

        val rawIntent = parseIntentResponse(routerResultJson) ?: return RouterOutcome.RouterFailed("Tầng 5 LLM parse error")
        if (rawIntent.pluginId == "chat") return RouterOutcome.NotACommand

        val targetPlugin = devicePlugins.find { it.manifest.id == rawIntent.pluginId } ?: return RouterOutcome.RouterFailed("Không tìm thấy Plugin")
        val targetAction = targetPlugin.manifest.actions.find { it.name == rawIntent.action } ?: return RouterOutcome.RouterFailed("Không tìm thấy Action")
        
        val finalParams = resolveParametersWithMeta(
            parameters = targetAction.parameters,
            inputParams = rawIntent.params,
            context = context,
            excludeIntentId = null,
            depth = 0
        )
        
        val intent = rawIntent.copy(params = finalParams)
        val result = executeIntent(targetPlugin, intent, context, traceId)

        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = result))
    }

    private fun parseIntentResponse(response: String): Intent? {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(cleaned)
            Intent(
                pluginId = json.getString("plugin"),
                action = json.getString("action"),
                params = json.optJSONObject("params")?.toMap() ?: emptyMap()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            if (value != org.json.JSONObject.NULL) {
                map[key] = when (value) {
                    is JSONObject -> value.toMap()
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            val item = value.get(i)
                            if (item != org.json.JSONObject.NULL) {
                                list.add(if (item is JSONObject) item.toMap() else item)
                            }
                        }
                        list
                    }
                    else -> value
                }
            }
        }
        return map
    }

    suspend fun executePluginAction(
        pluginId: String,
        action: String,
        params: Map<String, Any>
    ): PluginResult {
        val plugin = plugins.find { it.manifest.id == pluginId }
            ?: return PluginResult.Failure("Không tìm thấy plugin: $pluginId")

        val normalizedParams = ParameterResolver.normalizeParams(params, plugin, action, plugins, null)

        return try {
            plugin.execute(action, normalizedParams)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Lỗi Dashboard", e)
            PluginResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun process(userMessage: String): PluginResult {
        val outcome = tryDeviceCommand(userMessage)
        return when (outcome) {
            is RouterOutcome.Matched -> outcome.result.result
            is RouterOutcome.RouterFailed -> PluginResult.Failure(outcome.reason)
            is RouterOutcome.NotACommand -> PluginResult.Failure("Không phải lệnh thiết bị")
        }
    }

    suspend fun explainDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): DiagnosticInfo {
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        val tier2HighConf = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.80f)

        val simulatedTiers = mutableListOf<DiagnosticTier>()
        var finalOutcome: String? = null

        // ── TẦNG 0: DIALOG MANAGER (CANCEL / PRONOUN RESOLUTION) ──
        val currentPendingForCancel = chatHistoryManager.getActivePendingIntents().firstOrNull()
        val pendingStateForCancel = currentPendingForCancel?.toPendingState() ?: emptyPendingState()
        val cancelDecision = dialogManager.resolveCancel(userMessage, pendingStateForCancel)
        
        val pronounResult = dialogManager.resolvePronoun(userMessage, username, System.currentTimeMillis())
        val resolvedMessage = pronounResult.rewrittenMessage

        if (cancelDecision is CancelDecision.CancelPending) {
            val cancelledPluginId = cancelDecision.pluginId ?: "không xác định"
            val cancelledAction = cancelDecision.action ?: "lệnh trước đó"
            finalOutcome = "✅ Đã huỷ lệnh trước đó của \"$cancelledPluginId.$cancelledAction\" thành công."
        }

        // ── TẦNG 1: PENDING RESOLVER ──
        val pendings = chatHistoryManager.getActivePendingIntents()
        val isT1Matched = pendings.isNotEmpty() && cancelDecision !is CancelDecision.CancelPending
        
        if (isT1Matched && finalOutcome == null) {
            val devicePlugins = plugins.filter { it.manifest.routable }
            val activePending = pendings.first()
            val pendingResult = tryResolvePendingIntent(
                pending = activePending, 
                userMessage = userMessage, 
                devicePlugins = devicePlugins, 
                traceId = "DIAGNOSTIC-TRACE",
                mode = PipelineMode.DIAGNOSTIC
            )
            finalOutcome = if (pendingResult != null) {
                when (val r = pendingResult.result) {
                    is PluginResult.Success -> "✅ [Tầng 1 Chạy Thật Thành Công] ${(r.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                    is PluginResult.Failure -> "❌ [Tầng 1 Chạy Thật Thất Bại] ${r.error}"
                    is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 1 Yêu Cầu Nhập Thêm] ${r.question} (Danh sách thiếu: ${r.missingParams.joinToString()})"
                }
            } else {
                "🔵 [Tầng 1 Bỏ Qua] Tiến trình dở dang không liên quan đến câu trả lời."
            }
        }

        val t1Details = buildString {
            if (pronounResult.wasResolved) {
                append("• [Tầng 0] Pronoun: Thay thế đại từ thành công \"${pronounResult.resolvedFrom}\" ➔ \"${pronounResult.resolvedTo}\". Câu xử lý tiếp theo: \"$resolvedMessage\"\n")
            }
            if (cancelDecision is CancelDecision.CancelPending) {
                append("• [Tầng 0] Cancel: Xác nhận hủy tiến trình dở dang: ${cancelDecision.pluginId}.${cancelDecision.action}\n")
            }
            if (pendings.isNotEmpty()) {
                append("• Phát hiện ${pendings.size} tiến trình chưa hoàn thành: ")
                append(pendings.joinToString { "${it.pluginId}.${it.action}" })
            } else {
                append("• Không phát hiện hàng đợi dở dang nào đang chờ.")
            }
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 1: Trạng thái lệnh dở dang (Pending Intents)",
                tierNum = 1,
                matched = isT1Matched || cancelDecision is CancelDecision.CancelPending,
                details = t1Details
            )
        )

        // KHỞI TẠO ROUTING CONTEXT (TÍNH TOÁN 1 LẦN DUY NHẤT VỚI ALIAS THRESHOLD 0.0)
        val devicePlugins = plugins.filter { it.manifest.routable }
        val matchResult = trainingSkill.fuzzyMatchCategorized(
            resolvedMessage,
            username,
            intentThreshold = intentThreshold,
            aliasThreshold = aliasThreshold
        )

        val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
        val clauses = clauseSeparator.split(resolvedMessage).map { it.trim() }.filter { it.isNotBlank() }

        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(resolvedMessage)?.value?.let { localEntities["email"] = it }
        DateTimeParser.parseVietnameseTime(resolvedMessage)?.let { localEntities["cron"] = it }
        DateTimeParser.parseVietnameseInterval(resolvedMessage)?.let { localEntities["intervalMinutes"] = it }

        val context = RoutingContext(
            originalQuery = userMessage,
            resolvedQuery = resolvedMessage,
            username = username,
            clauses = clauses,
            globalMatchResult = matchResult,
            localEntities = localEntities
        )

        // ── TẦNG 2A: MÀNG LỌC PHỦ ĐỊNH CỤC BỘ ──
        val lowerMsg = resolvedMessage.lowercase().trim()
        val negationWords = setOf("không", "chưa", "đừng", "hủy", "huỷ", "không muốn", "đừng có")
        val hasNegation = negationWords.any { word -> Regex("(?<!\\p{L})$word(?!\\p{L})").containsMatchIn(lowerMsg) }
        
        if (hasNegation && finalOutcome == null) {
            finalOutcome = "🔵 [Màng lọc Phủ định Cục bộ] Phát hiện từ khóa phủ định trong câu lệnh. Hệ thống bypass toàn bộ bộ lọc tĩnh xuống Tầng 5 (LLM Fallback) để đảm bảo hiểu đúng ngữ nghĩa."
            simulatedTiers.add(DiagnosticTier("Tầng 2: So khớp Ý định tĩnh (Exact/Fuzzy Intent Match)", 2, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
            simulatedTiers.add(DiagnosticTier("Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)", 3, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
            simulatedTiers.add(DiagnosticTier("Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)", 4, false, "• Bỏ qua do phát hiện từ khóa phủ định."))
            simulatedTiers.add(DiagnosticTier("Tầng 5: Phân loại thông minh bằng AI (LLM Fallback)", 5, true, "• [KÍCH HOẠT] Đã chuyển xuống LLM xử lý."))
            
            return DiagnosticInfo(
                query = userMessage,
                tiers = simulatedTiers,
                resolvedIntents = emptyList(),
                resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
                intentMatches = matchResult.intentMatches,
                aliasMatches = matchResult.aliasMatches,
                bestAliasMatches = matchResult.bestAliasMatches,
                intentThreshold = intentThreshold,
                aliasThreshold = aliasThreshold,
                executionOutcome = finalOutcome
            )
        }

        // ── TẦNG 2: SO KHỚP Ý ĐỊNH TĨNH ──
        val layer2Result = tryTier2SemanticSlotResolver(context, devicePlugins)
        val isT2Matched = layer2Result != null && layer2Result.confidence >= tier2HighConf

        if (isT2Matched && finalOutcome == null) {
            val executionResult = executeIntent(layer2Result!!.plugin, layer2Result.intent, context, "DIAGNOSTIC-TRACE")
            finalOutcome = when (executionResult) {
                is PluginResult.Success -> "✅ [Tầng 2 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                is PluginResult.Failure -> "❌ [Tầng 2 Chạy Thật Thất Bại] ${executionResult.error}"
                is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 2 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
            }
        }

        val t2Details = when {
            layer2Result == null -> "• Không tìm thấy ý định mẫu nào khớp với nội dung đã nhập."
            isT2Matched -> "• Khớp ý định '${layer2Result.intent.pluginId}.${layer2Result.intent.action}' với điểm số cao (${String.format("%.2f", layer2Result.confidence)} >= $tier2HighConf). Lệnh được bypass thực thi trực tiếp."
            else -> "• Ý định '${layer2Result.intent.pluginId}.${layer2Result.intent.action}' chưa đạt điểm tin cậy thực thi nhanh (${String.format("%.2f", layer2Result.confidence)} < $tier2HighConf). Chuyển xuống Tầng 3."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 2: So khớp Ý định tĩnh (Exact/Fuzzy Intent Match)",
                tierNum = 2,
                matched = isT2Matched,
                score = layer2Result?.confidence ?: 0.0,
                details = t2Details
            )
        )

        // ── TẦNG 3: TÁCH MỆNH ĐỀ ĐA LỆNH & SLOT-FILLING ──
        val layer3Result = if (!isT2Matched) {
            processLayer3ClauseEntitySpotter(context, devicePlugins, "DIAGNOSTIC-TRACE")
        } else {
            Layer3Result.NoMatch
        }

        if (layer3Result !is Layer3Result.NoMatch && finalOutcome == null) {
            finalOutcome = when (layer3Result) {
                is Layer3Result.Single -> {
                    val executionResult = executeIntent(layer3Result.plugin, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                    when (executionResult) {
                        is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                        is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                        is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                    }
                }
                is Layer3Result.Nested -> {
                    val executionResult = executeIntent(layer3Result.wrapper, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                    when (executionResult) {
                        is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                        is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                        is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                    }
                }
                is Layer3Result.Multi -> {
                    val results = mutableListOf<String>()
                    layer3Result.intents.forEach { (plugin, intent) ->
                        val executionResult = executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
                        val msg = when (executionResult) {
                            is PluginResult.Success -> "✅ ${intent.pluginId}.${intent.action} thành công."
                            is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action} thất bại: ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action} thiếu: ${executionResult.question}"
                        }
                        results.add(msg)
                    }
                    "✅ [Tầng 3 Chạy Thật Đa Lệnh]:\n" + results.joinToString("\n")
                }
                else -> null
            }
        }

        val t3Details = when (layer3Result) {
            is Layer3Result.Single -> {
                val missing = ParameterResolver.getUnresolvedParams(layer3Result.intent.params, layer3Result.plugin, layer3Result.intent.action, plugins)
                if (missing.isNotEmpty()) {
                    "• Tách thành công câu lệnh đơn '${layer3Result.intent.pluginId}.${layer3Result.intent.action}'. Phát hiện thiếu thông tin: [${missing.joinToString { getQuestionForMissingParam(it, layer3Result.plugin, layer3Result.intent.action) }}]"
                } else {
                    "• Tách thành công câu lệnh đơn và điền đủ tham số: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
                }
            }
            is Layer3Result.Nested -> "• Khớp bộ khung lập lịch (schedule) lồng hành động: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
            is Layer3Result.Multi -> "• Phân tích cú pháp đa hành động (song song/tuần tự): ${layer3Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
            else -> "• Không tìm thấy hoặc cấu trúc câu không chứa mệnh đề/từ khóa lập lịch đạt chuẩn hoặc độ bao phủ mệnh đề chưa đạt 80%."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)",
                tierNum = 3,
                matched = layer3Result !is Layer3Result.NoMatch,
                details = t3Details
            )
        )

        // ── TẦNG 4: SO KHỚP MÔ TẢ & NHÃN PLUGIN ──
        val isT4Checkable = !isT2Matched && layer3Result is Layer3Result.NoMatch
        val layer4Result = if (isT4Checkable) {
            tryTier2_5ActionMetadataMatcher(context, devicePlugins)
        } else {
            Layer3Result.NoMatch
        }

        if (layer4Result !is Layer3Result.NoMatch && finalOutcome == null) {
            finalOutcome = when (layer4Result) {
                is Layer3Result.Single -> {
                    val executionResult = executeIntent(layer4Result.plugin, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                    when (executionResult) {
                        is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                        is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                        is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                    }
                }
                is Layer3Result.Nested -> {
                    val executionResult = executeIntent(layer4Result.wrapper, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                    when (executionResult) {
                        is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                        is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                        is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                    }
                }
                is Layer3Result.Multi -> {
                    val results = mutableListOf<String>()
                    layer4Result.intents.forEach { (plugin, intent) ->
                        val executionResult = executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
                        val msg = when (executionResult) {
                            is PluginResult.Success -> "✅ ${intent.pluginId}.${intent.action} thành công."
                            is PluginResult.Failure -> "❌ ${intent.pluginId}.${intent.action} thất bại: ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ ${intent.pluginId}.${intent.action} thiếu: ${executionResult.question}"
                        }
                        results.add(msg)
                    }
                    "✅ [Tầng 4 Chạy Thật Đa Lệnh]:\n" + results.joinToString("\n")
                }
                else -> null
            }
        }

        val t4Details = when (layer4Result) {
            is Layer3Result.Single -> "• Khớp 1 lệnh thông qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
            is Layer3Result.Nested -> "• Khớp cấu trúc lập lịch qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
            is Layer3Result.Multi -> "• Khớp đa lệnh qua Meta Manifest: ${layer4Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
            else -> "• Không khớp với mô tả action hoặc nhãn ví dụ trong Manifest của các Plugin hoặc độ bao phủ mệnh đề chưa đạt 80%."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)",
                tierNum = 4,
                matched = layer4Result !is Layer3Result.NoMatch,
                details = t4Details
            )
        )

        // ── TẦNG 5: PHÂN LOẠI THÔNG MINH BẰNG AI ──
        val isT5Matched = !isT2Matched && layer3Result is Layer3Result.NoMatch && layer4Result is Layer3Result.NoMatch
        if (isT5Matched && finalOutcome == null) {
            finalOutcome = "🔵 [Bypass Tầng 5] Đã chuyển xuống Tầng 5 để LLM phân giải tự do. Hệ thống không tự động chạy thật ở tầng này nhằm tiết kiệm token API Groq."
        }

        val t5Details = if (isT5Matched) {
            "• Không khớp bất kỳ mẫu tĩnh hay heuristic nào. Câu lệnh sẽ được gửi lên Groq LLM để phân rã tự do."
        } else {
            "• Bypass (Bỏ qua gọi LLM) nhằm tiết kiệm tài nguyên do các tầng heuristic phía trên đã giải quyết xong."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 5: Phân loại thông minh bằng AI (LLM Fallback)",
                tierNum = 5,
                matched = isT5Matched,
                details = t5Details
            )
        )

        return DiagnosticInfo(
            query = userMessage,
            tiers = simulatedTiers,
            resolvedIntents = matchResult.intentMatches
                .filter { it.second >= intentThreshold }
                .map { "${it.first.question} (${String.format("%.2f", it.second)})" },
            resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
            intentMatches = matchResult.intentMatches,
            aliasMatches = matchResult.aliasMatches,
            bestAliasMatches = matchResult.bestAliasMatches,
            intentThreshold = intentThreshold,
            aliasThreshold = aliasThreshold,
            executionOutcome = finalOutcome
        )
    }

    // ── TIỆN ÍCH HỖ TRỢ CHẾ ĐỘ KHÓA ĐIỀU KHIỂN CỨNG ──

    private fun isExitLockPhrase(msg: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        val exitPhrases = setOf("thoat dieu khien", "ra khoi dieu khien", "ket thuc dieu khien", "thoat")
        return norm in exitPhrases
    }

    private fun parseYesNo(msg: String): Boolean? {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        val yes = setOf("co", "dung", "u", "ok", "dong y", "chuan", "phai")
        val no = setOf("khong", "khoi", "thoi", "huy", "sai")
        return when {
            yes.any { norm == it } -> true
            no.any { norm == it } -> false
            else -> null
        }
    }

    private fun detectLockTrigger(userMessage: String): String? {
        val norm = StringSimilarityUtil.normalizeVietnamese(userMessage.trim())
        val m = Regex("^(dieu khien|khoa dieu khien)\\s+(thiet bi\\s+)?(.+)$").find(norm) ?: return null
        val target = m.groupValues[3].trim()
        if (target.isBlank()) return null

        val matched = plugins.firstOrNull { p ->
            p.manifest.routable && (
                target.contains(p.manifest.id, ignoreCase = true) ||
                StringSimilarityUtil.normalizeVietnamese(p.manifest.name).contains(target, ignoreCase = true)
            )
        }
        return matched?.manifest?.id
    }

    private fun handleLockConfirmation(userMessage: String, pluginId: String): RouterOutcome {
        val matchedPlugin = plugins.find { it.manifest.id == pluginId }
        val displayName = matchedPlugin?.manifest?.name ?: pluginId
        return when (parseYesNo(userMessage)) {
            true -> {
                chatHistoryManager.lockPlugin(pluginId)
                chatHistoryManager.clearLockRequest()
                RouterOutcome.Matched(
                    DeviceCommandResult(pluginId, PluginResult.Success(mapOf("message" to "🔒 Đã vào chế độ điều khiển riêng biệt cho \"$displayName\". Tất cả hội thoại thông thường sẽ bị chặn cho đến khi bạn yêu cầu \"thoát\".")))
                )
            }
            false -> {
                chatHistoryManager.clearLockRequest()
                RouterOutcome.Matched(
                    DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Đã hủy yêu cầu điều khiển riêng.")))
                )
            }
            null -> RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Xác nhận vào chế độ điều khiển riêng cho \"$displayName\" chứ? (có/không)")))
            )
        }
    }

    // Các hàm Getter cung cấp trạng thái hiển thị cho ViewModel và UI Screen
    fun getLockedPluginId(): String? = chatHistoryManager.getLockedPlugin()

    fun getLockedPluginName(): String? {
        val id = getLockedPluginId() ?: return null
        return plugins.find { it.manifest.id == id }?.manifest?.name ?: id
    }

    data class Intent(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any> = emptyMap()
    )

    data class DeviceCommandResult(
        val pluginId: String,
        val result: PluginResult
    )

    sealed class RouterOutcome {
        data class Matched(val result: DeviceCommandResult) : RouterOutcome()
        object NotACommand : RouterOutcome()
        data class RouterFailed(val reason: String) : RouterOutcome()
    }

    sealed class PluginResult {
        data class Success(val data: Any) : PluginResult()
        data class Failure(val error: String) : PluginResult()
        data class NeedMoreInfo(val missingParams: List<String>, val question: String) : PluginResult()
    }
}
