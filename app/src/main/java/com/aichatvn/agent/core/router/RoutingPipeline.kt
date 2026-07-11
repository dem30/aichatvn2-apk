package com.aichatvn.agent.core.router

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.DeviceCommandResult
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.AgentKernel.RouterOutcome
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.execution.IntentExecutor
import com.aichatvn.agent.core.execution.PendingIntentResolver
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase // ✅ THÊM IMPORT
import com.aichatvn.agent.data.model.QAEntity // ✅ THÊM IMPORT
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.DateTimeParser
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

@Singleton
class RoutingPipeline @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val configProvider: AppConfigProvider,
    private val chatHistoryManager: ChatHistoryManager,
    private val dialogManager: DialogManager,
    private val trainingSkill: TrainingSkill,
    private val intentExecutor: IntentExecutor,
    private val pendingIntentResolver: PendingIntentResolver,
    private val groqClient: GroqClientTool,
    private val database: AppDatabase,
    private val logger: Logger
) {
    companion object {
        private const val MAX_DEPTH = 3
        private const val MAX_FALLBACK_PLUGINS = 3

        private val COMMAND_TRIGGER_KEYWORDS = setOf(
            "bat", "tat", "mo", "dong", "chay", "quet", "gui", "len lich", "dat lich", "kiem tra", "setup",
            "den", "quat", "camera", "bom", "khoa", "thiet bi", "tuya", "canh bao", "email", "thu", "status",
            "huy", "xoa", "sua", "doi", "them", "xem", "hen gio", "bao thuc"
        )
    }

    private fun isPotentialCommand(message: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(message.lowercase().trim())
        return COMMAND_TRIGGER_KEYWORDS.any { keyword -> norm.contains(keyword) }
    }

    private fun detectScheduleAction(queryNormalized: String): String {
        val toggleOffVerbs = setOf("tat lich", "vo hieu hoa", "ngung kich hoat", "tat hen gio")
        val toggleOnVerbs = setOf("bat lich", "kich hoat", "bat hen gio")
        val deleteVerbs = setOf("huy", "xoa", "bo lich", "dung lich")
        val listVerbs = setOf("xem", "liet ke", "danh sach", "kiem tra", "hien thi")
        val updateVerbs = setOf("sua", "doi", "cap nhat", "thay doi", "dat lai", "chinh sua")

        return when {
            toggleOffVerbs.any { queryNormalized.contains(it) } -> "toggle"
            toggleOnVerbs.any { queryNormalized.contains(it) } -> "toggle"
            deleteVerbs.any { queryNormalized.contains(it) } -> "delete"
            listVerbs.any { queryNormalized.contains(it) } -> "list"
            updateVerbs.any { queryNormalized.contains(it) } -> "update"
            else -> "add"
        }
    }

    fun buildPendingBanner(pending: PendingIntent, pluginName: String, actionDesc: String): String {
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
                val tPlugin = devicePlugins().find { it.manifest.id == targetPluginId }
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

    private fun devicePlugins(): List<Plugin> = plugins.filter { it.manifest.routable }

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
        },
        "schedule_ref" to { _, currentValue, isPlh, context, _, _, _, _ ->
            if (isPlh) {
                resolveScheduleReference(context.resolvedQuery) ?: ""
            } else {
                resolveScheduleReference(currentValue.toString().trim()) ?: currentValue ?: ""
            }
        }
    )

    private fun resolvePluginIdFromSecondary(secondaryIntent: QAEntity): String {
        return try { JSONObject(secondaryIntent.answer).optString("plugin", "") } catch (_: Exception) { "" }
    }

    private fun resolveActionIdFromSecondary(secondaryIntent: QAEntity): String {
        return try { JSONObject(secondaryIntent.answer).optString("action", "") } catch (_: Exception) { "" }
    }

    private suspend fun resolveScheduleReference(raw: String): String? {
        if (raw.isBlank()) return null

        val allSchedules = withContext(Dispatchers.IO) {
            database.scheduleDao().getAllSchedules()
        }.sortedBy { it.createdAt }

        val normalizedRaw = StringSimilarityUtil.normalizeVietnamese(raw.lowercase().trim())

        val numberPattern = Regex("\\b(?:so|chon|cau|thu|lich|\\s+)?\\s*(\\d+)\\b")
        val matchResult = numberPattern.find(normalizedRaw)
        val extractedNumberStr = matchResult?.groupValues?.get(1) 
            ?: Regex("\\b(\\d+)\\b").find(normalizedRaw)?.value
            
        extractedNumberStr?.toIntOrNull()?.let { orderNumber ->
            allSchedules.getOrNull(orderNumber - 1)?.let { return it.id }
        }

        val bestMatchPair = allSchedules
            .filter { it.label.isNotBlank() }
            .map { schedule ->
                val score = StringSimilarityUtil.calculateLocalSimilarity(
                    StringSimilarityUtil.normalizeVietnamese(schedule.label),
                    normalizedRaw
                )
                schedule to score
            }
            .maxByOrNull { it.second }

        val SIMILARITY_THRESHOLD = 0.35

        return if (bestMatchPair != null && bestMatchPair.second >= SIMILARITY_THRESHOLD) {
            bestMatchPair.first.id
        } else {
            null
        }
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

        return currentValue ?: ""
    }

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
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
            val resolvedValue: Any = if (resolver != null) {
                resolver(
                    param, currentValue, isPlh, context,
                    secondaryIntentQA, plugins.toList(), excludeIntentId, depth
                ) ?: ""
            } else {
                resolveAliasOrFallback(param, currentValue, isPlh, context)
            }
            resolved[param.name] = resolvedValue

            context.traces.add(TraceNode(
                nodeId = "param.resolve:${param.name}",
                label = "Phân giải tham số: ${param.name}",
                input = "Thô: '$currentValue', isPlaceholder=$isPlh",
                output = "Kết quả: '$resolvedValue'",
                matched = resolvedValue.toString().isNotBlank() && !ParameterResolver.isPlaceholder(resolvedValue, param),
                codeRef = CodeReference(
                    fileName = "RoutingPipeline.kt / ParameterResolver.kt",
                    functionName = "resolveParametersWithMeta",
                    hardcodedRules = "Param='${param.name}', SemanticType='${param.semanticType}', Required=${param.required}, Placeholder='${param.placeholder}'",
                    businessLogic = "Nếu có resolver chuyên biệt theo semanticType thì dùng, không thì fallback sang resolveAliasOrFallback (bestAliasMatches ➔ containsMatch ➔ localEntities)."
                )
            ))
        }

        return resolved
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

    suspend fun process(
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
        val pendingStateForCancel = currentPendingForCancel?.let {
            PendingState(
                isActive = true,
                pluginId = it.pluginId,
                action = it.action,
                missingParams = it.missingParams,
                askedQuestion = it.askedQuestion
            )
        } ?: PendingState(isActive = false)
        
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
                logger.d("RoutingPipeline", "[$traceId] [Tầng 0] Cancel Resolver xác nhận huỷ pending: $cancelledPluginId.$cancelledAction")
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
            logger.d("RoutingPipeline", "[$traceId] [Tầng 0] Pronoun Resolver: \"${pronounResult.resolvedFrom}\" -> \"${pronounResult.resolvedTo}\". Câu sau khi viết lại: \"$resolvedMessage\"")
        }

        val pendings = chatHistoryManager.getActivePendingIntents()
        val isT1Matched = pendings.isNotEmpty() && cancelDecision !is CancelDecision.CancelPending

        if (mode == PipelineMode.EXECUTE) {
            if (pendings.isNotEmpty()) {
                val resolvedResult = pendingIntentResolver.tryResolvePendingIntent(activePending = pendings.first(), userMessage = userMessage, devicePlugins = devicePlugins, traceId = traceId, mode = mode)

                if (resolvedResult != null) {
                    val r = resolvedResult.result
                    val finalResult = when (r) {
                        is PluginResult.Success -> {
                            chatHistoryManager.removePendingIntent(pendings.first().pluginId, pendings.first().action)
                            
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
                                
                                @Suppress("UNCHECKED_CAST")
                                val nextOptions = nextPending.knownParams["_options"] as? Map<String, String> ?: emptyMap()
                                PluginResult.NeedMoreInfo(nextPending.missingParams, combinedMsg, nextOptions)
                            } else {
                                r
                            }
                        }
                        is PluginResult.NeedMoreInfo -> {
                            val targetPlugin = plugins.find { it.manifest.id == pendings.first().pluginId }
                            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == pendings.first().action }
                            val pluginName = targetPlugin?.manifest?.name ?: pendings.first().pluginId
                            val actionDesc = targetAction?.description ?: pendings.first().action
                            
                            val banner = buildPendingBanner(
                                pendings.first().copy(missingParams = r.missingParams, askedQuestion = r.question),
                                pluginName,
                                actionDesc
                            )
                            PluginResult.NeedMoreInfo(r.missingParams, banner, r.options)
                        }
                        is PluginResult.Failure -> {
                            chatHistoryManager.removePendingIntent(pendings.first().pluginId, pendings.first().action)
                            r
                        }
                    }
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(pendings.first().pluginId, finalResult))
                    )
                }
            }
        } else {
            if (isT1Matched && finalOutcome == null) {
                val pendingResult = pendingIntentResolver.tryResolvePendingIntent(pendings.first(), userMessage, devicePlugins, traceId, mode)
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
                logger.d("RoutingPipeline", "[$traceId] ⚠️ Phát hiện từ khóa phủ định -> Bypass thẳng xuống Tầng 5 (LLM) để hiểu đúng ngữ nghĩa.")
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
                    matchResult = matchResult,
                    traces = context.traces
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
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 2 Hit - Confidence: ${layer2Result.confidence}] ${intent.pluginId}.${intent.action}")
                    return try {
                        val result = intentExecutor.executeIntent(plugin, intent, context, traceId)
                        PipelineResult(
                            routerOutcome = RouterOutcome.Matched(DeviceCommandResult(pluginId = intent.pluginId, result = result)),
                            matchResult = matchResult
                        )
                    } catch (e: Exception) {
                        logger.e("RoutingPipeline", "[$traceId] Tầng 2 execute error: ${e.message}", e)
                        PipelineResult(
                            routerOutcome = RouterOutcome.RouterFailed("Tầng 2 execute failed: ${e.message}"),
                            matchResult = matchResult
                        )
                    }
                } else {
                    logger.d("RoutingPipeline", "[$traceId] ⚠️ [Tầng 2 Low Confidence: ${layer2Result.confidence}] -> Chuyển sang Tầng 3")
                }
            }
        } else {
            if (isT2Matched && finalOutcome == null) {
                val executionResult = intentExecutor.executeIntent(layer2Result!!.plugin, layer2Result.intent, context, traceId)
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
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 3 Single] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                    val result = intentExecutor.executeIntent(layer3Result.plugin, layer3Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer3Result.plugin.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Nested -> {
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 3 Nested] ${layer3Result.intent.pluginId}.${layer3Result.intent.action}")
                    val result = intentExecutor.executeIntent(layer3Result.wrapper, layer3Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer3Result.wrapper.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Multi -> {
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 3 Multi] ${layer3Result.intents.size} actions")
                    val completedMsgs = mutableListOf<String>()
                    val pendingResults = mutableListOf<PendingResultInfo>()

                    layer3Result.intents.forEach { (plugin, intent) ->
                        val res = intentExecutor.executeIntent(plugin, intent, context, traceId)
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
                        val executionResult = intentExecutor.executeIntent(layer3Result.plugin, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = intentExecutor.executeIntent(layer3Result.wrapper, layer3Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 3 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 3 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 3 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer3Result.intents.forEach { (plugin, intent) ->
                            val executionResult = intentExecutor.executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
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
                        "• Tách thành công câu lệnh đơn '${layer3Result.intent.pluginId}.${layer3Result.intent.action}'. Phát hiện thiếu thông tin: [${missing.joinToString { intentExecutor.getQuestionForMissingParamDiagnostic(it, layer3Result.plugin, layer3Result.intent.action) }}]"
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
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 4 Single] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                    val result = intentExecutor.executeIntent(layer4Result.plugin, layer4Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer4Result.plugin.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Nested -> {
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 4 Nested] ${layer4Result.intent.pluginId}.${layer4Result.intent.action}")
                    val result = intentExecutor.executeIntent(layer4Result.wrapper, layer4Result.intent, context, traceId)
                    return PipelineResult(
                        routerOutcome = RouterOutcome.Matched(DeviceCommandResult(layer4Result.wrapper.manifest.id, result)),
                        matchResult = matchResult
                    )
                }
                is Layer3Result.Multi -> {
                    logger.d("RoutingPipeline", "[$traceId] ✅ [Tầng 4 Multi] ${layer4Result.intents.size} actions")
                    val completedMsgs = mutableListOf<String>()
                    val pendingResults = mutableListOf<PendingResultInfo>()

                    layer4Result.intents.forEach { (plugin, intent) ->
                        val res = intentExecutor.executeIntent(plugin, intent, context, traceId)
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
                logger.d("RoutingPipeline", "[$traceId] 🍃 [Tầng 4 Miss] Không chứa từ khóa chỉ định lệnh -> Bypass thẳng xuống Chat (bỏ qua Tầng 5)")
                return PipelineResult(
                    routerOutcome = RouterOutcome.NotACommand,
                    matchResult = matchResult
                )
            }

            logger.d("RoutingPipeline", "[$traceId] 🔵 [Tầng 4 Miss] -> LLM")
            return PipelineResult(
                routerOutcome = executeTier3LlmRouting(context, devicePlugins, traceId),
                matchResult = matchResult
            )
        } else {
            if (layer4Result !is Layer3Result.NoMatch && finalOutcome == null) {
                finalOutcome = when (layer4Result) {
                    is Layer3Result.Single -> {
                        val executionResult = intentExecutor.executeIntent(layer4Result.plugin, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Nested -> {
                        val executionResult = intentExecutor.executeIntent(layer4Result.wrapper, layer4Result.intent, context, "DIAGNOSTIC-TRACE")
                        when (executionResult) {
                            is PluginResult.Success -> "✅ [Tầng 4 Chạy Thật Thành Công] ${(executionResult.data as? Map<*, *>)?.get("message") ?: "Đã thực hiện."}"
                            is PluginResult.Failure -> "❌ [Tầng 4 Chạy Thật Thất Bại] ${executionResult.error}"
                            is PluginResult.NeedMoreInfo -> "⚠️ [Tầng 4 Yêu Cầu Nhập Thêm] ${executionResult.question} (Danh sách thiếu: ${executionResult.missingParams.joinToString()})"
                        }
                    }
                    is Layer3Result.Multi -> {
                        val results = mutableListOf<String>()
                        layer4Result.intents.forEach { (plugin, intent) ->
                            val executionResult = intentExecutor.executeIntent(plugin, intent, context, "DIAGNOSTIC-TRACE")
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
                matchResult = matchResult,
                traces = context.traces
            )
        }
    }
}