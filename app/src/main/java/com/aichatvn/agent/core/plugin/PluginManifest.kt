package com.aichatvn.agent.core.plugin

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "AIChatVN2",
    val keywords: List<String> = emptyList(),
    val actions: List<PluginAction>,
    val subscribes: List<String> = emptyList(),
    val publishes: List<String> = emptyList()
)

data class PluginAction(
    val name: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val parameters: List<PluginParameter>
)

data class PluginParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)