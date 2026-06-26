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
        private val PLACEHOLDER_VALUES = setOf(
            "device_1", "device_2", "camera_1", "camera_2",
            "example@gmail.com", "example@email.com",
            "schedule_1", "schedule_id_here"
        )
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.visibleInQuickBar }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val devicePlugins = plugins.filter { it.visibleInQuickBar }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 1: Xử lý Pending Intent (Lệnh dở dang)
        // ─────────────────────────────────────────────────────────────────
        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, username)

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 2: Semantic Slot Resolver (Lắp ráp động dựa theo Siêu dữ liệu)
        // ─────────────────────────────────────────────────────────────────
        val tier2Result = tryTier2SemanticSlotResolver(userMessage, matchResult, devicePlugins)
        if (tier2Result != null) {
            val (plugin, intent) = tier2Result
            logger.d("AgentKernel", "✅ Tier 2 Semantic Compose Hit: ${intent.pluginId}.${intent.action} | Params: ${intent.params}")
            return try {
                val result = executeIntent(plugin, intent, userMessage)
                RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result))
            } catch (e: Exception) {
                logger.e("AgentKernel", "Tier 2 execute error: ${e.message}", e)
                RouterOutcome.RouterFailed("Tier 2 execute failed: ${e.message}")
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 3: LLM Router (Dự phòng cho câu phức tạp ngoài phạm vi cục bộ)
        // ─────────────────────────────────────────────────────────────────
        logger.d("AgentKernel", "🔵 Tier 3: Tầng 2 Miss -> Chuyển tiếp sang LLM Semantic Router")
        return executeTier3LlmRouting(userMessage, matchResult, devicePlugins)
    }

    /**
     * Semantic Slot Resolver: Lắp ráp cục bộ 100% dựa vào thuộc tính semanticType của PluginParameter.
     */
    private fun tryTier2SemanticSlotResolver(
        userMessage: String,
        matchResult: TrainingSkill.MatchResult,
        devicePlugins: List<Plugin>
    ): Pair<Plugin, Intent>? {
        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_MIN_SCORE }
            ?.value?.toDoubleOrNull() ?: 0.3

        // Tìm Root Intent tốt nhất vượt ngưỡng
        val bestIntentQA = matchResult.intentMatches
            .filter { it.second >= dynamicMinScore }
            .map { it.first }
            .firstOrNull() ?: return null

        val rootJson = try { JSONObject(bestIntentQA.answer) } catch (e: Exception) { return null }
        val rootPluginId = rootJson.optString("plugin")
        val rootActionName = rootJson.optString("action")
        val rootParams = rootJson.optJSONObject("params")?.toMap() ?: emptyMap()

        val rootPlugin = devicePlugins.find { it.id == rootPluginId } ?: return null
        val rootAction = rootPlugin.getActions().find { it.name == rootActionName } ?: return null

        // Phân tích và liên kết tham số dựa hoàn toàn vào Spec tự khai báo của Action đó
        val resolvedParams = resolveParametersWithMeta(
            parameters = rootAction.parameters,
            inputParams = rootParams,
            matchResult = matchResult,
            userMessage = userMessage,
            devicePlugins = devicePlugins,
            excludeIntentId = bestIntentQA.id
        )

        return Pair(rootPlugin, Intent(rootPluginId, rootActionName, resolvedParams))
    }

    /**
     * Bộ phân tích & liên kết tham số động dựa hoàn toàn vào siêu dữ liệu (Metadata) của tham số.
     */
    private fun resolveParametersWithMeta(
        parameters: List<PluginParameter>,
        inputParams: Map<String, Any>,
        matchResult: TrainingSkill.MatchResult,
        userMessage: String,
        devicePlugins: List<Plugin>,
        excludeIntentId: String? = null
    ): Map<String, Any> {
        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
        parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
        parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

        // Tìm kiếm Secondary Intent khớp trong câu (loại trừ root intent) để hỗ trợ composite lồng nhau
        val secondaryIntentQA = matchResult.intentMatches
            .filter { it.first.type == "intent" }
            .map { it.first }
            .firstOrNull { it.id != excludeIntentId }

        val resolved = mutableMapOf<String, Any>()

        parameters.forEach { param ->
            val currentValue = inputParams[param.name]
            val strVal = currentValue?.toString() ?: ""
            val isPlaceholder = strVal.isBlank() || strVal in PLACEHOLDER_VALUES

            when (param.semanticType.lowercase()) {
                "time" -> {
                    resolved[param.name] = localEntities["cron"] ?: currentValue ?: ""
                }
                "interval" -> {
                    resolved[param.name] = localEntities["intervalMinutes"] ?: currentValue ?: 0
                }
                "plugin_id" -> {
                    if (isPlaceholder && secondaryIntentQA != null) {
                        try {
                            val secJson = JSONObject(secondaryIntentQA.answer)
                            resolved[param.name] = secJson.optString("plugin", "")
                        } catch (_: Exception) {}
                    } else {
                        resolved[param.name] = currentValue ?: ""
                    }
                }
                "action_id" -> {
                    if (isPlaceholder && secondaryIntentQA != null) {
                        try {
                            val secJson = JSONObject(secondaryIntentQA.answer)
                            resolved[param.name] = secJson.optString("action", "")
                        } catch (_: Exception) {}
                    } else {
                        resolved[param.name] = currentValue ?: ""
                    }
                }
                "params" -> {
                    // Composite parameter (Lồng ghép cấu trúc params con tự động)
                    if (secondaryIntentQA != null) {
                        try {
                            val secJson = JSONObject(secondaryIntentQA.answer)
                            val secPluginId = secJson.optString("plugin", "")
                            val secActionName = secJson.optString("action", "")
                            val secParams = secJson.optJSONObject("params")?.toMap() ?: emptyMap()

                            val secPlugin = devicePlugins.find { it.id == secPluginId }
                            val secAction = secPlugin?.getActions()?.find { it.name == secActionName }

                            if (secAction != null) {
                                // Đệ quy lắp ghép tham số lồng nhau
                                val resolvedNested = resolveParametersWithMeta(
                                    parameters = secAction.parameters,
                                    inputParams = secParams,
                                    matchResult = matchResult,
                                    userMessage = userMessage,
                                    devicePlugins = devicePlugins,
                                    excludeIntentId = excludeIntentId
                                )
                                resolved[param.name] = resolvedNested
                            } else {
                                resolved[param.name] = secParams
                            }
                        } catch (e: Exception) {
                            resolved[param.name] = currentValue ?: emptyMap<String, Any>()
                        }
                    } else {
                        resolved[param.name] = currentValue ?: emptyMap<String, Any>()
                    }
                }
                else -> {
                    // Liên kết Alias tự động dựa trên semanticType của PluginParameter và 'type' của Alias trong DB
                    if (isPlaceholder) {
                        val matchedAliasValue = aliasMatchesForType(matchResult.aliasMatches, param.semanticType, userMessage)
                        if (matchedAliasValue != null) {
                            resolved[param.name] = matchedAliasValue
                        } else {
                            // Dự phòng nạp Email thô từ regex
                            if (param.semanticType == "email" && localEntities.containsKey("email")) {
                                resolved[param.name] = localEntities["email"]!!
                            } else {
                                resolved[param.name] = currentValue ?: ""
                            }
                        }
                    } else {
                        resolved[param.name] = currentValue ?: ""
                    }
                }
            }
        }

        return resolved
    }

    private fun aliasMatchesForType(
        aliasMatches: List<Pair<QAEntity, Double>>,
        semanticType: String,
        userMessage: String
    ): String? {
        return aliasMatches
            .filter { it.first.type == semanticType && userMessage.contains(it.first.question, ignoreCase = true) }
            .map { it.first.answer }
            .firstOrNull()
    }

    private fun parseVietnameseTime(message: String): String? {
        val lower = message.lowercase()
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
            val strVal = value?.toString() ?: ""

            if (param.semanticType == "params") {
                // Kiểm tra trạng thái nạp của các tham số lồng ghép (nested parameters)
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
                            val subVal = nestedParams[subParam.name]?.toString() ?: ""
                            val isSubPlaceholder = subVal.isBlank() || subVal in PLACEHOLDER_VALUES
                            if (isSubPlaceholder) {
                                missing.add("params.${subParam.name}")
                            }
                        }
                    }
                }
            } else {
                val isPlaceholder = strVal.isBlank() || strVal in PLACEHOLDER_VALUES
                if (isPlaceholder) {
                    missing.add(param.name)
                }
            }
        }
        return missing.distinct()
    }

    private fun getQuestionForMissingParam(param: String): String {
        return when (param) {
            "device", "device_id", "deviceId"          -> "Bạn muốn điều khiển thiết bị nào?"
            "camera", "camera_id", "cameraId"          -> "Bạn muốn xem camera nào?"
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId"                                 -> "Bạn muốn lên lịch cho chức năng nào (ví dụ: email, thông báo, thiết bị)?"
            "params.to", "params.email",
            "params.recipient"                         -> "Email nhận trong lịch định kỳ là gì?"
            "params.subject"                           -> "Tiêu đề email trong lịch định kỳ là gì?"
            "params.body"                              -> "Nội dung email trong lịch định kỳ bạn muốn gửi gì?"
            "params.device", "params.device_id",
            "params.deviceId"                          -> "Thiết bị cần điều khiển trong lịch là gì?"
            "params.camera", "params.camera_id",
            "params.cameraId"                          -> "Camera cần giám sát trong lịch là gì?"
            "params.title"                             -> "Tiêu đề thông báo trong lịch là gì?"
            "params.message"                           -> "Nội dung thông báo trong lịch bạn muốn gửi gì?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$param' nhé?"
        }
    }

    private suspend fun executeIntent(
        plugin: Plugin,
        intent: Intent,
        userMessage: String
    ): PluginResult {
        val normalizedParams = normalizeParams(intent.params, plugin, intent.action, userMessage)
        val normalizedIntent = intent.copy(params = normalizedParams)

        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"] ?: normalizedIntent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val missing = getUnresolvedParams(normalizedIntent.params, plugin, normalizedIntent.action)

        val executionResult = if (missing.isNotEmpty()) {
            val question = getQuestionForMissingParam(missing.first())
            PluginResult.NeedMoreInfo(missing, question)
        } else {
            try {
                plugin.execute(normalizedIntent.action, normalizedIntent.params)
            } catch (e: Exception) {
                logger.e("AgentKernel", "Execute error: ${e.message}", e)
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
                    askedQuestion = executionResult.question
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
        devicePlugins: List<Plugin>
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
            logger.w("AgentKernel", "⚠️ Pending bị lặp lại không có tiến triển -> clear pending")
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

        // Tìm kiếm cả Intent và Alias trên câu trả lời của người dùng (LEGO style)
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

            // Lấy Metadata tương ứng của tham số cần điền (để biết vai trò ngữ nghĩa của nó)
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
                    // Người dùng bổ sung tên Plugin định hướng -> tìm trực tiếp từ Intent phụ khớp được
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
                    // Slot-Filling theo đúng kiểu Semantic của Alias
                    val matchedAliasVal = matchResult.aliasMatches
                        .filter { it.first.type == paramMeta.semanticType && trimmed.contains(it.first.question, ignoreCase = true) }
                        .map { it.first.answer }
                        .firstOrNull()

                    if (matchedAliasVal != null) {
                        heuristicFilled[param] = matchedAliasVal
                    } else {
                        // Dự phòng email thô
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
            val question = getQuestionForMissingParam(stillMissing.first())

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
        devicePlugins: List<Plugin>
    ): RouterOutcome {
        val candidates = mutableListOf<LocalCandidate>()
        devicePlugins.forEach { plugin ->
            plugin.getActions().forEach { act ->
                candidates.add(
                    LocalCandidate(
                        pluginId = plugin.id,
                        action = act.name,
                        description = act.description,
                        parameters = act.parameters.map { if (it.required) it.name else "${it.name}?" }
                    )
                )
            }
        }

        val candidateLines = candidates.joinToString("\n") { c ->
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
            devicePlugins = devicePlugins
        )
        
        val intent = rawIntent.copy(params = finalParams)
        val result = executeIntent(targetPlugin, intent, userMessage)

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

            val paramMeta = action.parameters.find { it.name == key }
            if (paramMeta != null && paramMeta.type.lowercase() == "boolean") {
                val rawValue = if ((value.toString().isBlank() || value.toString() == "null") && userMessage != null) {
                    extractBooleanFromMessage(userMessage) ?: value
                } else {
                    value
                }
                parseBooleanSmart(rawValue) ?: rawValue
            } else {
                value
            }
        }
    }

    private fun parseBooleanSmart(value: Any?): Boolean? {
        if (value is Boolean) return value
        val str = value?.toString()?.trim()?.lowercase() ?: return null
        val trueWords = setOf("true", "mở", "bật", "yes", "on", "1", "kích hoạt")
        val falseWords = setOf("false", "tắt", "no", "off", "0", "dừng")
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