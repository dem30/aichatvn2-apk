package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.HouseSituation

interface HouseManagerSkill : Plugin {
    /**
     * Thuật toán phân tích dữ liệu Bản sao số (World State) để quy nạp ra HouseSituation thực tế
     */
    suspend fun evaluateSituation(): HouseSituation

    /**
     * Sự kiện lắng nghe biến động từ Bản sao số của các Skill ngoại vi
     */
    suspend fun onWorldStateChanged(source: String, sourceId: String, key: String, value: String)

    /**
     * Lắng nghe sự kiện mới ghi nhận vào Event Log để bổ sung nhận thức
     */
    suspend fun onEvent(event: EventLogEntity)

    /**
     * Tạo dữ liệu ngữ cảnh cô đọng (đầu vào chất lượng cho LLM, chống Token Bloat)
     */
    suspend fun buildSystemContext(): String
}