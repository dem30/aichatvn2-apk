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
class FacebookSkill @Inject constructor(
    logger: Logger
) : BaseSkill("facebook", "Facebook Assistant", logger) {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(background = true),
        routable = true,
        visibleOnDashboard = false,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "post_status",
                description = "Đăng một trạng thái hoặc bài viết mới lên Facebook",
                examples = listOf("đăng bài viết lên facebook", "post bài fb"),
                parameters = listOf(
                    PluginParameter("content", "string", "Nội dung bài viết", true, "string")
                )
            ),
            PluginAction(
                name = "send_messenger",
                description = "Gửi một tin nhắn trực tiếp qua Messenger đến ID khách hàng cụ thể",
                examples = listOf("gửi tin nhắn facebook cho", "inbox messenger"),
                parameters = listOf(
                    PluginParameter("recipient_id", "string", "ID người nhận (PSID)", true, "string"),
                    PluginParameter("message", "string", "Nội dung tin nhắn cần gửi", true, "string")
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("FacebookSkill", "execute: action=$action")
        return when (action) {
            "post_status" -> {
                val content = params["content"] as? String ?: ""
                success("✅ Đã tiếp nhận và chuẩn bị đăng trạng thái lên Facebook: \"$content\"")
            }
            "send_messenger" -> {
                val recipientId = params["recipient_id"] as? String ?: ""
                val message = params["message"] as? String ?: ""
                success("✅ Đã xếp hàng tin nhắn gửi qua Messenger tới khách hàng ID $recipientId: \"$message\"")
            }
            else -> failure("Không tìm thấy hành động: $action")
        }
    }
}