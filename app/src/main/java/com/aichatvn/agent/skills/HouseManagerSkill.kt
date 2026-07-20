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
}