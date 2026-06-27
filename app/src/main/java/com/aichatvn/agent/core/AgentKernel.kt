package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private val EMAIL_REGEX = Regex("[A-Za-0-9+_.-]+@[A-Za-0-9.-]+\\.[A-Za-z]{2,}")

data class LocalCandidate(
    val pluginId: String,
    val action: String,
    val description: String,
    val parameters: List<String>
)

data class Tier2Result(
    val plugin: Plugin,
    val intent: AgentKernel.Intent,
    val confidence: Double
)

@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) {

    companion object {
        private val SPACE_REGEX = Regex("\\s+")
        private const val MAX_DEPTH = 3
    }

    private val actionCandidates: List<LocalCandidate> by lazy {
        plugins.flatMap { plugin ->
            plugin.getActions().map { act ->
                LocalCandidate(
                    pluginId = plugin.id,
                    action = act.name,
                    description = act.description,
                    parameters = act.parameters.map { if (it.required) it.name else "${it.name}?" }
                )
            }
        }
    }

    private val pluginActionList: List<Pair<Plugin, PluginAction>> by lazy {
        plugins.flatMap { plugin ->
            plugin.getActions().map { action -> plugin to action }
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
        depth: Int
    ) -> Any?> = mapOf(
        "time" to { _, currentValue, _, _, localEntities, _, _, _, _ ->
            localEntities["cron"] ?: currentValue ?: ""
        },
        "interval" to { _, currentValue, _, _, localEntities, _, _, _, _ ->
            localEntities["intervalMinutes"] ?: currentValue ?: 0
        },
        "plugin_id" to { _, currentValue, isPlh, _, _, secondaryIntent, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolvePluginIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "action_id" to { _, currentValue, isPlh, _, _, secondaryIntent, _, _, _ ->
            if (isPlh && secondaryIntent != null) resolveActionIdFromSecondary(secondaryIntent) else currentValue ?: ""
        },
        "params" to { param, currentValue, _, matchResult, _, secondaryIntent, devicePlugins, excludeId, depth ->
            resolveNestedParams(param, currentValue, matchResult, secondaryIntent, devicePlugins, excludeId, depth)
        }
    )

    private fun generateTraceId(): String = "TR-${System.currentTimeMillis() % 100000}-${(100..999).random()}"

    private fun isPlaceholder(value: Any?, parameter: PluginParameter?): Boolean {
        val strVal = value?.toString() ?: ""
        if (strVal.isBlank()) return true
        if (parameter != null && parameter.placeholder.isNotBlank() && strVal.equals(parameter.placeholder, ignoreCase = true)) {
            return true
        }
        val defaultPlaceholders = setOf(
            "device_1", "device_2", "camera_1", "camera_2",
            "example@gmail.com", "example@email.com",
            "schedule_1", "schedule_id_here"
        )
        return strVal in defaultPlaceholders
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.routable }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = generateTraceId()
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")

        val devicePlugins = plugins.filter { it.routable }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            logger.d("AgentKernel", "[$traceId] Phát hiện tiến trình dở dang Pending Intent")
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins, traceId)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_MIN_SCORE }
            ?.value?.toFloatOrNull() ?: 0.3f

        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, username, threshold = dynamicMinScore)

        val tier2Result = tryTier2SemanticSlotResolver(userMessage, matchResult, devicePlugins)
        if (tier2Result != null) {
            if (tier2Result.confidence >= 0.80) {
                val plugin = tier2Result.plugin
                val intent = tier2Result.intent
                logger.d("AgentKernel", "[$traceId] ✅ [Tier 2 Hit - Confidence: ${tier2Result.confidence}] Semantic Compose: ${intent.pluginId}.${intent.action}")
                return try {
                    val result = executeIntent(plugin, intent, userMessage, traceId)
                    RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result))
                } catch (e: Exception) {
                    logger.e("AgentKernel", "[$traceId] Tier 2 execute error: ${e.message}", e)
                    RouterOutcome.RouterFailed("Tier 2 execute failed: ${e.message}")
                }
            } else {
                logger.d("AgentKernel", "[$traceId] ⚠️ [Tier 2 Low Confidence: ${tier2Result.confidence}] -> Chuyển hướng xử lý sang Tier 2.5")
            }
        }

        val tier2_5Result = tryTier2_5ActionMetadataMatcher(userMessage, matchResult, devicePlugins)
        if (tier2_5Result != null) {
            val (plugin, intent) = tier2_5Result
            logger.d("AgentKernel", "[$traceId] ✅ [Tier 2.5 Hit] Action Metadata Matcher Compose: ${intent.pluginId}.${intent.action}")
            return try {
                val result = executeIntent(plugin, intent, userMessage, traceId)
                RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result))
            } catch (e: Exception) {
                logger.e("AgentKernel", "[$traceId] Tier 2.5 execute error: ${e.message}", e)
                RouterOutcome.RouterFailed("Tier 2.5 execute failed: ${e.message}")
            }
        }

        logger.d("AgentKernel", "[$traceId] 🔵 [Tier 2.5 Miss] -> Chuyển tiếp sang LLM Semantic Router")
        return executeTier3LlmRouting(userMessage, matchResult, devicePlugins, traceId)
    }

    private suspend fun tryTier2SemanticSlotResolver(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Tier2Result? {
        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_MIN_SCORE }
            ?.value?.toDoubleOrNull() ?: 0.3

        val bestIntentPair = matchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .firstOrNull() ?: return null

        val bestIntentQA = bestIntentPair.first
        val confidence = bestIntentPair.second

        val rootJson = try { JSONObject(bestIntentQA.answer) } catch (e: Exception) { return null }
        val rootPluginId = rootJson.optString("plugin")
        val rootActionName = rootJson.optString("action")
        
        if (rootPluginId.isBlank() || rootActionName.isBlank()) return null
        
        val rootParams = rootJson.optJSONObject("params")?.toMap() ?: emptyMap()

        val rootPlugin = devicePlugins.find { it.id == rootPluginId } ?: return null
        val rootAction = rootPlugin.getActions().find { it.name == rootActionName } ?: return null

        val resolvedParams = resolveParametersWithMeta(
            parameters = rootAction.parameters,
            inputParams = rootParams,
            matchResult = matchResult,
            userMessage = userMessage,
            devicePlugins = devicePlugins,
            excludeIntentId = bestIntentQA.id,
            depth = 0
        )

        return Tier2Result(rootPlugin, Intent(rootPluginId, rootActionName, resolvedParams), confidence)
    }

    private suspend fun tryTier2_5ActionMetadataMatcher(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Pair<Plugin, Intent>? {
        var bestScore = 0.0
        var bestMatch: Pair<Plugin, PluginAction>? = null
        
        val normalizedUserQuery = normalizeVietnamese(userMessage)

        pluginActionList.forEach { (plugin, action) ->
            if (!plugin.routable || !action.enabled) return@forEach

            val maxExampleScore = action.examples.maxOfOrNull { calculateLocalSimilarity(normalizedUserQuery, it) } ?: 0.0
            val maxAliasScore = action.aliases.maxOfOrNull { calculateLocalSimilarity(normalizedUserQuery, it) } ?: 0.0
            val maxTagScore = action.tags.maxOfOrNull { calculateLocalSimilarity(normalizedUserQuery, it) } ?: 0.0
            val descriptionScore = calculateLocalSimilarity(normalizedUserQuery, action.description)

            val actionBestScore = maxOf(maxExampleScore, maxAliasScore, maxTagScore, descriptionScore)
            if (actionBestScore > bestScore) {
                bestScore = actionBestScore
                bestMatch = plugin to action
            }
        }

        if (bestScore >= 0.82 && bestMatch != null) {
            val (plugin, action) = bestMatch!!

            val schemaParams = mutableMapOf<String, Any>()
            action.parameters.forEach { param ->
                schemaParams[param.name] = param.defaultValue ?: ""
            }

            val resolvedParams = resolveParametersWithMeta(
                parameters = action.parameters,
                inputParams = schemaParams,
                matchResult = matchResult,
                userMessage = userMessage,
                devicePlugins = devicePlugins,
                excludeIntentId = null,
                depth = 0
            )

            return plugin to Intent(plugin.id, action.name, resolvedParams)
        }

        return null
    }

    private fun calculateLocalSimilarity(clean1: String, s2: String): Double {
        if (clean1.isEmpty() || s2.isEmpty()) return 0.0
        val clean2 = normalizeVietnamese(s2)
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

        if (clean1.contains(clean2) || clean2.contains(clean1)) {
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
        EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
        parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
        parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

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
                    secondaryIntentQA, devicePlugins, excludeIntentId, depth
                ) ?: ""
            } else {
                resolved[param.name] = resolveAliasOrFallback(param, currentValue, isPlh, matchResult, localEntities)
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
        depth: Int
    ): Any {
        if (secondaryIntentQA == null) return currentValue ?: emptyMap<String, Any>()
        return try {
            val secJson = JSONObject(secondaryIntentQA.answer)
            val secPluginId = secJson.optString("plugin", "")
            val secActionName = secJson.optString("action", "")
            val secParams = secJson.optJSONObject("params")?.toMap() ?: emptyMap()

            val secPlugin = devicePlugins.find { it.id == secPluginId }
            val secAction = secPlugin?.getActions()?.find { it.name == secActionName }

            if (secAction != null) {
                resolveParametersWithMeta(
                    parameters = secAction.parameters,
                    inputParams = secParams,
                    matchResult = matchResult,
                    userMessage = "",
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
        localEntities: Map<String, Any>
    ): Any {
        if (!isPlh) return currentValue ?: ""
        
        val matchedAliasValue = aliasMatchesForType(matchResult, param.semanticType)
        if (matchedAliasValue != null) {
            return matchedAliasValue
        }

        if (param.semanticType == "email" && localEntities.containsKey("email")) {
            return localEntities["email"]!!
        }

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
        val action = plugin.getActions().find { it.name == actionName } ?: return missing

        action.parameters.filter { it.required }.forEach { param ->
            val value = params[param.name]

            if (param.semanticType == "params") {
                val nestedParams = value as? Map<*, *>
                val targetPluginId = params["pluginId"]?.toString() ?: ""
                val targetAction = params["action"]?.toString() ?: ""
                
                if (targetPluginId.isNotBlank() && targetAction.isNotBlank()) {
                    val tPlugin = plugins.find { it.id == targetPluginId }
                    val tAction = tPlugin?.getActions()?.find { it.name == targetAction }
                    
                    if (nestedParams == null) {
                        tAction?.parameters?.filter { it.required }?.forEach { subParam ->
                            missing.add("params.${subParam.name}")
                        }
                    } else {
                        tAction?.parameters?.filter { it.required }?.forEach { subParam ->
                            val subVal = nestedParams[subParam.name]
                            if (isPlaceholder(subVal, subParam)) {
                                missing.add("params.${subParam.name}")
                            }
                        }
                    }
                }
            } else {
                if (isPlaceholder(value, param)) {
                    missing.add(param.name)
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
        val targetAction = plugin?.getActions().orEmpty().find { it.name == actionName }
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
            "pluginId"                                 -> "Bạn muốn lên lịch cho chức năng nào?"
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
                logger.d("AgentKernel", "[$traceId] Execute Action Trực Tiếp: ${plugin.id}.${normalizedIntent.action}")
                plugin.execute(normalizedIntent.action, normalizedIntent.params)
            } catch (e: Exception) {
                logger.e("AgentKernel", "[$traceId] Execute error: ${e.message}", e)
                PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
            }
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = plugin.id,
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
        val targetPlugin = devicePlugins.find { it.id == pending.pluginId } ?: run {
            chatHistoryManager.clearPendingIntent()
            return null
        }
        val targetAction = targetPlugin.getActions().find { it.name == pending.action } ?: run {
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

        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, "default_user")

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
                val targetPluginId = pending.knownParams["pluginId"]?.toString() ?: ""
                val targetActionName = pending.knownParams["action"]?.toString() ?: ""
                val tPlugin = devicePlugins.find { it.id == targetPluginId }
                tPlugin?.getActions()?.find { it.name == targetActionName }?.parameters?.find { it.name == actualKey }
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
                                heuristicFilled[param] = trimmed
                            }
                        }
                    }
                }
            }
        }

        val filled = if (heuristicFilled.size >= pending.missingParams.size) {
            heuristicFilled
        } else {
            val fillPrompt = buildString {
                append("<system>Output ONLY raw JSON, NO explanation.\n")
                append("Determine if user's input fills the missing parameter:\n")
                append("{\"params\": {${pending.missingParams.joinToString(",") { "\"$it\": \"value\"" }}}}\n")
                append("If the user wants to cancel or starts another task, output: {\"unrelated\": true}</system>\n")
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

        val finalFilled = heuristicFilled + filled

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
            val madeProgress = normalizedMergedParams.size > pending.knownParams.size
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
                pluginId = pending.pluginId,
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

        return DeviceCommandResult(pluginId = targetPlugin.id, result = executionResult)
    }

    private suspend fun executeTier3LlmRouting(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>,
        traceId: String
    ): RouterOutcome {
        val candidateLines = actionCandidates.joinToString("\n") { c ->
            "  - ${c.pluginId}.${c.action}(${c.parameters.joinToString(",")})"
        }

        val qaFacts = matchResult.aliasMatches
            .take(5)
            .joinToString("\n") { "${it.first.question} = ${it.first.answer}" }

        val shortHistory = chatHistoryManager.getRecentTurnsAsText()
        val lastDevice = chatHistoryManager.lastMentionedDeviceId ?: "none"

        val routerPrompt = buildString {
            append("<sys>Intent Formatter. JSON only: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("- Map user input to an active candidate.\n")
            append("- If general talk, output: {\"plugin\":\"chat\",\"action\":\"none\"}\n")
            append("- Use mapped aliases for resolution.\n")
            append("- Output raw JSON. No explanation.</sys>\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            if (qaFacts.isNotEmpty()) append("<aliases>\n$qaFacts\n</aliases>\n")
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>$userMessage</input>\n")
            append("<output>")
        }

        val routerResultJson = try {
            withTimeout(15_000L) { groqClient.routeIntent(routerPrompt) }
        } catch (e: Exception) {
            return RouterOutcome.RouterFailed("Tier 3 timeout/error: ${e.message}")
        }

        val rawIntent = parseIntentResponse(routerResultJson) ?: return RouterOutcome.RouterFailed("Tier 3 parse error")
        if (rawIntent.pluginId == "chat") return RouterOutcome.NotACommand

        val targetPlugin = devicePlugins.find { it.id == rawIntent.pluginId } ?: return RouterOutcome.RouterFailed("Plugin not found")
        val targetAction = targetPlugin.getActions().find { it.name == rawIntent.action } ?: return RouterOutcome.RouterFailed("Action not found")
        
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

        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.id, result = result))
    }

    private fun normalizeParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String, 
        userMessage: String? = null
    ): Map<String, Any> {
        val action = plugin.getActions().find { it.name == actionName } ?: return params
        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val nested = value as Map<String, Any>
                val targetPluginId = params["pluginId"]?.toString() ?: ""
                val targetAction = params["action"]?.toString() ?: ""
                val targetPlugin = plugins.find { it.id == targetPluginId }
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

            paramMeta.normalize(rawValue) ?: value
        }
    }

    private fun parseBooleanSmart(value: Any?): Boolean? {
        if (value is Boolean) return value
        val str = value?.toString()?.trim()?.lowercase() ?: return null
        val trueWords = setOf("true", "mở", "bật", "yes", "on", "1", "kích hoạt", "enable")
        val falseWords = setOf("false", "tắt", "no", "off", "0", "dừng", "vô hiệu", "disable")
        return when (str) {
            in trueWords -> true
            in falseWords -> false
            else -> null
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
        val plugin = plugins.find { it.id == pluginId }
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