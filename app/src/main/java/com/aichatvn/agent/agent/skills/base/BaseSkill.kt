package com.aichatvn.agent.skills.base

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.utils.Logger

abstract class BaseSkill(
    override val id: String, // Giữ ghi đè để cầu nối tương thích ngược trong Plugin hoạt động chính xác
    override val name: String,
    protected val logger: Logger
) : Plugin {
    
    override suspend fun initialize() {}
    override suspend fun shutdown() {}
    
    protected fun success(message: String, data: Map<String, Any> = emptyMap()): AgentKernel.PluginResult {
        return AgentKernel.PluginResult.Success(
            mapOf("message" to message) + data
        )
    }
    
    protected fun failure(message: String): AgentKernel.PluginResult {
        return AgentKernel.PluginResult.Failure(message)
    }
    
    protected fun needMoreInfo(missingParams: List<String>, question: String): AgentKernel.PluginResult {
        return AgentKernel.PluginResult.NeedMoreInfo(missingParams, question)
    }
}