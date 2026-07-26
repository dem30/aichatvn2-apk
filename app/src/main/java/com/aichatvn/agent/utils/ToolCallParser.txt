package com.aichatvn.agent.utils

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ToolCall(val tool: String, val params: Map<String, String>)

@Singleton
class ToolCallParser @Inject constructor(
    private val logger: Logger
) {
    /**
     * Bóc tách an toàn chuỗi JSON thô chứa lệnh gọi Tool của AI (hỗ trợ dọn dẹp markdown).
     */
    fun parse(responseRaw: String): ToolCall? {
        val trimmed = responseRaw.trim()
        
        // Tìm khối JSON {} đầu tiên để loại bỏ các đoạn chat tự do rác của AI bọc bên ngoài
        val jsonString = try {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start in 0 until end) {
                trimmed.substring(start, end + 1).trim()
            } else {
                trimmed
            }
        } catch (e: Exception) {
            return null
        }

        return try {
            val json = JSONObject(jsonString)
            val toolName = json.optString("tool", "").trim()
            if (toolName.isBlank()) return null

            val params = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                if (key != "tool") {
                    params[key] = json.optString(key)
                }
            }
            ToolCall(tool = toolName, params = params)
        } catch (e: Exception) {
            logger.e("ToolCallParser", "❌ Phân tích JSON Tool Call thất bại: ${e.message}")
            null
        }
    }
}