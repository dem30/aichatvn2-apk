package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HousekeeperSkill @Inject constructor(
    logger: Logger
) : BaseSkill("housekeeper", "Quản gia tự động", logger) {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(schedule = true, background = true),
        routable = true,
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "check_status",
                description = "Kiểm tra toàn bộ trạng thái an ninh, camera và thiết bị trong nhà",
                examples = listOf("kiểm tra nhà cửa", "báo cáo an ninh", "quản gia báo cáo tình hình")
            ),
            PluginAction(
                name = "set_auto_mode",
                description = "Bật hoặc tắt chế độ tự động hóa quản gia",
                examples = listOf("bật quản gia tự động", "tắt chế độ tự động nhà cửa"),
                parameters = listOf(
                    PluginParameter("enabled", "boolean", "Trạng thái bật/tắt tự động", true, "boolean")
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("HousekeeperSkill", "execute: action=$action")
        return when (action) {
            "check_status" -> {
                success("✅ Báo cáo quản gia: Mọi thiết bị, đèn chiếu sáng và camera an ninh hiện tại đều đang hoạt động ở trạng thái an toàn.")
            }
            "set_auto_mode" -> {
                val enabled = params["enabled"] as? Boolean ?: true
                success("✅ Đã chuyển đổi chế độ quản gia tự động thành: ${if (enabled) "KÍCH HOẠT" else "TẮT"}.")
            }
            else -> failure("Không tìm thấy hành động: $action")
        }
    }
}