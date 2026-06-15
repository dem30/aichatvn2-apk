package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginContext
import com.aichatvn.agent.core.plugin.PluginEvent
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginResult
import com.aichatvn.agent.core.plugin.Subscription
import com.aichatvn.agent.skills.EmailSkill
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmailPlugin @Inject constructor(
    private val emailSkill: EmailSkill
) : Plugin {
    
    override val manifest = PluginManifest(
        id = "email",
        name = "Email Plugin",
        version = "1.0.0",
        description = "Gửi email qua Gmail",
        author = "AIChatVN2",
        keywords = listOf("email", "mail", "gửi email", "send email"),
        actions = listOf(
            PluginAction(
                name = "send",
                description = "Gửi email",
                keywords = listOf("gửi", "send"),
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email người nhận", true),
                    PluginParameter("subject", "string", "Tiêu đề email", true),
                    PluginParameter("body", "string", "Nội dung email", true)
                )
            ),
            PluginAction(
                name = "test",
                description = "Gửi email test",
                keywords = listOf("test", "kiểm tra"),
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email nhận test", true)
                )
            )
        ),
        subscribes = listOf("ALERT_DETECTED"),
        publishes = listOf("EMAIL_SENT", "EMAIL_FAILED")
    )
    
    private var alertSubscription: Subscription? = null
    private lateinit var eventBus: com.aichatvn.agent.core.plugin.PluginEventBus
    private lateinit var context: PluginContext
    
    override suspend fun initialize(context: PluginContext) {
        this.context = context
        this.eventBus = context.eventBus
        emailSkill.initialize()
        
        alertSubscription = context.eventBus.subscribe("ALERT_DETECTED") { event ->
            val cameraId = event.payload["cameraId"] as? String
            val message = event.payload["message"] as? String
            if (cameraId != null && message != null) {
                val result = emailSkill.sendEmail(
                    to = "admin@aichatvn.com",
                    subject = "🚨 ALERT: Camera $cameraId",
                    body = message,
                    imageBytes = null
                )
                
                // Publish kết quả gửi email
                if (result.success) {
                    eventBus.publish(PluginEvent(
                        type = "EMAIL_SENT",
                        source = manifest.id,
                        payload = mapOf("to" to "admin@aichatvn.com", "cameraId" to cameraId)
                    ))
                } else {
                    eventBus.publish(PluginEvent(
                        type = "EMAIL_FAILED",
                        source = manifest.id,
                        payload = mapOf("to" to "admin@aichatvn.com", "error" to (result.error ?: "Unknown"))
                    ))
                }
            }
        }
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return try {
            when (action) {
                "send" -> {
                    val to = params["to"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("to"),
                            "Bạn muốn gửi email tới địa chỉ nào?"
                        )
                    val subject = params["subject"] as? String ?: "Không có tiêu đề"
                    val body = params["body"] as? String ?: ""
                    val result = emailSkill.sendEmail(to, subject, body, null)
                    
                    if (result.success) {
                        // ✅ THÊM: Publish EMAIL_SENT event
                        eventBus.publish(PluginEvent(
                            type = "EMAIL_SENT",
                            source = manifest.id,
                            payload = mapOf("to" to to, "subject" to subject)
                        ))
                        PluginResult.Success(mapOf("message" to "✅ Email đã gửi tới $to"))
                    } else {
                        // ✅ THÊM: Publish EMAIL_FAILED event
                        eventBus.publish(PluginEvent(
                            type = "EMAIL_FAILED",
                            source = manifest.id,
                            payload = mapOf("to" to to, "error" to (result.error ?: "Unknown"))
                        ))
                        PluginResult.Failure(result.error ?: "Gửi email thất bại")
                    }
                }
                "test" -> {
                    val to = params["to"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("to"),
                            "Bạn muốn gửi email test tới địa chỉ nào?"
                        )
                    val result = emailSkill.sendEmail(
                        to, 
                        "Test từ AIChatVN2", 
                        "Email test gửi lúc ${System.currentTimeMillis()}", 
                        null
                    )
                    if (result.success) {
                        eventBus.publish(PluginEvent(
                            type = "EMAIL_SENT",
                            source = manifest.id,
                            payload = mapOf("to" to to, "test" to true)
                        ))
                        PluginResult.Success(mapOf("message" to "✅ Email test đã gửi tới $to"))
                    } else {
                        PluginResult.Failure(result.error ?: "Gửi email test thất bại")
                    }
                }
                else -> PluginResult.Failure("Action không xác định: $action")
            }
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }
    
    override suspend fun onEvent(event: PluginEvent) {
        context.logger.d("EmailPlugin", "Received event: ${event.type}")
        // Xử lý các event khác nếu cần
    }
    
    override suspend fun shutdown() {
        alertSubscription?.unsubscribe()
        emailSkill.shutdown()
    }
}