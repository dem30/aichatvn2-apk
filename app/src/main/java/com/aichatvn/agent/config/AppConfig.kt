package com.aichatvn.agent.config

/**
 * AppConfig - Cấu hình toàn cục
 * 
 * Tất cả cấu hình tập trung ở đây, dễ dàng thay đổi
 */
object AppConfig {
    
    // ===== SCHEDULED TASKS =====
    // Thêm task mới chỉ cần thêm vào list này
    // TaskScheduler sẽ tự động chạy
    
    data class ScheduledTask(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any> = emptyMap(),
        val intervalMinutes: Int = 15,
        val enabled: Boolean = true
    )
    
    private val scheduledTasks = listOf(
        ScheduledTask(
            pluginId = "camera",
            action = "scan",
            params = emptyMap(),
            intervalMinutes = 15,
            enabled = true
        ),
        // Sau này thêm:
        // ScheduledTask(
        //     pluginId = "email",
        //     action = "sync",
        //     intervalMinutes = 60,
        //     enabled = true
        // ),
        // ScheduledTask(
        //     pluginId = "weather",
        //     action = "update",
        //     intervalMinutes = 30,
        //     enabled = true
        // ),
        // ScheduledTask(
        //     pluginId = "sensor",
        //     action = "read",
        //     intervalMinutes = 5,
        //     enabled = true
        // )
    )
    
    fun getScheduledTasks(): List<ScheduledTask> {
        return scheduledTasks.filter { it.enabled }
    }
    
    // ===== LLM ROUTING =====
    // Prompt cho AgentKernel routing
    
    const val ROUTING_SYSTEM_PROMPT = """
        Bạn là bộ phân tích ý định cho AI quản gia.
        Phân tích câu nói của người dùng và chọn plugin phù hợp.
        Trả về JSON thuần túy.
    """
}