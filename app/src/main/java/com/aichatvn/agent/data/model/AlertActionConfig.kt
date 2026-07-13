package com.aichatvn.agent.data.model

import org.json.JSONArray
import org.json.JSONObject

// ✅ MỚI: Di dời từ CameraDetailViewModel.kt (ui.viewmodels) sang data.model để
// CameraSkill.kt (core/skills) không phải phụ thuộc ngược vào tầng UI khi cần
// dùng chung kiểu dữ liệu này cho tính năng "hành động khi có cảnh báo thật"
// (dùng chung ở cả cấp camera lẫn cấp từng lịch trình riêng).
data class AlertActionConfig(
    val pluginId: String,
    val action: String,
    val params: Map<String, String> = emptyMap()
)

fun alertActionsToJson(list: List<AlertActionConfig>): String {
    val arr = JSONArray()
    list.forEach { cfg ->
        arr.put(JSONObject().apply {
            put("pluginId", cfg.pluginId)
            put("action", cfg.action)
            put("params", JSONObject(cfg.params))
        })
    }
    return arr.toString()
}

fun alertActionsFromJson(json: String): List<AlertActionConfig> {
    return try {
        val arr = JSONArray(json.ifBlank { "[]" })
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val paramsObj = obj.optJSONObject("params") ?: JSONObject()
            val paramsMap = mutableMapOf<String, String>()
            paramsObj.keys().forEach { k -> paramsMap[k] = paramsObj.optString(k) }
            AlertActionConfig(
                pluginId = obj.optString("pluginId"),
                action = obj.optString("action"),
                params = paramsMap
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
