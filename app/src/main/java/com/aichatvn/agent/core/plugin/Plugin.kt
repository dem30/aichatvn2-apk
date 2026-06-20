package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel

interface Plugin {
    val id: String
    val name: String

    // ✅ Mặc định true; các skill hệ thống/chat override thành false để ẩn khỏi
    // catalog định tuyến local (AgentKernel.tryDeviceCommand) và UI quick bar.
    val visibleInQuickBar: Boolean get() = true

    suspend fun initialize()
    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    suspend fun shutdown()
    
    // ✅ Thêm để AgentKernel biết plugin có những action gì
    fun getActions(): List<PluginAction>
}

data class PluginAction(
    val name: String,
    val description: String,
    val parameters: List<PluginParameter> = emptyList()
)

data class PluginParameter(
    val name: String,
    val type: String,  // string, boolean, number, object
    val description: String,
    val required: Boolean = true
)