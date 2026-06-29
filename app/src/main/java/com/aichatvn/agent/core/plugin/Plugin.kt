package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.ui.dashboard.DeviceNode // Thêm import trực tiếp để hợp nhất Dashboard

// Khai báo tập hợp các năng lực đặc hữu của Plugin
data class PluginCapabilities(
    val dashboard: Boolean = false,
    val training: Boolean = false,
    val notification: Boolean = false,
    val schedule: Boolean = false,
    val voice: Boolean = false,
    val vision: Boolean = false
)

// Khai báo Tuyên bố Siêu dữ liệu chuẩn hóa của Plugin
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
    // Thuộc tính mô tả duy nhất nắm giữ toàn bộ cấu hình, loại bỏ các biến rời rạc cũ
    val manifest: PluginManifest

    // Quản lý Lifecycle mở rộng
    suspend fun initialize()
    suspend fun shutdown()
    suspend fun onInstalled() {}
    suspend fun onUpdated() {}
    suspend fun onRemoved() {}

    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    
    fun getBootstrapQA(): List<PluginQABootstrap> = emptyList()

    // Thay thế hoàn toàn giao diện DashboardProvider cũ
    suspend fun getDashboardNodes(): List<DeviceNode> = emptyList()
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