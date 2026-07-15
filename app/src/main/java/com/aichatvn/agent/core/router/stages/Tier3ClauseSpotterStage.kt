package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap
import com.aichatvn.agent.data.model.QAEntity

@Singleton
class Tier3ClauseSpotterStage @Inject constructor(
    private val trainingSkill: TrainingSkill,
    private val logger: Logger
) : RouterStage<Layer3Result> {

    // ✅ ĐÃ SỬA: Lọc bỏ alias không liên quan tới action đã khớp trước khi quyết định nhánh xử lý
    // (xem chi tiết lý do ở resolveRelevantAliases bên dưới) — tránh clause bị bỏ qua oan khi có
    // alias khác chủ đề (vd category "camera") vô tình trùng substring trong cùng câu lệnh đèn/quạt.
    private fun relevantSemanticTypesForIntents(
        matchedIntents: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): Set<String> {
        val types = mutableSetOf<String>()
        matchedIntents.forEach { qa ->
            val json = try { JSONObject(qa.answer) } catch (e: Exception) { null }
            val pluginId = json?.optString("plugin") ?: ""
            val actionName = json?.optString("action") ?: ""
            val targetPlugin = devicePlugins.find { it.manifest.id == pluginId }
            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == actionName }
            targetAction?.parameters?.forEach { types.add(it.semanticType) }
        }
        return types
    }

    override suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
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
                logger.d("RoutingPipeline", "[ClauseSpotter] ⚠️ Tỷ lệ bao phủ mệnh đề '$clause' quá thấp (${String.format("%.2f", coverageRatio)} < 0.70). Bỏ qua Tầng 3.")
                continue
            }

            val relevantSemanticTypes = relevantSemanticTypesForIntents(matchedIntents, devicePlugins)
            val relevantAliases = matchedAliases.filter { it.first.category in relevantSemanticTypes }

            val uniqueTypes = relevantAliases.map { it.first.category }.distinct()
            val hasDuplicateTypes = relevantAliases.size != uniqueTypes.size

            // ✅ SCHEDULE-WRAPPER GUARD: Nếu clause vừa khớp intent "schedule" (lên lịch) vừa khớp
            // intent hành động thiết bị khác (vd "bật đèn"), thì "schedule" không phải là 1 ý định
            // độc lập ngang hàng — nó là ý định BAO TRÙM (wrapper). Hành động thiết bị đi kèm chỉ mô
            // tả NỘI DUNG của lịch, không phải lệnh cần thực thi ngay. Nếu để lọt vào isMultiIntent,
            // Layer3Result.Multi sẽ thực thi thật CẢ HAI song song -> đèn bị bật ngay dù người dùng
            // chỉ muốn lên lịch. Do đó phải tách case này ra ưu tiên trước, giống cơ chế wrapperIntentPair
            // đã áp dụng ở Tier2SlotResolverStage.
            val scheduleIntentQA = matchedIntents.firstOrNull { qa ->
                val json = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                json?.optString("plugin") == "schedule"
            }
            val isScheduleWrapperCase = scheduleIntentQA != null && matchedIntents.size > 1

            val isIntentDouble = matchedIntents.size == 1 && relevantAliases.size > 1 && uniqueTypes.size == 1
            val isMultiIntent = matchedIntents.size > 1 && !hasDuplicateTypes && !isScheduleWrapperCase

            context.traces.add(TraceNode(
                nodeId = "clause.branchSelect",
                label = "Chọn nhánh xử lý mệnh đề (Clause Spotter)",
                input = "Clause: '$clause' | intents=${matchedIntents.size}, aliases=${matchedAliases.size} (liên quan=${relevantAliases.size}), coverage=${String.format("%.2f", coverageRatio)}",
                output = when {
                    isScheduleWrapperCase -> "isScheduleWrapperCase: intent 'schedule' bao trùm, bỏ qua ${matchedIntents.size - 1} intent hành động thiết bị đi kèm (không thực thi song song)"
                    isIntentDouble -> "isIntentDouble: 1 ý định + nhiều alias cùng category '${uniqueTypes.firstOrNull()}'"
                    isMultiIntent -> "isMultiIntent: ${matchedIntents.size} ý định độc lập, không trùng category"
                    matchedIntents.size == 1 && relevantAliases.size <= 1 -> "Đơn lệnh chuẩn"
                    else -> "Không khớp nhánh nào, bỏ qua clause"
                },
                matched = isScheduleWrapperCase || isIntentDouble || isMultiIntent || (matchedIntents.size == 1 && relevantAliases.size <= 1),
                codeRef = CodeReference(
                    fileName = "AgentKernel.kt",
                    functionName = "processLayer3ClauseEntitySpotter",
                    hardcodedRules = "coverageRatio >= 0.70 (ngưỡng bao phủ mệnh đề), isIntentDouble = 1 intent + >1 alias LIÊN QUAN cùng category, isMultiIntent = >1 intent khác category. Alias có category không thuộc semanticType của bất kỳ tham số nào trong action đã khớp sẽ bị loại trước khi xét (vd alias 'camera' khi action đang xét không có tham số kiểu camera).",
                    businessLogic = "Tách câu thành các mệnh đề rồi phân loại: 1 ý định lặp nhiều thiết bị cùng loại (isIntentDouble), nhiều ý định độc lập song song (isMultiIntent), hoặc đơn lệnh."
                )
            ))

            when {
                isScheduleWrapperCase -> {
                    val qa = scheduleIntentQA!!
                    val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }

                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = pipeline.resolveParametersWithMeta(
                            parameters = targetAction.parameters,
                            inputParams = rootParams,
                            context = context,
                            excludeIntentId = qa.id,
                            depth = 0
                        )
                        // ⚠️ CHỦ Ý: KHÔNG lặp qua các matchedIntents khác (vd "bật đèn") để executeIntent
                        // riêng — chúng chỉ mô tả nội dung của lịch, không phải lệnh thực thi ngay.
                        resolvedIntents.add(targetPlugin to Intent(rootPluginId, rootActionName, resolvedParams))
                    }
                }

                isIntentDouble -> {
                    val singleIntentQA = matchedIntents.first()
                    val rootJson = try { JSONObject(singleIntentQA.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        
                        for (alias in relevantAliases) {
                            val resolvedParams = pipeline.resolveParametersWithMeta(
                                parameters = targetAction.parameters,
                                inputParams = rootParams,
                                context = context.copy(globalMatchResult = pipeline.matchResultCopyForSingleAlias(context.globalMatchResult, alias.first)),
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
                            val resolvedParams = pipeline.resolveParametersWithMeta(
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

                matchedIntents.size == 1 && relevantAliases.size <= 1 -> {
                    val qa = matchedIntents.first()
                    val rootJson = try { JSONObject(qa.answer) } catch (e: Exception) { null }
                    val rootPluginId = rootJson?.optString("plugin") ?: ""
                    val rootActionName = rootJson?.optString("action") ?: ""
                    val targetPlugin = devicePlugins.find { it.manifest.id == rootPluginId }
                    val targetAction = targetPlugin?.manifest?.actions?.find { it.name == rootActionName }
                    
                    if (targetPlugin != null && targetAction != null) {
                        val rootParams = rootJson?.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = pipeline.resolveParametersWithMeta(
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

    


    
}