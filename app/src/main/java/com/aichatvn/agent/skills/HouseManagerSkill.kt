package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.HouseSituation

// ✅ ĐÃ SỬA: Khai báo lớp dữ liệu ChatDecision tại cấp độ file để tránh lỗi Unresolved Reference
data class ChatDecision(
    val shouldAutoRespond: Boolean,
    val intent: String,
    val urgency: String,
    val unreadCount: Int,
    val summary: String
)

// ✅ MỚI (Giai đoạn 3 - Planner): Định nghĩa một bước hành động đơn lẻ trong chuỗi kế hoạch
data class ActionStep(
    val pluginId: String,            // Plugin đích (vd: smart_switch, camera)
    val action: String,              // Hành động (vd: set, scan)
    val params: Map<String, Any>,    // Tham số thực thi
    val delayMs: Long = 0L,          // Thời gian hoãn chờ (milli-giây) TRƯỚC khi thực hiện bước này
    val precondition: String? = null // Điều kiện thế giới thực dạng "source.sourceId.key=value"
)

// ✅ MỚI (Giai đoạn 3 - Planner): Theo dõi trạng thái tiến trình chạy của một kế hoạch
data class PlanStatus(
    val planId: String,
    val goalName: String,
    val currentStepIndex: Int,
    val totalSteps: Int,
    val status: String,              // RUNNING, COMPLETED, FAILED, BLOCKED
    val logs: List<String>
)

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

    // 🧠 MỚI (Giai đoạn 3 - Planner Engine):
    // Cho phép Quản gia tiếp nhận một chuỗi hành động và tự chạy bất đồng bộ dưới nền
    suspend fun executePlan(goalName: String, steps: List<ActionStep>)

    // Kích hoạt kịch bản mẫu "Bảo vệ nhà liên hoàn" khi phát hiện có xâm nhập bất thường
    suspend fun triggerProtectHouseSequence(cameraId: String)

    // Lấy danh sách các kế hoạch đang chạy để hiển thị lên Screen UI sau này
    fun getActivePlans(): List<PlanStatus>
}