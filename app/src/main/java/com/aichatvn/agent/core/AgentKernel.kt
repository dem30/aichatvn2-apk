package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

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
    private val logger: Logger
) {

    companion object {
        private val SPACE_REGEX = Regex("\\s+")
        private const val MAX_DEPTH = 3
    }

    private val actionCandidates: List<LocalCandidate> by lazy {
        plugins.flatMap { plugin ->
            // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
            plugin.manifest.actions.map { act ->
                LocalCandidate(
                    pluginId = plugin.manifest.id, // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                    action = act.name,
                    description = act.description,
                    parameters = act.parameters.map { if (it.required) it.name else "${it.name}?" }
                )
            }
        }
    }

    private val normalizedActionMetadataList: List<NormalizedActionMetadata> by lazy {
        plugins.flatMap { plugin ->
            // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
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

    // ✅ ĐÃ SỬA: Lọc danh sách plugin UI từ manifest
    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.manifest.routable }

    // ─── [CẬP NHẬT GIAI ĐOẠN 5] HÀM ĐIỀU PHỐI CHAT KHÔNG HARDCODE CHUỖI ──────────────────────
    suspend fun chat(request: ChatRequest): ChatResponse {
        val message = request.message
        val username = request.username
        val extraContext = request.extraContext
        val imageBase64 = request.imageBase64
        val fileUrl = request.fileUrl
        
        // 1. DUYỆT TIỀN TỐ KÍCH HOẠT ĐỘNG (Regex/Rule-based routing)
        // Quét tất cả các Action của mọi Plugin đang cài trong hệ thống để tìm tiền tố trùng khớp
        for (plugin in plugins) {
            // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
            for (action in plugin.manifest.actions) {
                if (!action.enabled) continue
                val matchedPrefix = action.triggerPrefixes.find { prefix ->
                    message.startsWith(prefix, ignoreCase = true)
                }
                if (matchedPrefix != null) {
                    val result = executePluginAction(
                        pluginId = plugin.manifest.id, // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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
                    return ChatResponse(responseText, action.name, plugin.manifest.id) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                }
            }
        }
        
        // 2. DUYỆT NĂNG LỰC THỊ GIÁC ĐỘNG (Capability-based routing)
        // Khi có ảnh đính kèm, tìm plugin có thuộc tính supportsVision = true thay vì gọi cứng id = "vision"
        if (!imageBase64.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
            // ✅ ĐÃ SỬA: Đọc năng lực động từ manifest capabilities
            val visionPlugin = plugins.find { it.manifest.capabilities.vision }
            if (visionPlugin != null) {
                // ✅ ĐÃ SỬA: Lấy hành động đầu tiên từ manifest actions
                val actionName = visionPlugin.manifest.actions.firstOrNull()?.name ?: "analyze"
                val visionResult = executePluginAction(
                    pluginId = visionPlugin.manifest.id, // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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
                return ChatResponse(responseText, actionName, visionPlugin.manifest.id) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            } else {
                return ChatResponse("⚠️ Thiết bị hiện không hỗ trợ phân tích hình ảnh (chưa cài đặt Vision Plugin).", "vision_error", null)
            }
        }
        
        // 3. Nhánh Điều khiển thiết bị (Local Device Command Router)
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
        
        // 4. Nhánh Fallback tự nhiên (Combined / QA / Groq)
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

        // ✅ ĐÃ SỬA: Lọc danh sách thiết bị từ manifest
        val devicePlugins = plugins.filter { it.manifest.routable }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            logger.d("AgentKernel", "[$traceId] [Tầng 1] Phát hiện tiến trình dở dang Pending Intent")
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins, traceId)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD }
            ?.value?.toFloatOrNull() ?: 0.3f

        val matchResult = trainingSkill.fuzzyMatchCategorized(
            userMessage, 
            username, 
            intentThreshold = dynamicMinScore,
            aliasThreshold = 0.0f
        )

        val tier2HighConf = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE }
            ?.value?.toDoubleOrNull() ?: 0.80

        val layer2Result = tryTier2SemanticSlotResolver(userMessage, matchResult, devicePlugins)
        if (layer2Result != null) {
            if (layer2Result.confidence >= tier2HighConf) {
                val plugin = layer2Result.plugin
                val intent = layer2Result.intent
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 2 Hit - Confidence: ${layer2Result.confidence}] ${intent.pluginId}.${intent.action}")
                return try {
                    val result = executeIntent(plugin, intent, userMessage, traceId)
                    RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result))
                } catch (e: Exception) {
                    logger.e("AgentKernel", "[$traceId] Tầng 2 execute error: ${e.message}", e)
                    RouterOutcome.RouterFailed("Tầng 2 execute failed: ${e.message}")
                }
            } else {
                logger.d("AgentKernel", "[$traceId] ⚠️ [Tầng 2 Low Confidence: ${layer2Result.confidence}] -> Chuyển sang Tầng 3")
            }
        }

        val layer3Result = processLayer3ClauseEntitySpotter(userMessage, username, devicePlugins, traceId)
        when (layer3Result) {
            is Layer3Result.Single -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Single] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                val result = executeIntent(layer3Result.plugin, layer3Result.intent, userMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer3Result.plugin.manifest.id, result)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            }
            is Layer3Result.Nested -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Nested] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                val result = executeIntent(layer3Result.wrapper, layer3Result.intent, userMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer3Result.wrapper.manifest.id, result)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            }
            is Layer3Result.Multi -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 3 Multi] ${layer3Result.intents.size} actions")
                val results = mutableListOf<String>()
                layer3Result.intents.forEach { (plugin, intent) ->
                    try {
                        val r = executeIntent(plugin, intent, userMessage, traceId)
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

        val layer4Result = tryTier2_5ActionMetadataMatcher(userMessage, matchResult, devicePlugins)
        when (layer4Result) {
            is Layer3Result.Single -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Single] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                val result = executeIntent(layer4Result.plugin, layer4Result.intent, userMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer4Result.plugin.manifest.id, result)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            }
            is Layer3Result.Nested -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Nested] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                val result = executeIntent(layer4Result.wrapper, layer4Result.intent, userMessage, traceId)
                return RouterOutcome.Matched(DeviceCommandResult(layer4Result.wrapper.manifest.id, result)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            }
            is Layer3Result.Multi -> {
                logger.d("AgentKernel", "[$traceId] ✅ [Tầng 4 Multi] ${layer4Result.intents.size} actions")
                val results = mutableListOf<String>()
                layer4Result.intents.forEach { (plugin, intent) ->
                    try {
                        val r = executeIntent(plugin, intent, userMessage, traceId)
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
        return executeTier3LlmRouting(userMessage, matchResult, devicePlugins, traceId)
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

        for (clause in clauses) {
            val clauseNorm = normalizeVietnamese(clause)
            var matchedIntentQA: QAEntity? = null
            
            for (qa in intentQAs) {
                val qaNorm = normalizeVietnamese(qa.question)
                if (clauseNorm.contains(qaNorm)) {
                    matchedIntentQA = qa
                    break
                }
            }

            if (matchedIntentQA != null) {
                val rootJson = try { JSONObject(matchedIntentQA.answer) } catch (e: Exception) { null }
                val rootPluginId = rootJson?.optString("plugin") ?: ""
                val rootActionName = rootJson?.optString("action") ?: ""
                
                if (rootPluginId == "schedule") {
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        
                        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, username, aliasThreshold = 0.0f)

                        val resolvedParams = resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            matchResult = matchResult,
                            userMessage = userMessage,
                            devicePlugins = devicePlugins,
                            excludeIntentId = matchedIntentQA.id,
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
        }

        for (clause in clauses) {
            val clauseNorm = normalizeVietnamese(clause)
            var matchedIntentQA: QAEntity? = null
            
            for (qa in intentQAs) {
                val qaNorm = normalizeVietnamese(qa.question)
                if (clauseNorm.contains(qaNorm)) {
                    matchedIntentQA = qa
                    break
                }
            }

            if (matchedIntentQA != null) {
                val rootJson = try { JSONObject(matchedIntentQA.answer) } catch (e: Exception) { null }
                val rootPluginId = rootJson?.optString("plugin") ?: ""
                val rootActionName = rootJson?.optString("action") ?: ""
                
                if (rootPluginId != "schedule") {
                    if (absorbedActions.contains("$rootPluginId.$rootActionName")) continue

                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val matchResult = trainingSkill.fuzzyMatchCategorized(clause, username, aliasThreshold = 0.0f)

                        val resolvedParams = resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            matchResult = matchResult,
                            userMessage = clause,
                            devicePlugins = devicePlugins,
                            excludeIntentId = matchedIntentQA.id,
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

    private suspend fun tryTier2SemanticSlotResolver(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Layer2Result? {
        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD }
            ?.value?.toDoubleOrNull() ?: 0.3

        val bestIntentPair = matchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .firstOrNull() ?: return null

        val bestIntentQA = bestIntentPair.first
        val confidence = bestIntentPair.second

        val rootJson = try { JSONObject(bestIntentQA.answer) } catch (e: Exception) { null }
        val rootPluginId = rootJson?.optString("plugin") ?: ""
        val rootActionName = rootJson?.optString("action") ?: ""
        
        if (rootPluginId.isBlank() || rootActionName.isBlank()) return null
        
        val rootPlugin = devicePlugins.find { it.manifest.id == rootPluginId } ?: return null // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
        val rootAction = rootPlugin.manifest.actions.find { it.name == rootActionName } ?: return null // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest

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

        for (clause in clauses) {
            val clauseNormalized = normalizeVietnamese(clause)
            var bestMatch: NormalizedActionMetadata? = null
            var bestMatchLen = 0

            for (meta in normalizedActionMetadataList) {
                // ✅ ĐÃ SỬA: Đọc routable của plugin từ manifest
                if (!meta.plugin.manifest.routable || !meta.action.enabled) continue

                val isMatched = meta.normalizedDescription.contains(clauseNormalized) ||
                                clauseNormalized.contains(meta.normalizedDescription) ||
                                meta.normalizedExamples.any { ex ->
                                    ex.length >= 5 && (clauseNormalized.contains(ex) || ex.contains(clauseNormalized))
                                }

                if (isMatched) {
                    val matchLen = meta.action.description.length
                    if (matchLen > bestMatchLen) {
                        bestMatchLen = matchLen
                        bestMatch = meta
                    }
                }
            }

            // ✅ ĐÃ SỬA: Đọc pluginId từ manifest
            if (bestMatch != null && bestMatch.plugin.manifest.id == "schedule") {
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

                resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest

                val nestedPluginId = resolvedParams["pluginId"]?.toString() ?: ""
                val nestedAction = resolvedParams["action"]?.toString() ?: ""
                if (nestedPluginId.isNotBlank() && nestedAction.isNotBlank()) {
                    absorbedActions.add("$nestedPluginId.$nestedAction")
                }
            }
        }

        for (clause in clauses) {
            val clauseNormalized = normalizeVietnamese(clause)
            var bestMatch: NormalizedActionMetadata? = null
            var bestMatchLen = 0

            for (meta in normalizedActionMetadataList) {
                // ✅ ĐÃ SỬA: Đọc cấu hình từ manifest
                if (!meta.plugin.manifest.routable || !meta.action.enabled) continue

                val isMatched = meta.normalizedDescription.contains(clauseNormalized) || 
                                clauseNormalized.contains(meta.normalizedDescription) ||
                                meta.normalizedExamples.any { ex -> 
                                    ex.length >= 5 && (clauseNormalized.contains(ex) || ex.contains(clauseNormalized)) 
                                }

                if (isMatched) {
                    val matchLen = meta.action.description.length
                    if (matchLen > bestMatchLen) {
                        bestMatchLen = matchLen
                        bestMatch = meta
                    }
                }
            }

            // ✅ ĐÃ SỬA: Đọc pluginId từ manifest
            if (bestMatch != null && bestMatch.plugin.manifest.id != "schedule") {
                val plugin = bestMatch.plugin
                val action = bestMatch.action

                if (absorbedActions.contains("${plugin.manifest.id}.${action.name}")) continue // ✅ ĐÃ SỬA: Lấy pluginId từ manifest

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

                resolvedIntents.add(plugin to Intent(plugin.manifest.id, action.name, resolvedParams)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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

        val levDistance = levenshteinDistance(clean1, clean2)
        val levSim = 1.0 - (levDistance.toDouble() / maxLen)

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

            val secPlugin = devicePlugins.find { it.manifest.id == secPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            val secAction = secPlugin?.manifest?.actions?.find { it.name == secActionName } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest

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
        
        if (lower.contains("mỗi ngày") || lower.contains("hằng ngày") || lower.contains("hàng ngày")) {
            return "0 0 * * *"
        }
        if (lower.contains("hằng tuần") || lower.contains("hàng tuần")) {
            return "0 0 * * 0"
        }
        if (lower.contains("ngày mai") || lower.contains("mai")) {
            return "0 8 * * *"
        }
        if (lower.contains("thứ hai") || lower.contains("thứ 2")) {
            return "0 0 * * 1"
        }

        val hourRegex = Regex("(\\d+)\\s*(giờ|g|h)\\s*(sáng|chiều|tối|đêm)?")
        val match = hourRegex.find(lower)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val period = match.groupValues[3]
            if ((period == "chiều" || period == "tối") && hour < 12) {
                hour += 12
            } else if (period == "đêm" && hour == 12) {
                hour = 0
            }
            return "0 $hour * * *"
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
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return missing // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest

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
                val tPlugin = plugins.find { it.manifest.id == targetPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                val tAction = tPlugin?.manifest?.actions?.find { it.name == targetAction } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
                
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
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
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
                logger.d("AgentKernel", "[$traceId] Execute Action Trực Tiếp: ${plugin.manifest.id}.${normalizedIntent.action}") // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                plugin.execute(normalizedIntent.action, normalizedIntent.params)
            } catch (e: Exception) {
                logger.e("AgentKernel", "[$traceId] Execute error: ${e.message}", e)
                PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
            }
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = plugin.manifest.id, // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                    action = normalizedIntent.action,
                    knownParams = normalizedIntent.params + mapOf("_noProgressCount" to 0),
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question,
                    createdAt = System.currentTimeMillis()
                )
            )
            else -> chatHistoryManager.clearPendingIntent()
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
        val targetPlugin = devicePlugins.find { it.manifest.id == pending.pluginId } ?: run { // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            chatHistoryManager.clearPendingIntent()
            return null
        }
        val targetAction = targetPlugin.manifest.actions.find { it.name == pending.action } ?: run { // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
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
                val tPlugin = devicePlugins.find { it.manifest.id == targetPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                tPlugin?.manifest?.actions?.find { it.name == targetActionName }?.parameters?.find { it.name == actualKey } // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
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

        val filled = if (heuristicFilled.size >= pending.missingParams.size) {
            heuristicFilled
        } else {
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

            chatHistoryManager.setPendingIntent(
                pending.copy(
                    knownParams = normalizedMergedParams + mapOf("_noProgressCount" to newNoProgressCount),
                    missingParams = stillMissing,
                    askedQuestion = question,
                    createdAt = System.currentTimeMillis()
                )
            )
            chatHistoryManager.addTurn(userMessage, question)
            return DeviceCommandResult(
                pluginId = targetPlugin.manifest.id, // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
                result = PluginResult.NeedMoreInfo(stillMissing, question)
            )
        }

        chatHistoryManager.clearPendingIntent()
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

        return DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = executionResult) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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
            meta.plugin.manifest.routable && meta.action.enabled && ( // ✅ ĐÃ SỬA: Lọc trạng thái từ manifest
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
                "  - ${meta.plugin.manifest.id}.${meta.action.name}: ${meta.action.description}. Cấu trúc tham số: [$paramsInfo]" // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
            }
        } else {
            actionCandidates
                .filter { c -> devicePlugins.any { it.manifest.id == c.pluginId } } // ✅ ĐÃ SỬA: Lọc thiết bị từ manifest
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

        val targetPlugin = devicePlugins.find { it.manifest.id == rawIntent.pluginId } ?: return RouterOutcome.RouterFailed("Không tìm thấy Plugin") // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
        val targetAction = targetPlugin.manifest.actions.find { it.name == rawIntent.action } ?: return RouterOutcome.RouterFailed("Không tìm thấy Action") // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
        
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

        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = result)) // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
    }

    private fun normalizeParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String, 
        userMessage: String? = null
    ): Map<String, Any> {
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return params // ✅ ĐÃ SỬA: Đọc danh sách hành động từ manifest
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
                val targetPlugin = plugins.find { it.manifest.id == targetPluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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
        val plugin = plugins.find { it.manifest.id == pluginId } // ✅ ĐÃ SỬA: Lấy pluginId từ manifest
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
}