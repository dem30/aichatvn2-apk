

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
    val matchResult: TrainingSkill.MatchResult? = null
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

        private val COMMAND_TRIGGER_KEYWORDS = setOf(
            "bat", "tat", "mo", "dong", "chay", "quet", "gui", "len lich", "dat lich", "kiem tra", "setup",
            "den", "quat", "camera", "bom", "khoa", "thiet bi", "tuya", "canh bao", "email", "thu", "status",
            "huy", "xoa", "sua", "doi", "them", "xem", "hen gio", "bao thuc"
        )

        private const val MAX_FALLBACK_PLUGINS = 3
    }

    private fun isPotentialCommand(message: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(message.lowercase().trim())
        return COMMAND_TRIGGER_KEYWORDS.any { keyword -> norm.contains(keyword) }
    }

    private fun detectScheduleAction(queryNormalized: String): String {
        val cancelVerbs = setOf("huy", "xoa", "bo", "tat lich", "ngung", "tat hen gio", "dung lich")
        val listVerbs = setOf("xem", "liet ke", "danh sach", "kiem tra", "hien thi")
        val updateVerbs = setOf("sua", "doi", "cap nhat", "thay doi", "dat lai", "chinh sua")
        
        return when {
            cancelVerbs.any { queryNormalized.contains(it) } -> "cancel"
            listVerbs.any { queryNormalized.contains(it) } -> "list"
            updateVerbs.any { queryNormalized.contains(it) } -> "update"
            else -> "add"
        }
    }

    private fun buildPendingBanner(pending: PendingIntent, pluginName: String, actionDesc: String): String {
        val targetPlugin = plugins.find { it.manifest.id == pending.pluginId }
        val targetAction = targetPlugin?.manifest?.actions?.find { it.name == pending.action }
        
        var validKnownCount = 0
        pending.knownParams.filterKeys { !it.startsWith("_") }.forEach { (k, v) ->
            if (k == "params" && v is Map<*, *>) {
                val targetPluginId = pending.knownParams["plugin_id"]?.toString()
                    ?: pending.knownParams["pluginId"]?.toString()
                    ?: pending.knownParams["plugin"]?.toString() ?: ""
                val targetActionName = pending.knownParams["action_id"]?.toString()
                    ?: pending.knownParams["action"]?.toString()
                    ?: pending.knownParams["actionId"]?.toString() ?: ""
                val tPlugin = plugins.find { it.manifest.id == targetPluginId }
                val tAction = tPlugin?.manifest?.actions?.find { it.name == targetActionName }

                v.forEach { (subK, subV) ->
                    val subParamMeta = tAction?.parameters?.find { it.name == subK.toString() }
                    if (!ParameterResolver.isPlaceholder(subV, subParamMeta)) {
                        validKnownCount++
                    }
                }
            } else if (k != "params") {
                val paramMeta = targetAction?.parameters?.find { it.name == k }
                if (!ParameterResolver.isPlaceholder(v, paramMeta)) {
                    validKnownCount++
                }
            }
        }
        
        val totalCount = validKnownCount + pending.missingParams.size
        return """
            📋 [Đang thực hiện]: $pluginName -> $actionDesc
            ⏳ Trạng thái: Đã thu thập $validKnownCount/$totalCount thông tin.
            ────────────────────────
            👉 ${pending.askedQuestion}
        """.trimIndent()
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

        if (message.isBlank() && imageBase64.isNullOrEmpty() && fileUrl.isNullOrEmpty()) {
            return ChatResponse("", "empty_message_guard", null)
        }

        val expiredNotification = chatHistoryManager.popExpiredNotificationMessage(plugins)

        if (request.allowDeviceControl) {
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
            val responseText = when (val result = outcome.result.result) {
                is PluginResult.Success -> {
                    val data = result.data as? Map<*, *>?
                    data?.get("message") as? String ?: "✅ Đã thực hiện thành công."
                }
                is PluginResult.Failure -> result.error
                is PluginResult.NeedMoreInfo -> result.question
            }
            val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
            return ChatResponse(finalMsg, "device_control", outcome.result.pluginId)
        }
        
        val routerFailed = outcome is RouterOutcome.RouterFailed
        val usedMode = request.chatMode
        val usedPluginId = if (routerFailed) "router_error" else null
        
        val ANTI_HALLUCINATION_GUARD =
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được."

        val guard = if (routerFailed) {
            ANTI_HALLUCINATION_GUARD + "\n" +
                "⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định được chính xác (lý do nội bộ: ${(outcome as RouterOutcome.RouterFailed).reason}). Hãy báo cho user là lệnh CHƯA thực hiện được và hỏi họ nói rõ hơn, ĐỪNG khẳng định đã làm."
        } else {
            ANTI_HALLUCINATION_GUARD
        }

        var responseText = try {
            withTimeout(30_000L) {
                when (usedMode.lowercase()) {
                    "qa" -> {
                        val matches = search(message, username, 0.6f)
                        val qa = matches.firstOrNull()?.qa
                        qa?.answer ?: "Không tìm thấy câu trả lời phù hợp trong danh sách huấn luyện."
                    }

                    "groq" -> {
                        val historySnapshot = try {
                            database.chatMessageDao().getMessages(username, 6)
                                .reversed()
                                .map { mapOf("role" to it.role, "content" to it.content) }
                        } catch (e: Exception) {
                            emptyList()
                        }

                        val cleanContext = buildString {
                            append(guard)
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                        }

                        groqClient.chat(
                            message = message,
                            extraContext = cleanContext,
                            history = historySnapshot,
                            imageUrl = null
                        )
                    }

                    else -> {
                        val matches = search(message, username, 0.85f)
                        val perfectMatch = matches.firstOrNull()?.qa

                        if (perfectMatch != null) {
                            perfectMatch.answer
                        } else {
                            val historySnapshot = try {
                                database.chatMessageDao().getMessages(username, 6)
                                    .reversed()
                                    .map { mapOf("role" to it.role, "content" to it.content) }
                            } catch (e: Exception) {
                                emptyList()
                            }
                            
                            val qaContext = buildQAContextForAgent(message, username)
                            val fullContext = buildString {
                                append(guard)
                                if (qaContext.isNotEmpty()) append("\n\n$qaContext")
                                if (extraContext.isNotEmpty()) append("\n\n$extraContext")
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
            }
        } catch (e: Exception) {
            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
        }
        
        if (expiredNotification != null) {
            responseText = "$expiredNotification\n\n$responseText"
        }
        return ChatResponse(responseText, usedMode, usedPluginId)
    }

    private suspend fun buildQAContextForAgent(message: String, username: String): String {
        // Chỉ lấy các dữ liệu huấn luyện có loại là "qa" hoặc "chat"
        val matches = search(message, username, 0.7f).filter { it.qa.type == "qa" || it.qa.type == "chat" }
        if (matches.isEmpty()) return ""
        return matches.joinToString("\n") { match ->
            "📚 Q: ${match.qa.question}\n   A: ${match.qa.answer} (độ tương tự: ${String.format("%.2f", match.similarity)})"
        }
    }



    // Trong AgentKernel.kt — Cập nhật hàm tryDeviceCommand:



    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = generateTraceId()
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")

        val normMsg = StringSimilarityUtil.normalizeVietnamese(userMessage.trim())
        val isAskingPending = normMsg.contains("dang cho gi") || 
            normMsg.contains("kiem tra yeu cau") || 
            (normMsg.contains("cho") && normMsg.contains("gi") && normMsg.contains("pending")) ||
            normMsg.contains("dang bi ket")

        if (isAskingPending) {
            val active = chatHistoryManager.getActivePendingIntents()
            if (active.isEmpty()) {
                return RouterOutcome.Matched(
                    DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Hiện tại không có yêu cầu điều khiển thiết bị nào đang chờ xử lý.")))
                )
            }
            val first = active.first()
            val targetPlugin = plugins.find { it.manifest.id == first.pluginId }
            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == first.action }
            val pluginName = targetPlugin?.manifest?.name ?: first.pluginId
            val actionDesc = targetAction?.description ?: first.action
            val banner = buildPendingBanner(first, pluginName, actionDesc)
            
            @Suppress("UNCHECKED_CAST")
            val currentOptions = first.knownParams["_options"] as? Map<String, String> ?: emptyMap()
            return RouterOutcome.Matched(
                DeviceCommandResult(first.pluginId, PluginResult.NeedMoreInfo(first.missingParams, banner, currentOptions))
            )
        }

        // ✅ ĐÃ SỬA: Truyền username để kiểm tra yêu cầu khóa biệt lập theo phiên [1]
        chatHistoryManager.getPendingLockRequest(username)?.let { pluginId ->
            return handleLockConfirmation(userMessage, pluginId, username)
        }

        // ✅ ĐÃ SỬA: Lấy trạng thái khóa riêng biệt của riêng user này [1]
        chatHistoryManager.getLockedPlugin(username)?.let { lockedId ->
            if (isExitLockPhrase(userMessage)) {
                chatHistoryManager.unlockPlugin(username) // Mở khóa riêng biệt cho user này [1]
                val matchedPlugin = plugins.find { it.manifest.id == lockedId }
                val displayName = matchedPlugin?.manifest?.name ?: lockedId
                return RouterOutcome.Matched(
                    DeviceCommandResult(lockedId, PluginResult.Success(mapOf("message" to "✅ Đã thoát chế độ điều khiển riêng cho \"$displayName\".")))
                )
            }
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

        detectLockTrigger(userMessage)?.let { targetPluginId ->
            // ✅ ĐÃ SỬA: Truyền username để ghi nhận yêu cầu khóa riêng biệt [1]
            chatHistoryManager.setPendingLockRequest(username, targetPluginId)
            val matchedPlugin = plugins.find { it.manifest.id == targetPluginId }
            val displayName = matchedPlugin?.manifest?.name ?: targetPluginId
            return RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Bạn muốn vào chế độ điều khiển riêng biệt cho \"$displayName\", đúng không?")))
            )
        }

        val result = runPipeline(userMessage, username, PipelineMode.EXECUTE, traceId)
        return result.routerOutcome ?: RouterOutcome.RouterFailed("Pipeline execution error")
    }

