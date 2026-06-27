package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel

interface Plugin {
    val id: String
    val name: String

    // Phiên bản metadata của plugin (Phục vụ nâng cấp schema)
    val pluginVersion: String get() = "1.0.0"
    val metadataVersion: String get() = "1.0.0"
    val schemaVersion: String get() = "1.0.0"

    // Khai báo Capabilities của Plugin
    val supportsVoice: Boolean get() = false
    val supportsSchedule: Boolean get() = false
    val supportsNotification: Boolean get() = false
    val supportsBackground: Boolean get() = false

    val routable: Boolean 
        get() = true

    val visibleOnDashboard: Boolean 
        get() = false

    val autoGenerateQA: Boolean 
        get() = true

    // Quản lý Lifecycle mở rộng
    suspend fun initialize()
    suspend fun shutdown()
    suspend fun onInstalled() {}
    suspend fun onUpdated() {}
    suspend fun onRemoved() {}

    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    
    fun getActions(): List<PluginAction>
    fun getQATriggers(): Map<String, List<String>> = emptyMap()

    // Khai báo bootstrap QA tự động (Decouple hoàn toàn QA Init Builder khỏi logic lõi của Agent)
    fun getBootstrapQA(): List<PluginQABootstrap> = emptyList()
}

data class PluginQABootstrap(
    val question: String,
    val answer: String,
    val type: String, // "intent" hoặc tên loại alias "camera"/"device"/"email"
    val category: String = "auto_init"
)

data class PluginParameter(
    val name: String,
    val type: String, // "string" | "boolean" | "number" | "object"
    val description: String,
    val required: Boolean,
    val semanticType: String = "string", // "email", "camera", "device", "time", "interval", "plugin_id", "action_id", "params"
    val placeholder: String = "",
    val examples: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val enumValues: List<String> = emptyList(),
    val defaultValue: Any? = null,
    val validationRegex: String = ""
) {
    // Tự xử lý normalize dữ liệu theo đặc tả tham số (Rút ngắn code xử lý trong AgentKernel)
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
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true
)