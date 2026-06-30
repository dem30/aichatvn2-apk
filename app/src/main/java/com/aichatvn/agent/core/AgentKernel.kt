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

// ── ĐỒNG BỘ HOÁ ĐỊNH NGHĨA PIPELINE MODE & RESULT THEO GÓP Ý ──
enum class PipelineMode { EXECUTE, DIAGNOSTIC }

data class PipelineResult(
    val routerOutcome: AgentKernel.RouterOutcome?,
    val tiers: List<DiagnosticTier> = emptyList(),
    val finalOutcome: String? = null,
    val matchResult: TrainingSkill.MatchResult? = null // Kế thừa kết quả khớp để tránh truy vấn lại DB
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
                        else -> "❌ Có lỗi phát sinh khi xư lý câu lệnh hệ thống."
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
                    else -> { //combined hoặc groq
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

    // ── GỘP LUỒNG SỬ DỤNG CHẠY PIPELINE THUẦN TÚY ──
    private suspend fun runPipeline(
        userMessage: String,
        username: String,
        mode: PipelineMode,
        traceId: String
    ): PipelineResult {
        val devicePlugins = plugins.filter { it.manifest.routable }
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

                for (pending in pendings) {
                    val resolvedResult = tryResolvePendingIntent(pending, userMessage, devicePlugins, traceId)
                    if (resolvedResult != null) {
                        val r = resolvedResult.result
                        val msg = when (r) {
                            is PluginResult.Success -> {
                                chatHistoryManager.removePendingIntent(pending.pluginId, pending.action)
                                (r.data as? Map<*, *>)?.get("message") as? String ?: "✅ Thực hiện thành công"
                            }
                            is PluginResult.Failure -> {
                                chatHistoryManager.removePendingIntent(pending.pluginId, pending.action)
                                "❌ Lỗi: ${r.error}"
                            }
                            is PluginResult.NeedMoreInfo -> {
                                allSucceeded = false
                                r.question
                            }
                        }
                        results.add(msg)
                    }
                }

                if (results.isNotEmpty()) {
                    val combined = results.distinct().joinToString("\n")
                    val finalResult = if (allSucceeded) {
                        PluginResult.Success(mapOf("message" to combined))
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
                val pendingResult = tryResolvePendingIntent(pendings.first(), userMessage, devicePlugins, traceId)
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

        // ── KHỞI TẠO ROUTING CONTEXT (TÍNH TOÁN 1 LẦN DUY NHẤT VỚI ALIAS THRESHOLD 0.0) ──
        val matchResult = trainingSkill.fuzzyMatchCategorized(
            resolvedMessage,
            username,
            intentThreshold = intentThreshold,
            aliasThreshold = 0.0f
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

        // ── TẦNG 2A: MÀNG LỌC PHỦ ĐỊNH CỤC BỘ (BYPASS THẲNG XUỐNG TẦNG 5) ──
        val lowerMsg = resolvedMessage.lowercase().trim()
        val negationWords = setOf("không", "chưa", "đừng", "hủy", "huỷ", "không muốn", "đừng có")
        val hasNegation = negationWords.any { word -> Regex("\\b$word\\b").containsMatchIn(lowerMsg) }

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
                        val executionResult = executeIntent(layer3Result.plugin, layer3Result.intent, context, traceId)
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = executeIntent(layer3Result.wrapper, layer3Result.intent, context, traceId)
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer3Result.intents.forEach { (plugin, intent) ->
                            val executionResult = executeIntent(plugin, intent, context, traceId)
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

            logger.d("AgentKernel", "[$traceId] 🔵 [Tầng 4 Miss] -> LLM")
            return PipelineResult(
                routerOutcome = executeTier3LlmRouting(context, devicePlugins, traceId),
                matchResult = matchResult
            )
        } else {
            if (layer4Result !is Layer3Result.NoMatch && finalOutcome == null) {
                finalOutcome = when (layer4Result) {
                    is Layer3Result.Single -> {
                        val executionResult = executeIntent(layer4Result.plugin, layer4Result.intent, context, traceId)
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = executeIntent(layer4Result.wrapper, layer4Result.intent, context, traceId)
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer4Result.intents.forEach { (plugin, intent) ->
                            val executionResult = executeIntent(plugin, intent, context, traceId)
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

            return PipelineResult(
                routerOutcome = null,
                tiers = simulatedTiers,
                finalOutcome = finalOutcome,
                matchResult = matchResult
            )
        }
    }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = generateTraceId()
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")
        val result = runPipeline(userMessage, username, PipelineMode.EXECUTE, traceId)
        return result.routerOutcome ?: RouterOutcome.RouterFailed("Pipeline execution error")
    }

    suspend fun explainDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): DiagnosticInfo {
        val traceId = generateTraceId()
        val result = runPipeline(userMessage, username, PipelineMode.DIAGNOSTIC, traceId)
        
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        
        val matchResult = result.matchResult ?: trainingSkill.fuzzyMatchCategorized(userMessage, username, intentThreshold, 0.0f)
        
        return DiagnosticInfo(
            query = userMessage,
            tiers = result.tiers,
            resolvedIntents = matchResult.intentMatches
                .filter { it.second >= intentThreshold }
                .map { "${it.first.question} (${String.format("%.2f", it.second)})" },
            resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
            intentMatches = matchResult.intentMatches,
            aliasMatches = matchResult.aliasMatches,
            bestAliasMatches = matchResult.bestAliasMatches,
            intentThreshold = intentThreshold,
            aliasThreshold = aliasThreshold,
            executionOutcome = result.finalOutcome
        )
    }