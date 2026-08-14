package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DashboardProvider

data class PluginCapabilities(
    val dashboard: Boolean = false,
    val training: Boolean = false,
    val notification: Boolean = false,
    val schedule: Boolean = false,
    val voice: Boolean = false,
    val vision: Boolean = false,
    val background: Boolean = false 
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

// ✅ SỬA: Plugin giờ extends DashboardProvider — đây là mắt xích còn thiếu khiến
// SmartSwitchSkill.pollLightweightStates() "overrides nothing" và
// DashboardViewModel.plugin.pollLightweightStates() "Unresolved reference". Trước đây
// Plugin tự khai báo getDashboardNodes() riêng, không liên quan gì tới DashboardProvider,
// nên pollLightweightStates() (chỉ khai báo bên DashboardProvider) không hề "có mặt" trên
// kiểu Plugin mà SmartSwitchSkill/DashboardViewModel đang thao tác. Xem ARCHITECTURE.md
// mục 3.4 — thiết kế gốc đã mô tả đúng quan hệ này, chỉ chưa được nối dây ở code.
interface Plugin : DashboardProvider {
    val manifest: PluginManifest

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

    suspend fun initialize()
    suspend fun shutdown()
    suspend fun onInstalled() {}
    suspend fun onUpdated() {}
    suspend fun onRemoved() {}

    suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult
    
    fun getBootstrapQA(): List<PluginQABootstrap> = emptyList()

    // ✅ SỬA: thêm `override` vì giờ đến từ DashboardProvider — chữ ký và default
    // (emptyList()) giữ nguyên y hệt trước, không đổi hành vi cho bất kỳ Plugin nào đã có.
    // pollLightweightStates() KHÔNG cần khai báo lại ở đây — Plugin thừa hưởng thẳng default
    // emptyMap() từ DashboardProvider (xem DashboardProvider.kt).
    override suspend fun getDashboardNodes(): List<DeviceNode> = emptyList()
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
    val validationRegex: String = "",
    // 🌟 MỚI: Tên tham số cha mà tham số này phụ thuộc vào
    val dependsOn: String? = null,
    // 🌟 MỚI: Danh sách các giá trị của tham số cha để tham số này ĐƯỢC HIỂN THỊ
    val visibleWhen: List<String>? = null
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

                val trueWords = setOf("true", "mở", "bật", "yes", "on", "1", "kích hoạt", "enable", "có", "đồng ý", "xác nhận", "ok", "chuẩn", "đúng")
                val falseWords = setOf("false", "tắt", "no", "off", "0", "dừng", "vô hiệu", "disable", "không", "hủy", "huỷ", "thôi")

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
    val exampleOverrides: Map<String, Map<String, Any>> = emptyMap(), 
    val parameters: List<PluginParameter> = emptyList(),
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val triggerPrefixes: List<String> = emptyList(),
    
    // ✅ MỚI (Tuần 5 - Phase 5): Ràng buộc trạng thái thế giới thực để chạy an toàn
    // Định dạng cấu trúc: "source.sourceId.attrKey=expectedValue"
    val requiredWorldState: String = ""
)