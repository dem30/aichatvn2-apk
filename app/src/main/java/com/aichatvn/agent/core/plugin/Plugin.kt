package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.ui.dashboard.DeviceNode

data class PluginCapabilities(
    val dashboard: Boolean = false,
    val training: Boolean = false,
    val notification: Boolean = false,
    val schedule: Boolean = false,
    val voice: Boolean = false,
    val vision: Boolean = false
)

data class PluginManifest(
    val id: String,
    val name: String,
    val capabilities: PluginCapabilities,
    val actions: List<PluginAction>,
    val pluginVersion: String = "1.0.0",
    val metadataVersion: String = "1.0.0",
    val schemaVersion: String = "1.0.0",
    val routable: Boolean = true,
    val visibleOnDashboard: Boolean = false,
    val autoGenerateQA: Boolean = true
)

interface Plugin {
    val manifest: PluginManifest

    // ─── [CẦU NỐI TƯƠNG THÍCH NGƯỢC] ──────────────────────────────────────────
    // Giúp tất cả các file cũ gọi plugin.id, plugin.name, plugin.getActions() không bị lỗi build
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val pluginVersion: String get() = manifest.pluginVersion
    val metadataVersion: String get() = manifest.metadataVersion
    val schemaVersion: String get() = manifest.schemaVersion
    val routable: Boolean get() = manifest.routable
    val visibleOnDashboard: Boolean get() = manifest.visibleOnDashboard
    val autoGenerateQA: Boolean get() = manifest.autoGenerateQA

    val supportsVoice: Boolean get() = manifest.capabilities.voice
    val supportsSchedule: Boolean get() = manifest.capabilities.schedule
    val supportsNotification: Boolean get() = manifest.capabilities.notification
    val supportsBackground: Boolean get() = manifest.capabilities.background
    val supportsVision: Boolean get() = manifest.capabilities.vision

    fun getActions(): List<PluginAction> = manifest.actions
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun initialize()
    suspend fun shutdown()
    suspend fun onInstalled() {}
    suspend fun onUpdated() {}
    suspend fun onRemoved() {}

    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    
    fun getBootstrapQA(): List<PluginQABootstrap> = emptyList()

    suspend fun getDashboardNodes(): List<DeviceNode> = emptyList()
}

data class PluginQABootstrap(
    val question: String,
    val answer: String,
    val type: String,
    val category: String = "auto_init"
)

data class PluginParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val semanticType: String = "string",
    val placeholder: String = "",
    val enumValues: List<String> = emptyList(),
    val defaultValue: Any? = null,
    val validationRegex: String = ""
) {
    fun normalize(value: Any?): Any? {
        val strVal = value?.toString()?.trim() ?: ""
        if (strVal.isBlank() || strVal == "null") {
            return defaultValue
        }
        return when (type.lowercase()) {
            "boolean" -> {
                if (value is Boolean) return value
                val lower = strVal.lowercase()
                val trueWords = setOf("true", "mở", "bật", "yes", "on", "1", "kích hoạt", "enable")
                val falseWords = setOf("false", "tắt", "no", "off", "0", "dừng", "vô hiệu", "disable")
                when (lower) {
                    in trueWords -> true
                    in falseWords -> false
                    else -> defaultValue ?: false
                }
            }
            "number" -> {
                strVal.toDoubleOrNull() ?: defaultValue ?: 0
            }
            else -> {
                if (validationRegex.isNotBlank() && !Regex(validationRegex).matches(strVal)) {
                    defaultValue ?: value
                } else {
                    value
                }
            }
        }
    }
}

data class PluginAction(
    val name: String,
    val description: String,
    val examples: List<String> = emptyList(),
    val parameters: List<PluginParameter> = emptyList(),
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val triggerPrefixes: List<String> = emptyList()
)