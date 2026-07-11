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

            val uniqueTypes = matchedAliases.map { it.first.category }.distinct()
            val hasDuplicateTypes = matchedAliases.size != uniqueTypes.size

            val isIntentDouble = matchedIntents.size == 1 && matchedAliases.size > 1 && uniqueTypes.size == 1
            val isMultiIntent = matchedIntents.size > 1 && !hasDuplicateTypes

            context.traces.add(TraceNode(
                nodeId = "clause.branchSelect",
                label = "Chọn nhánh xử lý mệnh đề (Clause Spotter)",
                input = "Clause: '$clause' | intents=${matchedIntents.size}, aliases=${matchedAliases.size}, coverage=${String.format("%.2f", coverageRatio)}",
                output = when {
                    isIntentDouble -> "isIntentDouble: 1 ý định + nhiều alias cùng category '${uniqueTypes.firstOrNull()}'"
                    isMultiIntent -> "isMultiIntent: ${matchedIntents.size} ý định độc lập, không trùng category"
                    matchedIntents.size == 1 && matchedAliases.size <= 1 -> "Đơn lệnh chuẩn"
                    else -> "Không khớp nhánh nào, bỏ qua clause"
                },
                matched = isIntentDouble || isMultiIntent || (matchedIntents.size == 1 && matchedAliases.size <= 1),
                codeRef = CodeReference(
                    fileName = "AgentKernel.kt",
                    functionName = "processLayer3ClauseEntitySpotter",
                    hardcodedRules = "coverageRatio >= 0.70 (ngưỡng bao phủ mệnh đề), isIntentDouble = 1 intent + >1 alias cùng category, isMultiIntent = >1 intent khác category",
                    businessLogic = "Tách câu thành các mệnh đề rồi phân loại: 1 ý định lặp nhiều thiết bị cùng loại (isIntentDouble), nhiều ý định độc lập song song (isMultiIntent), hoặc đơn lệnh."
                )
            ))

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

                matchedIntents.size == 1 && matchedAliases.size <= 1 -> {
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