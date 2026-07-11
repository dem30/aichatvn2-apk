package com.aichatvn.agent.core.router.stages

import com.aichatvn.agent.core.RoutingContext
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline

interface RouterStage<out T> {
    suspend fun process(
        context: RoutingContext,
        devicePlugins: List<Plugin>,
        pipeline: RoutingPipeline
    ): T
}