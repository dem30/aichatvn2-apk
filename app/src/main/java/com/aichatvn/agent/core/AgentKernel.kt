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
import com.aichatvn.agent.core.DialogManager
import com.aichatvn.agent.core.PendingState
import com.aichatvn.agent.core.CancelDecision
import com.aichatvn.agent.core.ConversationFocus
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

import com.aichatvn.agent.data.model.QAEntity

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
    val aliasThreshold: Float = 0.2f
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

// Cấu trúc dữ liệu yêu cầu và phản hồi Chat trung tâm
data class ChatRequest(
    val message: String,
    val username: String = "default_user",
    val imageBase64: String? = null,
    val fileUrl: String? = null,
    val extraContext: String = "",
    val chatMode: String = "COMBINED" // "GROQ" | "QA" | "COMBINED"
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
    private val database: AppDatabase, // Dùng để tải ngữ cảnh lịch sử cho LLM độc lập
    private val logger: Logger,
    private val dialogManager: DialogManager // ← TẦNG 0: Dialog Manager (Cancel/Pronoun/Focus)
) {

    companion object {
        private val SPACE_REGEX = Regex("\\s+")
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



     /* Chạy thử nghiệm và phân tích chính xác luồng xử lý 5 tầng mà không gây tác động phụ (dry-run).
     * Phục vụ mục đích chẩn đoán trực quan hóa cho màn hình kiểm thử.
     */
    suspend fun explainDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): DiagnosticInfo {
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        val tier2HighConf = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.80f)

        val simulatedTiers = mutableListOf<DiagnosticTier>()

        // ── TẦNG 0: PRONOUN RESOLUTION & CANCEL CHECK ──
        val currentPendingForCancel = chatHistoryManager.getActivePendingIntents().firstOrNull()
        val pendingStateForCancel = currentPendingForCancel?.toPendingState() ?: emptyPendingState()
        val cancelDecision = dialogManager.resolveCancel(userMessage, pendingStateForCancel)
        
        val pronounResult = dialogManager.resolvePronoun(userMessage, username, System.currentTimeMillis())
        val resolvedMessage = pronounResult.rewrittenMessage

        // ── TẦNG 1: TRẠNG THÁI DỞ DANG (PENDING INTENTS) ──
        val pendings = chatHistoryManager.getActivePendingIntents()
        val isT1Matched = pendings.isNotEmpty() && cancelDecision !is CancelDecision.CancelPending
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

        // ── TẦNG 2: SO KHỚP Ý ĐỊNH TĨNH (EXACT/FUZZY INTENT MATCH) ──
        val devicePlugins = plugins.filter { it.manifest.routable }
        val matchResult = trainingSkill.fuzzyMatchCategorized(
            resolvedMessage,
            username,
            intentThreshold = intentThreshold,
            aliasThreshold = 0.0f
        )
        val layer2Result = tryTier2SemanticSlotResolver(resolvedMessage, matchResult, devicePlugins)
        val isT2Matched = layer2Result != null && layer2Result.confidence >= tier2HighConf

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

        // ── TẦNG 3: TÁCH MỆNH ĐỀ ĐA LỆNH & SLOT-FILLING (CLAUSE SPOTTER) ──
        val layer3Result = if (!isT2Matched) {
            processLayer3ClauseEntitySpotter(resolvedMessage, username, devicePlugins, "DIAGNOSTIC-TRACE")
        } else {
            Layer3Result.NoMatch
        }
        val t3Details = when (layer3Result) {
            is Layer3Result.Single -> "• Tách và nhận diện được câu lệnh đơn: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
            is Layer3Result.Nested -> "• Khớp bộ khung lập lịch (schedule) lồng hành động: ${layer3Result.intent.pluginId}.${layer3Result.intent.action}"
            is Layer3Result.Multi -> "• Phân tích cú pháp đa hành động (song song/tuần tự): ${layer3Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
            else -> "• Không tìm thấy hoặc cấu trúc câu không chứa mệnh đề/từ khóa lập lịch đạt chuẩn."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)",
                tierNum = 3,
                matched = layer3Result !is Layer3Result.NoMatch,
                details = t3Details
            )
        )

        // ── TẦNG 4: SO KHỚP MÔ TẢ & NHÃN PLUGIN (METADATA MATCHER) ──
        val isT4Checkable = !isT2Matched && layer3Result is Layer3Result.NoMatch
        val layer4Result = if (isT4Checkable) {
            tryTier2_5ActionMetadataMatcher(resolvedMessage, matchResult, devicePlugins)
        } else {
            Layer3Result.NoMatch
        }
        val t4Details = when (layer4Result) {
            is Layer3Result.Single -> "• Khớp 1 lệnh thông qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
            is Layer3Result.Nested -> "• Khớp cấu trúc lập lịch qua Meta Manifest: ${layer4Result.intent.pluginId}.${layer4Result.intent.action}"
            is Layer3Result.Multi -> "• Khớp đa lệnh qua Meta Manifest: ${layer4Result.intents.joinToString { "${it.first.manifest.id}.${it.second.action}" }}"
            else -> "• Không khớp với mô tả action hoặc nhãn ví dụ trong Manifest của các Plugin."
        }
        simulatedTiers.add(
            DiagnosticTier(
                tierName = "Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)",
                tierNum = 4,
                matched = layer4Result !is Layer3Result.NoMatch,
                details = t4Details
            )
        )

        // ── TẦNG 5: PHÂN LOẠI THÔNG MINH BẰNG AI (LLM FALLBACK) ──
        val isT5Matched = !isT2Matched && layer3Result is Layer3Result.NoMatch && layer4Result is Layer3Result.NoMatch
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
            aliasThreshold = aliasThreshold
        )
    }
}


    
    private val normalizedActionMetadataList: List<NormalizedActionMetadata> by lazy {
        plugins.flatMap { plugin ->
            plugin.manifest.actions.map { action ->
                NormalizedActionMetadata(
                    plugin = plugin,
                    action = action,
                    normalizedDescription = normalizeVietnamese(action.description),
                    normalizedExamples = action.examples.map { normalizeVietnamese(it) },
                    normalizedTags = action.tags.map { normalizeVietnamese(it) }
                )
            }
        }
    }

    private val resolverTable: Map<String, suspend (
        param: PluginParameter,
        currentValue: Any?,
        isPlh: Boolean,
        matchResult: TrainingSkill.MatchResult,
        localEntities: Map<String, Any>,
        secondaryIntentQA: QAEntity?,
        devicePlugins: List<Plugin>,
        excludeIntentId: String?,
        depth: Int,
        userMessage: String
    ) -> Any?> = mapOf(
        "time" to { _, currentValue, _, _, localEntities, _, _, _, _, _ ->
            localEntities["cron"] ?: currentValue ?: ""
        },
        "interval" to { _, currentValue, _, _, localEntities, _, _, _, _, _ ->
            localEntities["intervalMinutes"] ?: currentValue ?: 0
        },
        "plugin_id" to { _, currentValue, isPlh, _, _, secondaryIntent, _, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolvePluginIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "action_id" to { _, currentValue, isPlh, _, _, secondaryIntent, _, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolveActionIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "params" to { param, currentValue, _, matchResult, _, secondaryIntent, devicePlugins, excludeId, depth, userMessage ->
            resolveNestedParams(param, currentValue, matchResult, secondaryIntent, devicePlugins, excludeId, depth, userMessage)
        }
    )

    private fun generateTraceId(): String = "TR-${System.currentTimeMillis() % 100000}-${(100..999).random()}"

    // ────────────────────────────────────────────────────────────
    // TẦNG 0 — Cầu nối PendingIntent (ChatHistoryManager) -> PendingState (DialogManager)
    // Chỉ là mapper đọc dữ liệu, không sửa ChatHistoryManager.
    // ────────────────────────────────────────────────────────────
    private fun PendingIntent.toPendingState(): PendingState = PendingState(
        isActive = true,
        pluginId = this.pluginId,
        action = this.action,
        missingParams = this.missingParams,
        askedQuestion = this.askedQuestion
    )

    private fun emptyPendingState(): PendingState = PendingState(isActive = false)

    private fun isPlaceholder(value: Any?, parameter: PluginParameter?): Boolean {
        val strVal = value?.toString()?.trim()?.replace(SPACE_REGEX, " ") ?: ""
        if (strVal.isBlank()) return true
        
        if (parameter != null && parameter.placeholder.isNotBlank()) {
            val paramPlh = parameter.placeholder.trim().replace(SPACE_REGEX, " ")
            if (strVal.equals(paramPlh, ignoreCase = true)) {
                return true
            }
        }
        
        val defaultPlaceholders = setOf(
            "device_1", "device_2", "camera_1", "camera_2",
            "device 1", "device 2", "camera 1", "camera 2",
            "example@gmail.com", "example@email.com",
            "schedule_1", "schedule_id_here"
        )
        return strVal in defaultPlaceholders
    }

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

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = generateTraceId()
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")

        val devicePlugins = plugins.filter { it.manifest.routable }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ── TẦNG 0a: CANCEL RESOLVER ──
        // Chỉ coi là "hủy" khi thực sự có Pending đang chờ. Nếu không pending,
        // DialogManager trả NotCancelNoPending/NotCancel và câu đi tiếp xuống Router như bình thường.
        val currentPendingForCancel = chatHistoryManager.getActivePendingIntents().firstOrNull()
        val pendingStateForCancel = currentPendingForCancel?.toPendingState() ?: emptyPendingState()
        val cancelDecision = dialogManager.resolveCancel(userMessage, pendingStateForCancel)
        if (cancelDecision is CancelDecision.CancelPending) {
            val cancelledPluginId = cancelDecision.pluginId ?: "không xác định"
            val cancelledAction = cancelDecision.action ?: "lệnh trước đó"
            if (cancelDecision.pluginId != null && cancelDecision.action != null) {
                chatHistoryManager.removePendingIntent(cancelDecision.pluginId, cancelDecision.action)
            } else {
                chatHistoryManager.clearPendingIntent()
            }
            val replyText = "✅ Đã huỷ lệnh \"$cancelledAction\" của \"$cancelledPluginId\"."
            chatHistoryManager.addTurn(userMessage, replyText)
            logger.d("AgentKernel", "[$traceId] [Tầng 0] Cancel Resolver xác nhận huỷ pending: $cancelledPluginId.$cancelledAction")
            return RouterOutcome.Matched(
                DeviceCommandResult(cancelledPluginId, PluginResult.Success(mapOf("message" to replyText)))
            )
        }

        // ── TẦNG 0b: PRONOUN RESOLVER ──
        // Viết lại câu, thay "nó"/"cái đó"/"thiết bị đó"... bằng giá trị cụ thể từ Focus,
        // TRƯỚC khi câu đi vào Tầng 1-5. Nếu không resolve được gì, dùng nguyên văn.
        val pronounResult = dialogManager.resolvePronoun(userMessage, username, System.currentTimeMillis())
        val resolvedMessage = pronounResult.rewrittenMessage
        if (pronounResult.wasResolved) {
            logger.d("AgentKernel", "[$traceId] [Tầng 0] Pronoun Resolver: \"${pronounResult.resolvedFrom}\" -> \"${pronounResult.resolvedTo}\". Câu sau khi viết lại: \"$resolvedMessage\"")
        }

        // ── TẦNG 1: Xử lý trạng thái dở dang đa lệnh song song (Pending Intents Queue) ──
        val pendings = chatHistoryManager.getActivePendingIntents()
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
                    PluginResult.NeedMoreInfo(
                        remainingPending.flatMap { it.missingParams },
                        combined
                    )
                }
                return RouterOutcome.Matched(DeviceCommandResult("multi_pending", finalResult))
            }
        }

        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD }
            ?.value?.toFloatOrNull() ?: 0.3f

        val matchResult = trainingSkill.fuzzyMatchCategorized(
            resolvedMessage, 
            username, 
            intentThreshold = dynamicMinScore,
            aliasThreshold = 0.0f
        )

        val tier2HighConf = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE }
            ?.value?.toDoubleOrNull() ?: 0.80

        val layer2Result = tryTier2SemanticSlotResolver(resolvedMessage, matchResult, devicePlugins)
        if (layer2Result != null) {
            if (layer2Result.confidence >= tier2HighConf) {
                val plugin = layer2Result.plugin
                val intent = layer2Result.intent
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 2 Hit - Confidence: ${layer2Result.confidence}] ${intent.pluginId}.${intent.action}")
                return try {
                    val result = executeIntent(plugin, intent, resolvedMessage, traceId)
                    RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result))
                } catch (e: Exception) {
                    logger.e("AgentKernel", "[$traceId] Tầng 2 execute error: ${e.message}", e)
                    RouterOutcome.RouterFailed("Tầng 2 execute failed: ${e.message}")
                }
            } else {
                logger.d("AgentKernel", "[$traceId] ⚠️ [Tầng 2 Low Confidence: ${layer2Result.confidence}] -> Chuyển sang Tầng 3")
            }
        }

        val layer3Result = processLayer3ClauseEntitySpotter(resolvedMessage, username, devicePlugins, traceId)
        when (layer3Result) {
            is Layer3Result.Single -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Single] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                val result = executeIntent(layer3Result.plugin, layer3Result.intent, resolvedMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer3Result.plugin.manifest.id, result))
            }
            is Layer3Result.Nested -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Nested] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                val result = executeIntent(layer3Result.wrapper, layer3Result.intent, resolvedMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer3Result.wrapper.manifest.id, result))
            }
            is Layer3Result.Multi -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Multi] ${layer3Result.intents.size} actions")
                val results = mutableListOf<String>()
                layer3Result.intents.forEach { (plugin, intent) ->
                    try {
                        val r = executeIntent(plugin, intent, resolvedMessage, traceId)
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
                return RouterOutcome.Matched(DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to combined))))
            }
            is Layer3Result.NoMatch -> { }
        }

        val layer4Result = tryTier2_5ActionMetadataMatcher(resolvedMessage, matchResult, devicePlugins)
        when (layer4Result) {
            is Layer3Result.Single -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Single] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                val result = executeIntent(layer4Result.plugin, layer4Result.intent, resolvedMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer4Result.plugin.manifest.id, result))
            }
            is Layer3Result.Nested -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Nested] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                val result = executeIntent(layer4Result.wrapper, layer4Result.intent, resolvedMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer4Result.wrapper.manifest.id, result))
            }
            is Layer3Result.Multi -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Multi] ${layer4Result.intents.size} actions")
                val results = mutableListOf<String>()
                layer4Result.intents.forEach { (plugin, intent) ->
                    try {
                        val r = executeIntent(plugin, intent, resolvedMessage, traceId)
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
                return RouterOutcome.Matched(DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to combined))))
            }
            is Layer3Result.NoMatch -> { }
        }

        logger.d("AgentKernel", "[$traceId] 🔵 [Tầng 4 Miss] -> LLM")
        return executeTier3LlmRouting(resolvedMessage, matchResult, devicePlugins, traceId)
    }

    private suspend fun processLayer3ClauseEntitySpotter(
        userMessage: String,
        username: String,
        devicePlugins: List<Plugin>,
        traceId: String
    ): Layer3Result {
        val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
        val clauses = clauseSeparator.split(userMessage).map { it.trim() }.filter { it.isNotBlank() }

        val intentQAs = trainingSkill.getRawCachedQAList(username)
            .filter { it.type == "intent" }
            .sortedByDescending { it.question.length }

        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()
        val absorbedActions = mutableSetOf<String>()

        // ✅ ĐÃ SỬA: VÒNG LẶP 1: Ưu tiên bóc tách và phân tích các bộ khung lên lịch trình (Wrapper - schedule) trước bằng câu lệnh gốc đầy đủ
        for (clause in clauses) {
            val clauseNorm = normalizeVietnamese(clause)
            
            // Lọc và chỉ quét các QA có chứa câu lệnh cấu trúc schedule để tránh bị Leaf (gửi email) cướp quyền [1]
            val wrapperQA = intentQAs
                .filter { it.answer.contains("\"plugin\":\"schedule\"") }
                .firstOrNull { clauseNorm.contains(normalizeVietnamese(it.question)) }

            if (wrapperQA != null) {
                val rootJson = try { JSONObject(wrapperQA.answer) } catch (e: Exception) { null }
                val rootPluginId = rootJson?.optString("plugin") ?: ""
                val rootActionName = rootJson?.optString("action") ?: ""
                
                val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                
                if (targetPlugin != null && targetAction != null) {
                    val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                    
                    val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, username, aliasThreshold = 0.0f)

                    val resolvedParams = resolveParametersWithMeta(
                        parameters = targetAction.parameters,
                        inputParams = rootParams,
                        matchResult = matchResult,
                        userMessage = userMessage,
                        devicePlugins = devicePlugins,
                        excludeIntentId = wrapperQA.id,
                        depth = 0
                    )

                    resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))

                    val nestedPluginId = resolvedParams["pluginId"]?.toString() ?: ""
                    val nestedAction = resolvedParams["action"]?.toString() ?: ""
                    if (nestedPluginId.isNotBlank() && nestedAction.isNotBlank()) {
                        absorbedActions.add("$nestedPluginId.$nestedAction")
                    }
                }
            }
        }

        // ✅ ĐÃ SỬA: VÒNG LẶP 2: Xử lý các câu lệnh đơn lẻ hành động độc lập (Leaf) còn lại
        for (clause in clauses) {
            val clauseNorm = normalizeVietnamese(clause)
            
            // Loại bỏ hoàn toàn plugin schedule ở vòng quét thứ hai [1]
            val leafQA = intentQAs
                .filter { !it.answer.contains("\"plugin\":\"schedule\"") }
                .firstOrNull { clauseNorm.contains(normalizeVietnamese(it.question)) }

            if (leafQA != null) {
                val rootJson = try { JSONObject(leafQA.answer) } catch (e: Exception) { null }
                val rootPluginId = rootJson?.optString("plugin") ?: ""
                val rootActionName = rootJson?.optString("action") ?: ""
                
                if (absorbedActions.contains("$rootPluginId.$rootActionName")) continue

                val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                
                if (targetPlugin != null && targetAction != null) {
                    val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                    val matchResult = trainingSkill.fuzzyMatchCategorized(clause, username, aliasThreshold = 0.0f)

                    val resolvedParams = resolveParametersWithMeta(
                        parameters = targetAction.parameters,
                        inputParams = rootParams,
                        matchResult = matchResult,
                        userMessage = clause,
                        devicePlugins = devicePlugins,
                        excludeIntentId = leafQA.id,
                        depth = 0
                    )

                    resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
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

    private suspend fun tryTier2SemanticSlotResolver(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Layer2Result? {
        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD }
            ?.value?.toDoubleOrNull() ?: 0.3

        // ✅ ĐÃ SỬA: Ưu tiên chọn wrapper (như schedule) nếu có nhiều khớp để đảm bảo nạp đúng khung lập lịch [1]
        val wrapperIntentPair = matchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .find { it.first.answer.contains("\"plugin\":\"schedule\"") }

        val bestIntentPair = wrapperIntentPair ?: matchResult.intentMatches
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
            matchResult = matchResult,
            userMessage = userMessage,
            devicePlugins = devicePlugins,
            excludeIntentId = bestIntentQA.id,
            depth = 0
        )

        return Layer2Result(rootPlugin, Intent(rootPluginId, rootActionName, resolvedParams), confidence)
    }

    private suspend fun tryTier2_5ActionMetadataMatcher(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Layer3Result {
        val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
        val clauses = clauseSeparator.split(userMessage).map { it.trim() }.filter { it.isNotBlank() }

        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()
        val absorbedActions = mutableSetOf<String>()

        // ✅ ĐÃ SỬA: VÒNG LẶP 1: Ưu tiên bóc tách và phân tích các bộ khung lên lịch trình (Wrapper - schedule) trước bằng câu lệnh gốc đầy đủ
        for (clause in clauses) {
            val clauseNormalized = normalizeVietnamese(clause)
            
            // Lọc riêng Action metadata của plugin schedule ở vòng quét 1 để dứt điểm gán Wrapper [1]
            val bestMatch = normalizedActionMetadataList
                .filter { it.plugin.manifest.id == "schedule" && it.plugin.manifest.routable && it.action.enabled }
                .find { meta ->
                    meta.normalizedDescription.contains(clauseNormalized) ||
                    clauseNormalized.contains(meta.normalizedDescription) ||
                    meta.normalizedExamples.any { ex ->
                        ex.length >= 5 && (clauseNormalized.contains(ex) || ex.contains(clauseNormalized))
                    }
                }

            if (bestMatch != null) {
                val plugin = bestMatch.plugin
                val action = bestMatch.action

                val schemaParams = mutableMapOf<String, Any>()
                action.parameters.forEach { param ->
                    schemaParams[param.name] = param.defaultValue ?: ""
                }

                val fullMatchResult = trainingSkill.fuzzyMatchCategorized(userMessage, "default_user", aliasThreshold = 0.0f)

                val resolvedParams = resolveParametersWithMeta(
                    parameters = action.parameters,
                    inputParams = schemaParams,
                    matchResult = fullMatchResult,
                    userMessage = userMessage,
                    devicePlugins = devicePlugins,
                    excludeIntentId = null,
                    depth = 0
                )

                resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams))

                val nestedPluginId = resolvedParams["pluginId"]?.toString() ?: ""
                val nestedAction = resolvedParams["action"]?.toString() ?: ""
                if (nestedPluginId.isNotBlank() && nestedAction.isNotBlank()) {
                    absorbedActions.add("$nestedPluginId.$nestedAction")
                }
            }
        }

        // ✅ ĐÃ SỬA: VÒNG LẶP 2: Xử lý các câu lệnh đơn lẻ hành động độc lập (Leaf) còn lại
        for (clause in clauses) {
            val clauseNormalized = normalizeVietnamese(clause)
            
            // Bỏ qua plugin schedule ở vòng 2
            val bestMatch = normalizedActionMetadataList
                .filter { it.plugin.manifest.id != "schedule" && it.plugin.manifest.routable && it.action.enabled }
                .find { meta ->
                    meta.normalizedDescription.contains(clauseNormalized) || 
                    clauseNormalized.contains(meta.normalizedDescription) ||
                    meta.normalizedExamples.any { ex -> 
                        ex.length >= 5 && (clauseNormalized.contains(ex) || ex.contains(clauseNormalized)) 
                    }
                }

            if (bestMatch != null) {
                val plugin = bestMatch.plugin
                val action = bestMatch.action

                if (absorbedActions.contains("${plugin.manifest.id}.${action.name}")) continue

                val schemaParams = mutableMapOf<String, Any>()
                action.parameters.forEach { param ->
                    schemaParams[param.name] = param.defaultValue ?: ""
                }

                val clauseMatchResult = trainingSkill.fuzzyMatchCategorized(clause, "default_user", aliasThreshold = 0.0f)

                val resolvedParams = resolveParametersWithMeta(
                    parameters = action.parameters,
                    inputParams = schemaParams,
                    matchResult = clauseMatchResult,
                    userMessage = clause,
                    devicePlugins = devicePlugins,
                    excludeIntentId = null,
                    depth = 0
                )

                resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams))
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

    private fun calculateLocalSimilarity(clean1: String, clean2: String): Double {
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0
        if (clean1 == clean2) return 1.0

        val lenDiff = Math.abs(clean1.length - clean2.length)
        val maxLen = maxOf(clean1.length, clean2.length)
        if (maxLen > 10 && lenDiff.toDouble() / maxLen > 0.7) {
            return 0.0
        }

        val tokens1 = clean1.split(SPACE_REGEX).toSet()
        val tokens2 = clean2.split(SPACE_REGEX).toSet()
        val intersectionSize = tokens1.intersect(tokens2).size.toDouble()

        if (tokens1.size > 1 && tokens2.size > 1 && intersectionSize == 0.0) {
            return 0.0
        }

        val isWordMatch = when {
            clean1.contains(clean2) -> {
                val index = clean1.indexOf(clean2)
                val charBefore = if (index > 0) clean1[index - 1] else ' '
                val charAfter = if (index + clean2.length < clean1.length) clean1[index + clean2.length] else ' '
                charBefore.isWhitespace() && charAfter.isWhitespace()
            }
            clean2.contains(clean1) -> {
                val index = clean2.indexOf(clean1)
                val charBefore = if (index > 0) clean2[index - 1] else ' '
                val charAfter = if (index + clean1.length < clean2.length) clean2[index + clean1.length] else ' '
                charBefore.isWhitespace() && charAfter.isWhitespace()
            }
            else -> false
        }

        if (isWordMatch) {
            return 0.95
        }

        val union = tokens1.union(tokens2).size.toDouble()
        val jaccard = if (union > 0) intersectionSize / union else 0.0

        val lenDistance = levenshteinDistance(clean1, clean2)
        val levSim = 1.0 - (lenDistance.toDouble() / maxLen)

        return (jaccard * 0.4) + (levSim * 0.6)
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

    private fun normalizeVietnamese(text: String): String {
        val temp = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val regex = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        return regex.replace(temp, "")
            .replace("đ", "d")
            .replace("Đ", "D")
            .lowercase()
            .trim()
            .replace(SPACE_REGEX, " ")
    }

    private suspend fun resolveParametersWithMeta(
        parameters: List<PluginParameter>,
        inputParams: Map<String, Any>,
        matchResult: TrainingSkill.MatchResult,
        userMessage: String,
        devicePlugins: List<Plugin>,
        excludeIntentId: String? = null,
        depth: Int = 0
    ): Map<String, Any> {
        if (depth > MAX_DEPTH) return emptyMap()

        val localEntities = mutableMapOf<String, Any>()
        if (userMessage.isNotBlank()) {
            EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
            parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
            parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }
        }

        val secondaryIntentQA = matchResult.intentMatches
            .filter { it.first.type == "intent" }
            .map { it.first }
            .firstOrNull { it.id != excludeIntentId }

        val resolved = mutableMapOf<String, Any>()

        parameters.forEach { param ->
            val currentValue = inputParams[param.name]
            val isPlh = isPlaceholder(currentValue, param)

            val resolver = resolverTable[param.semanticType.lowercase()]
            if (resolver != null) {
                resolved[param.name] = resolver(
                    param, currentValue, isPlh, matchResult, localEntities,
                    secondaryIntentQA, devicePlugins, excludeIntentId, depth, userMessage
                ) ?: ""
            } else {
                resolved[param.name] = resolveAliasOrFallback(param, currentValue, isPlh, matchResult, localEntities, userMessage)
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
        matchResult: TrainingSkill.MatchResult,
        secondaryIntentQA: QAEntity?,
        devicePlugins: List<Plugin>,
        excludeIntentId: String?,
        depth: Int,
        userMessage: String
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
                    matchResult = matchResult,
                    userMessage = userMessage,
                    devicePlugins = devicePlugins,
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
        matchResult: TrainingSkill.MatchResult,
        localEntities: Map<String, Any>,
        userMessage: String = ""
    ): Any {
        var finalIsPlh = isPlh
        
        if (!finalIsPlh && currentValue != null) {
            val isAliasVal = matchResult.aliasMatches.any { 
                it.first.category == param.semanticType && 
                it.first.question.equals(currentValue.toString().trim(), ignoreCase = true) 
            }
            if (isAliasVal) {
                finalIsPlh = true
            }
        }

        if (!finalIsPlh) return currentValue ?: ""

        val matchedAliasValue = aliasMatchesForType(matchResult, param.semanticType)
        if (matchedAliasValue != null) return matchedAliasValue

        if (userMessage.isNotBlank()) {
            val containsMatch = matchResult.aliasMatches
                .filter { it.first.category == param.semanticType }
                .sortedByDescending { it.first.question.length }
                .firstOrNull { userMessage.contains(it.first.question, ignoreCase = true) }
                ?.first?.answer
            if (containsMatch != null) return containsMatch
        }

        val localMatch = localEntities[param.semanticType]
        if (localMatch != null) return localMatch

        return currentValue ?: ""
    }

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
    }


    private fun parseVietnameseTime(message: String): String? {
    val lower = message.lowercase().trim()
    
    // 1. Trích xuất giờ và phút (hỗ trợ cả dạng 7h và dạng điện tử 2:00)
    var extractedHour: Int? = null
    var extractedMinute: Int = 0
    
    // Thử khớp định dạng điện tử (VD: 2:00 hoặc 14:30)
    val digitalRegex = Regex("\\b(\\d{1,2}):(\\d{2})\\s*(sáng|chiều|tối|đêm)?\\b")
    val digitalMatch = digitalRegex.find(lower)
    
    if (digitalMatch != null) {
        var hour = digitalMatch.groupValues[1].toIntOrNull() ?: 0
        extractedMinute = digitalMatch.groupValues[2].toIntOrNull() ?: 0
        val period = digitalMatch.groupValues[3]
        
        if ((period == "chiều" || period == "tối") && hour < 12) {
            hour += 12
        } else if (period == "đêm" && hour == 12) {
            hour = 0
        }
        extractedHour = hour
    } else {
        // Thử khớp định dạng chữ thông thường (VD: 7 giờ sáng, 7h sáng)
        val hourRegex = Regex("\\b(\\d+)\\s*(giờ|g|h)\\s*(sáng|chiều|tối|đêm)?\\b")
        val hourMatch = hourRegex.find(lower)
        if (hourMatch != null) {
            var hour = hourMatch.groupValues[1].toIntOrNull() ?: 0
            val period = hourMatch.groupValues[3]
            if ((period == "chiều" || period == "tối") && hour < 12) {
                hour += 12
            } else if (period == "đêm" && hour == 12) {
                hour = 0
            }
            extractedHour = hour
        }
    }

    // 2. Trích xuất nhiều thứ trong tuần (gom tất cả các thứ tìm thấy thành danh sách ngăn cách bởi dấu phẩy)
    val days = mutableListOf<String>()
    
    val mondayRegex = Regex("\\b(thứ hai|thứ 2)\\b")
    val tuesdayRegex = Regex("\\b(thứ ba|thứ 3)\\b")
    val wednesdayRegex = Regex("\\b(thứ tư|thứ 4)\\b")
    val thursdayRegex = Regex("\\b(thứ năm|thứ 5)\\b")
    val fridayRegex = Regex("\\b(thứ sáu|thứ 6)\\b")
    val saturdayRegex = Regex("\\b(thứ bảy|thứ 7)\\b")
    val sundayRegex = Regex("\\b(chủ nhật|cn)\\b")

    if (mondayRegex.containsMatchIn(lower)) days.add("1")
    if (tuesdayRegex.containsMatchIn(lower)) days.add("2")
    if (wednesdayRegex.containsMatchIn(lower)) days.add("3")
    if (thursdayRegex.containsMatchIn(lower)) days.add("4")
    if (fridayRegex.containsMatchIn(lower)) days.add("5")
    if (saturdayRegex.containsMatchIn(lower)) days.add("6")
    if (sundayRegex.containsMatchIn(lower)) days.add("0")

    // Nếu tìm thấy thứ, kết hợp với giờ và phút (mặc định giờ là 0 nếu không nói)
    if (days.isNotEmpty()) {
        val hour = extractedHour ?: 0
        val dayOfWeek = days.joinToString(",")
        return "$extractedMinute $hour * * $dayOfWeek"
    }

    // 3. Trích xuất các trường hợp lặp ngày/tuần hoặc "ngày mai"
    val dailyRegex = Regex("\\b(mỗi ngày|hằng ngày|hàng ngày)\\b")
    if (dailyRegex.containsMatchIn(lower)) {
        val hour = extractedHour ?: 0
        return "$extractedMinute $hour * * *"
    }
    
    val weeklyRegex = Regex("\\b(hằng tuần|hàng tuần)\\b")
    if (weeklyRegex.containsMatchIn(lower)) {
        val hour = extractedHour ?: 0
        return "$extractedMinute $hour * * 0" // Mặc định Chủ Nhật
    }
    
    val tomorrowRegex = Regex("\\b(ngày mai|mai)\\b")
    if (tomorrowRegex.containsMatchIn(lower)) {
        val hour = extractedHour ?: 8 // Mặc định 8h sáng nếu không nói giờ cụ thể
        return "$extractedMinute $hour * * *"
    }

    // 4. Nếu chỉ có giờ độc lập (mỗi ngày vào giờ đó)
    if (extractedHour != null) {
        return "$extractedMinute $extractedHour * * *"
    }

    return null
}

    private fun parseVietnameseInterval(message: String): Int? {
        val lower = message.lowercase()
        val intervalRegex = Regex("mỗi\\s*(\\d+)\\s*phút")
        val match = intervalRegex.find(lower)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun getUnresolvedParams(params: Map<String, Any>, plugin: Plugin, actionName: String): List<String> {
        val missing = mutableListOf<String>()
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return missing

        action.parameters.filter { it.required }.forEach { param ->
            val value = params[param.name]
            if (isPlaceholder(value, param)) {
                missing.add(param.name)
            }
        }

        val paramsMeta = action.parameters.find { it.semanticType == "params" || it.name == "params" }
        if (paramsMeta != null) {
            val value = params[paramsMeta.name]
            val nestedParams = value as? Map<*, *>
            val targetPluginId = params["plugin_id"]?.toString()
                ?: params["pluginId"]?.toString()
                ?: params["plugin"]?.toString() ?: ""
            val targetAction = params["action_id"]?.toString()
                ?: params["action"]?.toString()
                ?: params["actionId"]?.toString() ?: ""
            
            if (targetPluginId.isNotBlank() && targetAction.isNotBlank()) {
                val tPlugin = plugins.find { it.manifest.id == targetPluginId }
                val tAction = tPlugin?.manifest?.actions?.find { it.name == targetAction }
                
                if (tAction != null) {
                    if (nestedParams == null) {
                        tAction.parameters.filter { it.required }.forEach { subParam ->
                            missing.add("params.${subParam.name}")
                        }
                    } else {
                        tAction.parameters.filter { it.required }.forEach { subParam ->
                            val subVal = nestedParams[subParam.name]
                            if (isPlaceholder(subVal, subParam)) {
                                missing.add("params.${subParam.name}")
                            }
                        }
                    }
                }
            }
        }

        return missing.distinct()
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
        userMessage: String,
        traceId: String
    ): PluginResult {
        val normalizedParams = normalizeParams(intent.params, plugin, intent.action, userMessage)
        val normalizedIntent = intent.copy(params = normalizedParams)

        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"] ?: normalizedIntent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val missing = getUnresolvedParams(normalizedIntent.params, plugin, normalizedIntent.action)

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
                // ── TẦNG 0: Cập nhật Conversation Focus sau khi thực thi THÀNH CÔNG ──
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
        chatHistoryManager.addTurn(userMessage, replyForHistory)

        return executionResult
    }

    private suspend fun tryResolvePendingIntent(
        pending: PendingIntent,
        userMessage: String,
        devicePlugins: List<Plugin>,
        traceId: String
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

        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, "default_user", aliasThreshold = 0.0f)

        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
        parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
        parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

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
                        if (paramMeta.semanticType == "email" && localEntities.containsKey("email")) {
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

        // ── ĐÃ SỬA: XÁC ĐỊNH THAM SỐ ĐANG HỎI ĐỂ ĐỐI CHIẾU BYPASS GROQ ──
        val currentAskedParam = pending.missingParams.firstOrNull()
        
        val filled = if (currentAskedParam != null && heuristicFilled.containsKey(currentAskedParam)) {
            logger.d("AgentKernel", "[$traceId] Heuristic tự xử lý thành công tham số '$currentAskedParam'. Bypass LLM.")
            heuristicFilled
        } else {
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

        val finalFilled = filled + heuristicFilled

        val flatFilled = finalFilled.filterKeys { !it.startsWith("params.") && it != "params" }
        val nestedFilled = finalFilled.filterKeys { it.startsWith("params.") }.mapKeys { it.key.removePrefix("params.") }

        @Suppress("UNCHECKED_CAST")
        val existingNested = (pending.knownParams["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value ?: "") } ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val mergedNested = (existingNested as Map<String, Any>) + nestedFilled + (finalFilled["params"] as? Map<String, Any> ?: emptyMap())

        val mergedParams = pending.knownParams + flatFilled +
            if (mergedNested.isNotEmpty()) mapOf("params" to mergedNested) else emptyMap()

        val normalizedMergedParams = normalizeParams(mergedParams, targetPlugin, pending.action, userMessage)

        val stillMissing = pending.missingParams.filter { key ->
            if (key.startsWith("params.")) {
                val nestedKey = key.removePrefix("params.")
                @Suppress("UNCHECKED_CAST")
                val nested = normalizedMergedParams["params"] as? Map<String, Any>
                val v = nested?.get(nestedKey)
                v == null || v.toString().isBlank()
            } else {
                val v = normalizedMergedParams[key]
                v == null || v.toString().isBlank()
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
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>,
        traceId: String
    ): RouterOutcome {
        val queryNormalized = normalizeVietnamese(userMessage)
        val configAliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)

        val foundAliases = matchResult.aliasMatches
            .filter { it.second >= configAliasThreshold }
            .joinToString("\n") { "  - \"${it.first.question}\" ánh xạ sang \"${it.first.answer}\" (danh mục: ${it.first.category})" }

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

        // ── CẢI TIẾN PROMPT: ÉP PHÂN BIỆT CHAT THƯỜNG / KỂ CHUYỆN / TÁN GẪU ──
        val routerPrompt = buildString {
            append("<sys>Intent Formatter. Bạn là bộ định tuyến lệnh. Chỉ được phép xuất JSON thô dạng: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("QUY TẮC BẮT BUỘC:\n")
            append("1. Nếu câu lệnh của người dùng khớp với một hành động thiết bị trong danh sách <candidates>, hãy xuất JSON điều khiển tương ứng.\n")
            append("2. Nếu người dùng đang nói chuyện phiếm, tán gẫu, yêu cầu kể chuyện (ví dụ: 'kể chuyện', 'một câu chuyện'), chào hỏi ('hello', 'hi'), hoặc hỏi đáp kiến thức tổng hợp KHÔNG liên quan trực tiếp đến việc điều khiển thiết bị, bạn BẮT BUỘC phải xuất đúng cấu trúc: {\"plugin\":\"chat\",\"action\":\"none\",\"params\":{}}\n")
            append("3. Tuyệt đối KHÔNG được viết lời giải thích, không được tư vấn, không được trả lời hội thoại bằng văn bản thường. Chỉ xuất duy nhất JSON.</sys>\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            if (foundAliases.isNotEmpty()) append("<aliases>\n$foundAliases\n</aliases>\n")
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>$userMessage</input>\n")
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
            matchResult = matchResult,
            userMessage = userMessage,
            devicePlugins = devicePlugins,
            excludeIntentId = null,
            depth = 0
        )
        
        val intent = rawIntent.copy(params = finalParams)
        val result = executeIntent(targetPlugin, intent, userMessage, traceId)

        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = result))
    }

    
    private fun normalizeParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String, 
        userMessage: String? = null
    ): Map<String, Any> {
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return params
        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val nested = value as Map<String, Any>
                val targetPluginId = params["plugin_id"]?.toString()
                    ?: params["pluginId"]?.toString()
                    ?: params["plugin"]?.toString() ?: ""
                val targetAction = params["action_id"]?.toString()
                    ?: params["action"]?.toString()
                    ?: params["actionId"]?.toString() ?: ""
                val targetPlugin = plugins.find { it.manifest.id == targetPluginId }
                return@mapValues if (targetPlugin != null && targetAction.isNotEmpty()) {
                    normalizeParams(nested, targetPlugin, targetAction, userMessage)
                } else nested
            }

            val paramMeta = action.parameters.find { it.name == key } ?: return@mapValues value

            var rawValue = value
            if (paramMeta.type.lowercase() == "boolean" && 
                (value.toString().isBlank() || value.toString() == "null") && 
                userMessage != null) {
                rawValue = extractBooleanFromMessage(userMessage) ?: value
            }

            paramMeta.normalize(rawValue) ?: paramMeta.defaultValue ?: ""
        }
    }

    private fun extractBooleanFromMessage(userMessage: String): Boolean? {
        val str = userMessage.lowercase()
        val trueWords = setOf("mở", "bật", "on")
        val falseWords = setOf("tắt", "off")
        val hasTrue = trueWords.any { str.contains(it) }
        val hasFalse = falseWords.any { str.contains(it) }
        return when {
            hasTrue && !hasFalse -> true
            hasFalse && !hasTrue -> false
            else -> null
        }
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

        val normalizedParams = normalizeParams(params, plugin, action, null)

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
