package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel

interface Plugin {
    val id: String
    val name: String

    // Cập nhật 3 flag độc lập theo thiết kế mới
    val routable: Boolean 
        get() = true

    val visibleOnDashboard: Boolean 
        get() = false

    val autoGenerateQA: Boolean 
        get() = true

    suspend fun initialize()
    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    suspend fun shutdown()
    
    fun getActions(): List<PluginAction>
    
    fun getQATriggers(): Map<String, List<String>> = emptyMap()
}

data class PluginParameter(
    val name: String,
    val type: String, // "string" | "boolean" | "number" | "object"
    val description: String,
    val required: Boolean,
    val semanticType: String = "string" // "email", "camera", "device", "time", "interval", "plugin_id", "action_id", "params"
)

data class PluginAction(
    val name: String,
    val description: String,
    val parameters: List<PluginParameter>
)