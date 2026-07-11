package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Tier4MetadataStage @Inject constructor(
    private val logger: Logger
) : RouterStage<Layer3Result> {

    override suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
    ): Layer3Result {
        val resolvedIntents = mutableListOf<Pair<Plugin, Intent>>()
        val queryNormalized = StringSimilarityUtil.normalizeVietnamese(context.resolvedQuery)

        val hasScheduleSignal = context.localEntities.containsKey("cron") || 
            queryNormalized.contains("lich", ignoreCase = true) ||
            queryNormalized.contains("hen gio", ignoreCase = true) ||
            queryNormalized.contains("setup", ignoreCase = true)

        if (hasScheduleSignal) {
            val schedulePlugin = devicePlugins.find { it.manifest.id == "schedule" }
            val targetActionName = pipeline.detectScheduleAction(queryNormalized)
            val targetAction = schedulePlugin?.manifest?.actions?.find { it.name == targetActionName }

            if (schedulePlugin != null && targetAction != null) {
                logger.d("RoutingPipeline", "🎯 [Tầng 4 Wrapper] Phát hiện tín hiệu lập lịch. Xác định action phù hợp: '$targetActionName'")
                
                val schemaParams = mutableMapOf<String, Any>()
                targetAction.parameters.forEach { param ->
                    schemaParams[param.name] = param.defaultValue ?: ""
                }
                
                val resolvedParams = pipeline.resolveParametersWithMeta(
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
            val sortedMetadata = pipeline.normalizedActionMetadataList
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
                        val resolvedParams = pipeline.resolveParametersWithMeta(
                            parameters = action.parameters,
                            inputParams = schemaParams,
                            context = context.copy(globalMatchResult = pipeline.matchResultCopyForSingleAlias(context.globalMatchResult, alias.first)),
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
                        val resolvedParams = pipeline.resolveParametersWithMeta(
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
                    val resolvedParams = pipeline.resolveParametersWithMeta(
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
            resolvedIntents.isEmpty() -> Layer3Result.NoMatch
            resolvedIntents.size == 1 -> {
                val (plugin, intent) = resolvedIntents.first()
                Layer3Result.Single(plugin, intent)
            }
            else -> Layer3Result.Multi(resolvedIntents)
        }
    }
}