// Cập nhật thêm tham số username cho hàm handleLockConfirmation:

private fun handleLockConfirmation(userMessage: String, pluginId: String, username: String): RouterOutcome {
        val matchedPlugin = plugins.find { it.manifest.id == pluginId }
        val displayName = matchedPlugin?.manifest?.name ?: pluginId
        return when (parseYesNo(userMessage)) {
            true -> {
                chatHistoryManager.lockPlugin(username, pluginId) // Khóa riêng biệt cho user này [1]
                chatHistoryManager.clearLockRequest(username)
                RouterOutcome.Matched(
                    DeviceCommandResult(pluginId, PluginResult.Success(mapOf("message" to "🔒 Đã vào chế độ điều khiển riêng biệt cho \"$displayName\". Tất cả hội thoại thông thường sẽ bị chặn cho đến khi bạn yêu cầu \"thoát\".")))
                )
            }
            false -> {
                chatHistoryManager.clearLockRequest(username)
                RouterOutcome.Matched(
                    DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Đã hủy yêu cầu điều khiển riêng.")))
                )
            }
            null -> RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Xác nhận vào chế độ điều khiển riêng cho \"$displayName\" chứ? (có/không)")))
            )
        }
    }


    

    private suspend fun runPipeline(
        userMessage: String,
        username: String,
        mode: PipelineMode,
        traceId: String,
        forcedPluginIds: List<String>? = null
    ): PipelineResult {
        val devicePlugins = plugins.filter { 
            it.manifest.routable && (forcedPluginIds == null || it.manifest.id in forcedPluginIds)
        }
        if (devicePlugins.isEmpty()) {
            return PipelineResult(routerOutcome = RouterOutcome.NotACommand)
        }

        val simulatedTiers = mutableListOf<DiagnosticTier>()
        var finalOutcome: String? = null
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
        val tier2HighConf = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.80f)

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

        val pronounResult = dialogManager.resolvePronoun(userMessage, username, System.currentTimeMillis())
        val resolvedMessage = pronounResult.rewrittenMessage
        if (pronounResult.wasResolved) {
            logger.d("AgentKernel", "[$traceId] [Tầng 0] Pronoun Resolver: \"${pronounResult.resolvedFrom}\" -> \"${pronounResult.resolvedTo}\". Câu sau khi viết lại: \"$resolvedMessage\"")
        }

        val pendings = chatHistoryManager.getActivePendingIntents()
        val isT1Matched = pendings.isNotEmpty() && cancelDecision !is CancelDecision.CancelPending

        if (mode == PipelineMode.EXECUTE) {
            if (pendings.isNotEmpty()) {
                logger.d("AgentKernel", "[$traceId] [Tầng 1] Phát hiện lệnh dở dang. Tiến hành giải quyết tuần tự.")
                
                val activePending = pendings.first()
                val resolvedResult = tryResolvePendingIntent(activePending, userMessage, devicePlugins, traceId, mode)


                if (resolvedResult != null) {
                    val r = resolvedResult.result
                    val finalResult = when (r) {
                        is PluginResult.Success -> {
                            chatHistoryManager.removePendingIntent(activePending.pluginId, activePending.action)
                            
                            // KIỂM TRA CHỦ ĐỘNG: Xem còn lệnh dở dang nào tiếp theo trong hàng đợi không
                            val remainingPendings = chatHistoryManager.getActivePendingIntents()
                            if (remainingPendings.isNotEmpty()) {
                                val nextPending = remainingPendings.first()
                                val targetPlugin = plugins.find { it.manifest.id == nextPending.pluginId }
                                val targetAction = targetPlugin?.manifest?.actions?.find { it.name == nextPending.action }
                                val pluginName = targetPlugin?.manifest?.name ?: nextPending.pluginId
                                val actionDesc = targetAction?.description ?: nextPending.action
                                
                                val successMsg = (r.data as? Map<*, *>)?.get("message") as? String ?: "✅ Đã thực hiện thành công."
                                val nextBanner = buildPendingBanner(nextPending, pluginName, actionDesc)
                                
                                val combinedMsg = if (remainingPendings.size > 1) {
                                    "$successMsg\n\n$nextBanner\n*(Còn ${remainingPendings.size - 1} yêu cầu khác đã được xếp hàng chờ)*"
                                } else {
                                    "$successMsg\n\n$nextBanner"
                                }
                                
                                // Trả về NeedMoreInfo của lệnh tiếp theo để giữ luồng thu thập tham số tiếp tục kích hoạt
                                @Suppress("UNCHECKED_CAST")
                                val nextOptions = nextPending.knownParams["_options"] as? Map<String, String> ?: emptyMap()
                                PluginResult.NeedMoreInfo(nextPending.missingParams, combinedMsg, nextOptions)
                            } else {
                                r
                            }
                        }
                        is PluginResult.NeedMoreInfo -> {
                            val targetPlugin = plugins.find { it.manifest.id == activePending.pluginId }
                            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == activePending.action }
                            val pluginName = targetPlugin?.manifest?.name ?: activePending.pluginId
                            val actionDesc = targetAction?.description ?: activePending.action
                            
                            val banner = buildPendingBanner(
                                activePending.copy(missingParams = r.missingParams, askedQuestion = r.question),
                                pluginName,
                                actionDesc
                            )
                            PluginResult.NeedMoreInfo(r.missingParams, banner, r.options)
                        }
                        is PluginResult.Failure -> {
                            chatHistoryManager.removePendingIntent(activePending.pluginId, activePending.action)
                            r
                        }
                    }
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(activePending.pluginId, finalResult))
                    )
                }
                
            }
        } else {
            if (isT1Matched && finalOutcome == null) {
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
                    val completedMsgs = mutableListOf<String>()
                    val pendingResults = mutableListOf<PendingResultInfo>()

                    layer3Result.intents.forEach { (plugin, intent) ->
                        val res = executeIntent(plugin, intent, context, traceId)
                        when (res) {
                            is PluginResult.Success -> {
                                val msg = (res.data as? Map<*, *>? )?.get("message") as? String ?: "✅ Đã thực hiện ${plugin.manifest.name}."
                                completedMsgs.add(msg)
                            }
                            is PluginResult.Failure -> {
                                completedMsgs.add("❌ ${plugin.manifest.name} thất bại: ${res.error}")
                            }
                            is PluginResult.NeedMoreInfo -> {
                                pendingResults.add(PendingResultInfo(plugin, intent.action, res))
                            }
                        }
                    }

                    val completedText = completedMsgs.joinToString("\n")

                    return if (pendingResults.isNotEmpty()) {
                        val (firstPlugin, firstActionName, firstPendingRes) = pendingResults.first()
                        val activePending = chatHistoryManager.getActivePendingIntents()
                            .firstOrNull { it.pluginId == firstPlugin.manifest.id && it.action == firstActionName }

                        val actionDesc = firstPlugin.manifest.actions.find { it.name == firstActionName }?.description ?: firstActionName
                        
                        val banner = if (activePending != null) {
                            val baseBanner = buildPendingBanner(activePending, firstPlugin.manifest.name, actionDesc)
                            if (pendingResults.size > 1) {
                                "$baseBanner\n*(Còn ${pendingResults.size - 1} yêu cầu khác đã được xếp hàng chờ)*"
                            } else {
                                baseBanner
                            }
                        } else {
                            firstPendingRes.question
                        }

                        val combinedMsg = if (completedText.isNotEmpty()) {
                            "$completedText\n\n$banner"
                        } else {
                            banner
                        }

                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(
                                DeviceCommandResult(firstPlugin.manifest.id, PluginResult.NeedMoreInfo(firstPendingRes.missingParams, combinedMsg, firstPendingRes.options))

                                
                            ),
                            matchResult = matchResult
                        )
                    } else {
                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(
                                DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to completedText)))
                            ),
                            matchResult = matchResult
                        )
                    }
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
                        "• Tách thành công câu lệnh đơn '${layer3Result.intent.pluginId}.${layer3Result.intent.action}'. Phát hiện thiếu thông tin: [${missing.joinToString { getQuestionForMissingParamDiagnostic(it, layer3Result.plugin, layer3Result.intent.action) }}]"
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
                    val completedMsgs = mutableListOf<String>()
                    val pendingResults = mutableListOf<PendingResultInfo>()

                    layer4Result.intents.forEach { (plugin, intent) ->
                        val res = executeIntent(plugin, intent, context, traceId)
                        when (res) {
                            is PluginResult.Success -> {
                                val msg = (res.data as? Map<*, *>? )?.get("message") as? String ?: "✅ Đã thực hiện ${plugin.manifest.name}."
                                completedMsgs.add(msg)
                            }
                            is PluginResult.Failure -> {
                                completedMsgs.add("❌ ${plugin.manifest.name} thất bại: ${res.error}")
                            }
                            is PluginResult.NeedMoreInfo -> {
                                pendingResults.add(PendingResultInfo(plugin, intent.action, res))
                            }
                        }
                    }

                    val completedText = completedMsgs.joinToString("\n")

                    return if (pendingResults.isNotEmpty()) {
                        val (firstPlugin, firstActionName, firstPendingRes) = pendingResults.first()
                        val activePending = chatHistoryManager.getActivePendingIntents()
                            .firstOrNull { it.pluginId == firstPlugin.manifest.id && it.action == firstActionName }

                        val actionDesc = firstPlugin.manifest.actions.find { it.name == firstActionName }?.description ?: firstActionName
                        
                        val banner = if (activePending != null) {
                            val baseBanner = buildPendingBanner(activePending, firstPlugin.manifest.name, actionDesc)
                            if (pendingResults.size > 1) {
                                "$baseBanner\n*(Còn ${pendingResults.size - 1} yêu cầu khác đã được xếp hàng chờ)*"
                            } else {
                                baseBanner
                            }
                        } else {
                            firstPendingRes.question
                        }

                        val combinedMsg = if (completedText.isNotEmpty()) {
                            "$completedText\n\n$banner"
                        } else {
                            banner
                        }

                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(
                                DeviceCommandResult(firstPlugin.manifest.id, PluginResult.NeedMoreInfo(firstPendingRes.missingParams, combinedMsg, firstPendingRes.options))
                            ),
                            matchResult = matchResult
                        )
                    } else {
                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(
                                DeviceCommandResult("multi", PluginResult.Success(mapOf("message" to completedText)))
                            ),
                            matchResult = matchResult
                        )
                    }
                }
                is Layer3Result.NoMatch -> { }
            }

            if (!isPotentialCommand(resolvedMessage)) {
                logger.d("AgentKernel", "[$traceId] 🍃 [Tầng 4 Miss] Không chứa từ khóa chỉ định lệnh -> Bypass thẳng xuống Chat (bỏ qua Tầng 5)")
                return PipelineResult(
                    routerOutcome = RouterOutcome.NotACommand,
                    matchResult = matchResult
                )
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
                else -> "• Không khớp với mô tả action hoặc nhãn ví dẫn trong Manifest của các Plugin hoặc độ bao phủ mệnh đề chưa đạt 80%."
            }
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)",
                    tierNum = 4,
                    matched = layer4Result !is Layer3Result.NoMatch,
                    details = t4Details
                )
            )

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
            
            val matchedAliases = context.globalMatchResult.aliasMatches.filter { 
                val aliasNorm = StringSimilarityUtil.normalizeVietnamese(it.first.question)
                clauseNorm.contains(aliasNorm) 
            }
            
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

            val totalMatchedLength = matchedIntents.sumOf { it.question.length } + 
                                     matchedAliases.sumOf { it.first.question.length }
            
            if (clause.isEmpty()) continue
            val coverageRatio = totalMatchedLength.toDouble() / clause.length

            if (coverageRatio < 0.70) {
                logger.d("AgentKernel", "[$traceId] ⚠️ Tỷ lệ bao phủ mệnh đề '$clause' quá thấp (${String.format("%.2f", coverageRatio)} < 0.70). Bỏ qua Tầng 3.")
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
        val wrapperIntentPair = context.globalMatchResult.intentMatches
            .find { 
                try {
                    JSONObject(it.first.answer).optString("plugin") == "schedule"
                } catch (_: Exception) {
                    false
                }
            }

        val bestIntentPair = wrapperIntentPair ?: context.globalMatchResult.intentMatches
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
        val queryNormalized = StringSimilarityUtil.normalizeVietnamese(context.resolvedQuery)

        val hasScheduleSignal = context.localEntities.containsKey("cron") || 
            queryNormalized.contains("lich", ignoreCase = true) ||
            queryNormalized.contains("hen gio", ignoreCase = true) ||
            queryNormalized.contains("setup", ignoreCase = true)

        if (hasScheduleSignal) {
            val schedulePlugin = devicePlugins.find { it.manifest.id == "schedule" }
            val targetActionName = detectScheduleAction(queryNormalized)
            val targetAction = schedulePlugin?.manifest?.actions?.find { it.name == targetActionName }

            if (schedulePlugin != null && targetAction != null) {
                logger.d("AgentKernel", "🎯 [Tầng 4 Wrapper] Phát hiện tín hiệu lập lịch. Xác định action phù hợp: '$targetActionName'")
                
                val schemaParams = mutableMapOf<String, Any>()
                targetAction.parameters.forEach { param ->
                    schemaParams[param.name] = param.defaultValue ?: ""
                }
                
                val resolvedParams = resolveParametersWithMeta(
                    parameters = targetAction.parameters,
                    inputParams = schemaParams,
                    context = context,
                    excludeIntentId = null,
                    depth = 0
                )
                
                return Layer3Result.Single(schedulePlugin, Intent(schedulePlugin.manifest.id, targetAction.name, resolvedParams))
            }
        }

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

            if (coverageRatio < 0.70) {
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
            resolvedIntents.isEmpty() -> {
                // ✅ ĐẶC THÙ CHO GOAL: Nếu đang điều khiển riêng Quản gia ("housekeeper") và nói câu ra lệnh tự nhiên,
                // tự động fallback chuyển sang create_goal luôn mà không đẩy xuống Chat thường.
                val lockedId = chatHistoryManager.getLockedPlugin()
                if (lockedId == "housekeeper") {
                    val housekeeperPlugin = devicePlugins.find { it.manifest.id == "housekeeper" }
                    if (housekeeperPlugin != null) {
                        val createGoalAction = housekeeperPlugin.manifest.actions.find { it.name == "create_goal" }
                        if (createGoalAction != null) {
                            val resolvedParams = resolveParametersWithMeta(
                                parameters = createGoalAction.parameters,
                                inputParams = mapOf("goalText" to context.resolvedQuery),
                                context = context,
                                excludeIntentId = null,
                                depth = 0
                            )
                            return Layer3Result.Single(housekeeperPlugin, Intent("housekeeper", "create_goal", resolvedParams))
                        }
                    }
                }
                Layer3Result.NoMatch
            }
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

    private suspend fun resolveAliasOrFallback(
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

        // ✅ ĐẶC THÙ CHO GOAL TEXT: Tự động bơm nguyên văn câu lệnh (sau xử lý đại từ) vào tham số goalText
        if (param.name == "goalText" && (currentValue == null || isPlh)) {
            return context.resolvedQuery
        }

        return currentValue ?: ""
    }

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
    }

    private fun getQuestionForMissingParamDiagnostic(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null
    ): String {
        val actualKey = if (param.startsWith("params.")) param.removePrefix("params.") else param
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName }
        val paramMeta = targetAction?.parameters?.find { it.name == actualKey }
        val semanticType = paramMeta?.semanticType?.lowercase() ?: ""

        val isCronField = semanticType == "time" || actualKey == "cron" || actualKey == "time"
        if (isCronField) {
            return "Bạn muốn thiết lập hẹn giờ/lên lịch vào lúc mấy giờ, ngày nào? (Ví dụ: 8h sáng mai, hoặc mỗi ngày lúc 18h)"
        }
        
        val isIntervalField = semanticType == "interval" || actualKey == "interval" || actualKey == "intervalMinutes"
        if (isIntervalField) {
            return "Bạn muốn hoạt động này được lặp lại định kỳ sau mỗi bao nhiêu phút? (Ví dụ: mỗi 10 phút)"
        }

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

    private suspend fun getQuestionForMissingParam(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null
    ): Pair<String, Map<String, String>> {
        val actualKey = if (param.startsWith("params.")) param.removePrefix("params.") else param
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName }
        val paramMeta = targetAction?.parameters?.find { it.name == actualKey }
        val semanticType = paramMeta?.semanticType?.lowercase() ?: ""

        // ── Các trường có danh sách hữu hạn -> hỏi bằng số ──


      if (actualKey in setOf("camera", "camera_id", "cameraId")) {
            val cameras = database.cameraDao().getActiveCameras()
            if (cameras.isNotEmpty()) {
                return buildNumberedQuestion(
                    "Bạn muốn thao tác với camera nào?",
                    cameras.map { 
                        // Ưu tiên hiển thị: Vị trí (Mã camera), nếu trống vị trí thì hiển thị Mã camera
                        val displayName = if (!it.landinfo.isNullOrBlank()) {
                            "${it.landinfo} (${it.id})"
                        } else {
                            it.id
                        }
                        displayName to it.id 
                    }
                )
            }
        }


        if (actualKey in setOf("device", "device_id", "deviceId")) {
            val devices = database.tuyaDeviceDao().getAllDevices()
            if (devices.isNotEmpty()) {
                return buildNumberedQuestion("Bạn muốn điều khiển thiết bị nào?", devices.map { it.name to it.id })
            }
        }
        if (actualKey == "id" && plugin?.manifest?.id == "schedule") {
            val schedules = database.scheduleDao().getAllSchedules()
            if (schedules.isNotEmpty()) {
                return buildNumberedQuestion(
                    "Bạn muốn thao tác với lịch trình nào?",
                    schedules.map { "${it.pluginId}.${it.action} (${if (it.cron.isNotEmpty()) it.cron else "${it.intervalMinutes} phút"})" to it.id }
                )
            }
        }

        val isCronField = semanticType == "time" || actualKey == "cron" || actualKey == "time"
        if (isCronField) {
            return ("Bạn muốn thiết lập hẹn giờ/lên lịch vào lúc mấy giờ, ngày nào? (Ví dụ: 8h sáng mai, hoặc mỗi ngày lúc 18h)" to emptyMap())
        }
        
        val isIntervalField = semanticType == "interval" || actualKey == "interval" || actualKey == "intervalMinutes"
        if (isIntervalField) {
            return ("Bạn muốn hoạt động này được lặp lại định kỳ sau mỗi bao nhiêu phút? (Ví dụ: mỗi 10 phút)" to emptyMap())
        }

        if (paramMeta != null && paramMeta.description.isNotBlank()) {
            return ("Bạn vui lòng cung cấp thông tin cho ${paramMeta.description} nhé?" to emptyMap())
        }

        val text = when (actualKey) {
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId", "plugin_id"                    -> "Bạn muốn lên lịch cho chức năng nào?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$actualKey' nhé?"
        }
        return (text to emptyMap())
    }

    private fun buildNumberedQuestion(prompt: String, candidates: List<Pair<String, String>>): Pair<String, Map<String, String>> {
        val listText = candidates.mapIndexed { i, (label, _) -> "Số ${i + 1}. $label" }.joinToString("\n")
        val options = candidates.mapIndexed { i, (_, value) -> (i + 1).toString() to value }.toMap()
        return "$prompt\n$listText" to options
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
            val (question, options) = getQuestionForMissingParam(missing.first(), plugin, normalizedIntent.action)
            PluginResult.NeedMoreInfo(missing, question, options)
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
                    knownParams = normalizedIntent.params + mapOf(
                        "_noProgressCount" to 0,
                        "_options" to executionResult.options
                    ),
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
                (executionResult.data as? Map<*, *>? )?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(context.originalQuery, replyForHistory)

        return executionResult
    }








    // Trong AgentKernel.kt — Hàm tryResolvePendingIntent:

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
        logger.w("AgentKernel", "[$traceId] ⚠️ Pending bị lặp lại không có tiến triển -> xóa pending")
        if (mode == PipelineMode.EXECUTE) {
            chatHistoryManager.removePendingIntent(pending.pluginId, pending.action)
            val failedPending = pending.copy(
                knownParams = pending.knownParams + mapOf("_cancelReason" to "no_progress")
            )
            chatHistoryManager.addExpiredNotification(failedPending)
        }
        return null
    }

    val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.5f)
    val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, "default_user", aliasThreshold = aliasThreshold)

    val localEntities = mutableMapOf<String, Any>()
    EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
    DateTimeParser.parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
    DateTimeParser.parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

    val heuristicFilled = mutableMapOf<String, Any>()

    // ── ✅ BẢN SỬA ĐỔI: Giải mã số chọn lọc và ghép dồn cho Quản gia ──
    if (pending.pluginId == "housekeeper" && pending.action == "create_goal") {
        val oldGoalText = pending.knownParams["goalText"]?.toString() ?: ""
        if (oldGoalText.isNotBlank()) {
            val userReply = userMessage.trim()
            val asked = pending.askedQuestion
            var resolvedValue = userReply
            
            // Trích xuất số chỉ mục người dùng chọn (ví dụ: "1", "chọn 1")
            val numberRegex = Regex("\\b(\\d+)\\b")
            val match = numberRegex.find(userReply)
            if (match != null) {
                val num = match.groupValues[1]
                // Tìm dòng khớp dạng "Số 1. <tên>" hoặc "1. <tên>" trong câu hỏi trước
                val optionRegex = Regex("(?i)\\b(?:số\\s+)?$num\\.\\s*([^\\n]+)")
                val optionMatch = optionRegex.find(asked)
                if (optionMatch != null) {
                    resolvedValue = optionMatch.groupValues[1].trim()
                    // Nếu giá trị có chứa ID trong ngoặc đơn, bóc tách lấy ID
                    val bracketRegex = Regex("\\(([^)]+)\\)")
                    val bracketMatch = bracketRegex.find(resolvedValue)
                    if (bracketMatch != null) {
                        resolvedValue = bracketMatch.groupValues[1].trim()
                    }
                }
            }
            
            val combined = "$oldGoalText $resolvedValue"
            heuristicFilled["goalText"] = combined
            logger.d("AgentKernel", "[$traceId] 🤵 Giải mã số chọn lọc và ghép dồn vào goalText: '$combined'")
        }
    }
    // ───────────────────────────────────────────────────────────────

    


    
    // ─────────────────────────────────────────────────────────────

    for (param in pending.missingParams) {
        val trimmed = userMessage.trim()
        val isNested = param.startsWith("params.")
        val actualKey = if (isNested) param.removePrefix("params.") else param
        
        // ... (giữ nguyên toàn bộ logic vòng lặp for phía dưới của hàm tryResolvePendingIntent cũ)
          
          
          
          
          
          
          
          
          
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
                    val parsedCron = DateTimeParser.parseVietnameseTime(userMessage)
                    if (parsedCron != null) {
                        heuristicFilled[param] = parsedCron
                    }
                }
                "interval" -> {
                    val parsedInterval = DateTimeParser.parseVietnameseInterval(userMessage)
                    if (parsedInterval != null) {
                        heuristicFilled[param] = parsedInterval
                    } else {
                        val numericVal = userMessage.trim().toIntOrNull()
                        if (numericVal != null) {
                            heuristicFilled[param] = numericVal
                        }
                    }
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

        // ── Nhận diện câu trả lời theo chỉ mục số hiển thị ──
        @Suppress("UNCHECKED_CAST")
        val activeOptions = pending.knownParams["_options"] as? Map<String, String> ?: emptyMap()
        val askedParamNow = pending.missingParams.firstOrNull()

        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val norm = StringSimilarityUtil.normalizeVietnamese(userMessage.lowercase().trim())
            // Tìm số đứng riêng lẻ hoặc kèm các cụm tiền tố thông dụng như "so 1", "chon 1", "thu 1"
            val chosenNumber = Regex("\\b(?:so|chon|cau|thu|\\s+)?\\s*(\\d+)\\b").find(norm)?.groupValues?.get(1)
                ?: Regex("\\b(\\d+)\\b").find(norm)?.value
                ?: Regex("(?<!\\w)(\\d+)(?!\\w)").find(norm)?.value
            
            chosenNumber?.let { num ->
                activeOptions[num]?.let { resolvedValue ->
                    heuristicFilled[askedParamNow] = resolvedValue
                    logger.d("AgentKernel", "[$traceId] Người dùng chọn số $num -> \"$resolvedValue\" (bypass LLM)")
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
                val nested = normalizedMergedParams["params"] as? Map<String, Any>?
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
            val (question, options) = getQuestionForMissingParam(stillMissing.first(), targetPlugin, pending.action)

            chatHistoryManager.addPendingIntent(
                pending.copy(
                    knownParams = normalizedMergedParams + mapOf(
                        "_noProgressCount" to newNoProgressCount,
                        "_options" to options
                    ),
                    missingParams = stillMissing,
                    askedQuestion = question,
                    createdAt = System.currentTimeMillis()
                )
            )
            chatHistoryManager.addTurn(userMessage, question)
            return DeviceCommandResult(
                pluginId = targetPlugin.manifest.id,
                result = PluginResult.NeedMoreInfo(stillMissing, question, options)
            )
        }

        chatHistoryManager.removePendingIntent(pending.pluginId, pending.action)
        val executionResult = try {
            targetPlugin.execute(pending.action, normalizedMergedParams)
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success -> (executionResult.data as? Map<*, *>? )?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(userMessage, replyForHistory)

        return DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = executionResult)
    }

    private fun rankRelevantPlugins(queryNormalized: String, devicePlugins: List<Plugin>): List<Plugin> {
        val hasScheduleSignal = queryNormalized.contains("cron") || 
            queryNormalized.contains("lich") || 
            queryNormalized.contains("hen gio") || 
            queryNormalized.contains("dat lich") || 
            queryNormalized.contains("setup")

        val queryTokens = queryNormalized.split(" ", "\t", "\n").filter { it.length >= 2 }.toSet()
        if (queryTokens.isEmpty() && !hasScheduleSignal) return emptyList()

        val scoreByPluginId = mutableMapOf<String, Int>()

        if (hasScheduleSignal) {
            scoreByPluginId["schedule"] = 999
        }

        normalizedActionMetadataList.forEach { meta ->
            if (!meta.plugin.manifest.routable || !meta.action.enabled) return@forEach
            val haystack = (listOf(meta.normalizedDescription) + meta.normalizedExamples + meta.normalizedTags)
                .flatMap { it.split(" ", "\t", "\n") }
                .toSet()
            val overlap = queryTokens.count { token -> haystack.any { it.contains(token) || token.contains(it) } }
            if (overlap > 0) {
                val pluginId = meta.plugin.manifest.id
                scoreByPluginId[pluginId] = maxOf(scoreByPluginId[pluginId] ?: 0, overlap)
            }
        }

        return scoreByPluginId.entries
            .sortedByDescending { it.value }
            .take(MAX_FALLBACK_PLUGINS)
            .mapNotNull { entry -> devicePlugins.find { it.manifest.id == entry.key } }
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
            val relevantPlugins = rankRelevantPlugins(queryNormalized, devicePlugins)

            if (relevantPlugins.isNotEmpty()) {
                logger.d("AgentKernel", "[$traceId] 🎯 [Tầng 5 Fallback] Thu hẹp candidate về ${relevantPlugins.size} plugin liên quan: ${relevantPlugins.joinToString { it.manifest.id }}")
                actionCandidates
                    .filter { c -> relevantPlugins.any { it.manifest.id == c.pluginId } }
                    .joinToString("\n") { c ->
                        "  - ${c.pluginId}.${c.action}: ${c.description} (tham số: ${c.parameters.joinToString(",")})"
                    }
            } else {
                logger.d("AgentKernel", "[$traceId] ⚠️ [Tầng 5 Fallback] Không tìm được plugin liên quan cục bộ -> gửi toàn bộ danh sách")
                actionCandidates
                    .filter { c -> devicePlugins.any { it.manifest.id == c.pluginId } }
                    .joinToString("\n") { c ->
                        "  - ${c.pluginId}.${c.action}: ${c.description} (tham số: ${c.parameters.joinToString(",")})"
                    }
            }
        }

        val shortHistory = chatHistoryManager.getRecentTurnsAsText()
        val lastDevice = chatHistoryManager.lastMentionedDeviceId ?: "none"

        val activePendingInfo = chatHistoryManager.getActivePendingIntents().firstOrNull()?.let {
            "⚠️ Lệnh đang chờ hoàn thành: ${it.pluginId}.${it.action}, Các trường chưa điền: ${it.missingParams.joinToString()}"
        } ?: "Không có lệnh dở dang"

        val routerPrompt = buildString {
            append("<sys>Bạn là bộ định tuyến ý định (Intent Router) thông minh cho hệ thống Smarthome.\n")
            append("Nhiệm vụ: Phân tích câu nói của người dùng và chuyển đổi thành JSON thô chính xác: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("🚨 LƯU Ý QUAN TRỌNG: Các ứng viên có định dạng \"pluginId.actionName\" (ví dụ: \"housekeeper.create_goal\"). ")
            append("Khi gán, trường \"plugin\" phải là \"pluginId\" đứng trước dấu chấm (ví dụ: \"housekeeper\"), và \"action\" là \"actionName\" đứng sau dấu chấm (ví dụ: \"create_goal\"). Tuyệt đối không viết dồn cả cụm vào \"plugin\".\n\n")
            append("🚨 QUY TẮC CHỐNG GÁN LỆNH NHẦM (ANTI-TOOL-USE BIAS):\n")
            append("1. Chỉ định tuyến sang một ứng viên (candidate) bên dưới KHI VÀ CHỈ KHI người dùng đưa ra một YÊU CẦU HÀNH ĐỘNG RÕ RÀNG (ví dụ: bật, tắt, đóng, mở, quét, gửi email cụ thể, thiết lập lịch hẹn giờ thực tế, kiểm tra trạng thái thiết bị).\n")
            append("2. Nếu câu nói là CÂU HỎI THÔNG TIN, GIẢI THÍCH LÝ THUYẾT, ĐỊNH NGHĨA, CHÀO HỎI, TÁN GẪU (ví dụ: 'camera có bao nhiêu loại', 'email hoạt động thế nào', 'tại sao đèn không sáng', 'thời tiết thế nào'...): Bạn TUYỆT ĐỐI KHÔNG ĐƯỢC gán vào bất kỳ lệnh thiết bị nào, cho dù câu nói có chứa từ khóa 'camera', 'email' hay 'đèn'. Hãy xuất chính xác: {\"plugin\":\"chat\",\"action\":\"none\"}\n")
            append("3. Không tự ý suy diễn câu hỏi lý thuyết, câu hỏi khảo sát hoặc thắc mắc chung của người dùng thành một hành động điều khiển thực tế.\n")
            append("4. Tuyệt đối không giải thích thêm, chỉ xuất JSON thô.</sys>\n")
            
          
          append("<candidates>\n$candidateLines\n</candidates>\n")
            if (foundAliases.isNotEmpty()) append("<aliases>\n$foundAliases\n</aliases>\n")
            append("<context>last_device: \"$lastDevice\", pending_status: \"$activePendingInfo\"</context>\n")
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

        return RouterOutcome.Matched(DeviceCommandResult(targetPlugin.manifest.id, result))
    }

    private fun parseIntentResponse(response: String): Intent? {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(cleaned)

            var pluginId = json.getString("plugin")
            val action = json.getString("action")

            // ✅ SỬA LỖI AI GÁN NHẦM: Nếu LLM viết cả cụm "housekeeper.create_goal" vào ô "plugin"
            if (pluginId.contains(".") && action.isNotBlank()) {
                val parts = pluginId.split(".")
                if (parts.size == 2 && parts[1] == action) {
                    pluginId = parts[0]
                }
            }

            Intent(
                pluginId = pluginId,
                action = action,
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
                    "• Tách thành công câu lệnh đơn '${layer3Result.intent.pluginId}.${layer3Result.intent.action}'. Phát hiện thiếu thông tin: [${missing.joinToString { getQuestionForMissingParamDiagnostic(it, layer3Result.plugin, layer3Result.intent.action) }}]"
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

    // ✅ ĐÃ SỬA: Chuyển đổi sang lấy trạng thái khóa biệt lập theo từng username [1]
    fun getLockedPluginId(username: String = "default_user"): String? = chatHistoryManager.getLockedPlugin(username)

    fun getLockedPluginName(username: String = "default_user"): String? {
        val id = getLockedPluginId(username) ?: return null
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
        data class NeedMoreInfo(
            val missingParams: List<String>, 
            val question: String,
            val options: Map<String, String> = emptyMap()
        ) : PluginResult()
    }
}