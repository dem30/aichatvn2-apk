package com.aichatvn.agent.core.execution

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.DeviceCommandResult
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity // ✅ THÊM IMPORT
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.DateTimeParser
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

@Singleton
class PendingIntentResolver @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val intentExecutor: IntentExecutor,
    private val logger: Logger
) {

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
    }

    suspend fun tryResolvePendingIntent(
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
            logger.w("PendingIntentResolver", "[$traceId] ⚠️ Pending bị lặp lại không có tiến triển -> xóa pending")
            
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

        @Suppress("UNCHECKED_CAST")
        val activeOptions = pending.knownParams["_options"] as? Map<String, String> ?: emptyMap()
        val askedParamNow = pending.missingParams.firstOrNull()

        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val userNorm = StringSimilarityUtil.normalizeVietnamese(userMessage.lowercase().trim())
            if (userNorm.isNotBlank()) {
                val labelByIndex = Regex("Số (\\d+)\\.\\s*(.+)").findAll(pending.askedQuestion ?: "")
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val matchedIndex = labelByIndex.entries.firstOrNull { (_, label) ->
                    val labelNorm = StringSimilarityUtil.normalizeVietnamese(label.lowercase().replace(".", " "))
                    labelNorm.contains(userNorm) || userNorm.contains(labelNorm.substringBefore(" ("))
                }?.key
                matchedIndex?.let { idx ->
                    activeOptions[idx]?.let { resolvedValue ->
                        heuristicFilled[askedParamNow] = resolvedValue
                        logger.d("PendingIntentResolver", "[$traceId] Người dùng chọn theo tên \"$userNorm\" -> option #$idx -> \"$resolvedValue\" (bypass LLM)")
                    }
                }
            }
        }

        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val norm = StringSimilarityUtil.normalizeVietnamese(userMessage.lowercase().trim())
            val chosenNumber = Regex("\\b(?:so|chon|cau|thu|\\s+)?\\s*(\\d+)\\b").find(norm)?.groupValues?.get(1)
                ?: Regex("\\b(\\d+)\\b").find(norm)?.value
                ?: Regex("(?<!\\w)(\\d+)(?!\\w)").find(norm)?.value
            
            chosenNumber?.let { num ->
                activeOptions[num]?.let { resolvedValue ->
                    heuristicFilled[askedParamNow] = resolvedValue
                    logger.d("PendingIntentResolver", "[$traceId] Người dùng chọn số $num -> \"$resolvedValue\" (bypass LLM)")
                }
            }
        }

        val currentAskedParam = pending.missingParams.firstOrNull()
        
        val fill = if (currentAskedParam != null && heuristicFilled.containsKey(currentAskedParam)) {
            logger.d("PendingIntentResolver", "[$traceId] Heuristic tự xử lý thành công tham số '$currentAskedParam'. Bypass LLM.")
            heuristicFilled
        } else {
            if (mode == PipelineMode.DIAGNOSTIC) {
                logger.d("PendingIntentResolver", "[$traceId] 🔵 [DIAGNOSTIC] Chặn gọi Groq thực tế cho Tầng 1 dở dang.")
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                )
            }

            logger.d("PendingIntentResolver", "[$traceId] Heuristic không khớp tự động được '$currentAskedParam'. Gọi LLM.")
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
                val tPlugin = plugins.find { it.manifest.id == targetPluginId }
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
            val (question, options) = intentExecutor.getQuestionForMissingParam(stillMissing.first(), targetPlugin, pending.action)

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

    



    
}