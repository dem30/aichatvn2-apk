package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.HouseSituation

// ... [Khai báo ChatDecision, ActionStep, PlanStatus giữ nguyên]

// ✅ MỚI (Giai đoạn 4): Định nghĩa kết quả kiểm duyệt chính sách
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

    // ✅ MỚI (Giai đoạn 4 - Policy Engine): Kiểm duyệt chính sách an toàn trước khi cấp điện thiết bị
    suspend fun checkPolicy(pluginId: String, action: String, params: Map<String, Any>): PolicyResult

    // ✅ MỚI (Giai đoạn 4 - Learning Engine): Tự học thói quen người dùng từ nhật ký 7 ngày gần nhất
    suspend fun mineUserHabits()
}