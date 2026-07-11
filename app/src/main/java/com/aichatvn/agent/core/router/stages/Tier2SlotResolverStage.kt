package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.Layer2Result
import com.aichatvn.agent.core.RoutingContext
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap

@Singleton
class Tier2SlotResolverStage @Inject constructor() : RouterStage<Layer2Result?> {

    override suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
    ): Layer2Result? {
        val wrapperIntentPair = context.globalMatchResult.intentMatches
            .find { 
                try {
                    JSONObject(it.first.answer).optString("plugin") == "schedule"
                } catch (_: Exception) {
                    false
                }
            }

        if (wrapperIntentPair == null) {
            val queryNorm = StringSimilarityUtil.normalizeVietnamese(context.resolvedQuery)
            val scheduleManageSignal = (queryNorm.contains("lich") || queryNorm.contains("hen gio")) &&
                setOf("huy", "xoa", "bo", "ngung", "dung lich", "sua", "doi", "cap nhat",
          "xem", "liet ke", "danh sach", "kiem tra",
          "tat lich", "bat lich", "kich hoat", "vo hieu hoa").any { queryNorm.contains(it) }
            if (scheduleManageSignal) return null
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

        val resolvedParams = pipeline.resolveParametersWithMeta(
            parameters = rootAction.parameters,
            inputParams = rootParams,
            context = context,
            excludeIntentId = bestIntentQA.id,
            depth = 0
        )

        return Layer2Result(rootPlugin, AgentKernel.Intent(rootPluginId, rootActionName, resolvedParams), confidence)
    }



    
}