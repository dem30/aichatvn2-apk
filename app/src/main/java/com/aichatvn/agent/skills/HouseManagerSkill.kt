package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.HouseSituation

// ✅ Khai báo lớp dữ liệu ChatDecision tại cấp độ file
data class ChatDecision(
    val shouldAutoRespond: Boolean,
    val intent: String,
    val urgency: String,
    val unreadCount: Int,
    val summary: String
)

// ✅ Định nghĩa một bước hành động đơn lẻ trong chuỗi kế hoạch
data class ActionStep(
    val pluginId: String,            // Plugin đích (vd: smart_switch, camera)
    val action: String,              // Hành động (vd: set, scan)
    val params: Map<String, Any>,    // Tham số thực thi
    val delayMs: Long = 0L,          // Thời gian hoãn chờ (milli-giây) TRƯỚC khi thực hiện bước này
    val precondition: String? = null // Điều kiện thế giới thực dạng "source.sourceId.key=value"
)

// ✅ Theo dõi trạng thái tiến trình chạy của một kế hoạch
data class PlanStatus(
    val planId: String,
    val goalName: String,
    val currentStepIndex: Int,
    val totalSteps: Int,
    val status: String,              // RUNNING, COMPLETED, FAILED, BLOCKED
    val logs: List<String>
)

// ✅ Khai báo kết quả kiểm duyệt chính sách
sealed class PolicyResult {
    object Allowed : PolicyResult()
    data class Blocked(val reason: String) : PolicyResult()
}

interface HouseManagerSkill : Plugin {
    suspend fun evaluateSituation(): HouseSituation
    suspend fun onWorldStateChanged(source: String, sourceId: String, key: String, value: String)
    suspend fun onEvent(event: EventLogEntity)
    suspend fun buildSystemContext(): String

    suspend fun sendDefaultCameraAlerts(
        camera: CameraConfigEntity,
        aiComment: String,
        imageBytes: ByteArray?,
        activeAlertId: String,
        shouldMerge: Boolean
    ): Boolean

    suspend fun handleChatEventDecision(
        platform: String,
        senderId: String,
        message: String,
        timestamp: Long
    ): ChatDecision

    suspend fun executePlan(goalName: String, steps: List<ActionStep>)
    suspend fun triggerProtectHouseSequence(cameraId: String)
    fun getActivePlans(): List<PlanStatus>

    suspend fun checkPolicy(pluginId: String, action: String, params: Map<String, Any>): PolicyResult
    suspend fun mineUserHabits()
}