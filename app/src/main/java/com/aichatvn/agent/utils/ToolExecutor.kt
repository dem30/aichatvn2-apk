package com.aichatvn.agent.utils

import javax.inject.Inject
import javax.inject.Singleton

data class ToolResult(
    val success: Boolean,
    val payload: String,
    val totalCount: Int = 0
)

@Singleton
class ToolExecutor @Inject constructor(
    private val databaseSearchHelper: DatabaseSearchHelper, // Độc lập luồng Database
    private val logger: Logger
) {
    /**
     * Thực thi tập trung các công cụ hỗ trợ của Agent.
     * Mai sau thêm các tool khác (WeatherTool, CalendarTool...) chỉ cần đăng ký tại đây, AgentKernel giữ nguyên vẹn.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult {
        logger.i("ToolExecutor", "🔧 [Tool Start] Bắt đầu thực hiện công cụ: '${toolCall.tool}'")
        
        return try {
            when (toolCall.tool) {
                "db_search" -> {
                    val timeframe = toolCall.params["timeframe"] ?: "today"
                    val objectLabel = toolCall.params["object"] ?: "all"
                    
                    // DatabaseSearchHelper tự quản lý allowedEventTypes và logic lọc của nó (SRP)
                    val result = databaseSearchHelper.executeSearch(
                        timeframe = timeframe,
                        objectLabel = objectLabel
                    )
                    
                    logger.i("ToolExecutor", "✅ [Tool Success] Tìm kiếm thành công. Bản ghi khớp: ${result.logs.size}/${result.totalCount}")
                    ToolResult(
                        success = true,
                        payload = result.summaryText,
                        totalCount = result.totalCount
                    )
                }
                
                // Điểm móc nối mở rộng các Tool khác trong tương lai:
                // "weather_search" -> { weatherTool.getForecast(toolCall.params["location"]) }
                // "calendar_query" -> { ... }
                
                else -> {
                    logger.w("ToolExecutor", "⚠️ Không tìm thấy công cụ đăng ký cho nhãn: '${toolCall.tool}'")
                    ToolResult(success = false, payload = "Lỗi: Không tìm thấy công cụ tương thích.")
                }
            }
        } catch (e: Exception) {
            logger.e("ToolExecutor", "❌ [Tool Error] Lỗi thực thi công cụ: ${e.message}", e)
            ToolResult(success = false, payload = "Lỗi hệ thống khi đang chạy công cụ hỗ trợ.")
        }
    }
}