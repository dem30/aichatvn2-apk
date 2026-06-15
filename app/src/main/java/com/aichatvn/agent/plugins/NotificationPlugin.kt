package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginContext
import com.aichatvn.agent.core.plugin.PluginEvent
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginResult
import com.aichatvn.agent.core.plugin.Subscription
import com.aichatvn.agent.skills.NotificationSkill
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPlugin @Inject constructor(
    private val notificationSkill: NotificationSkill
) : Plugin {
    
    override val manifest = PluginManifest(
        id = "notification",
        name = "Notification Plugin",
        version = "1.0.0",
        description = "Gửi thông báo push trên điện thoại",
        author = "AIChatVN2",
        keywords = listOf("thông báo", "notify", "notification", "báo", "alert"),
        actions = listOf(
            PluginAction(
                name = "send",
                description = "Gửi thông báo",
                keywords = listOf("gửi", "send"),
                parameters = listOf(
                    PluginParameter("title", "string", "Tiêu đề thông báo", true),
                    PluginParameter("message", "string", "Nội dung thông báo", true)
                )
            )
        ),
        subscribes = listOf("ALERT_DETECTED", "CAMERA_OFFLINE", "CAMERA_ONLINE", "EMAIL_SENT", "EMAIL_FAILED"),
        publishes = listOf("NOTIFICATION_SENT")
    )
    
    private lateinit var eventBus: com.aichatvn.agent.core.plugin.PluginEventBus
    private lateinit var context: PluginContext
    
    private var alertSubscription: Subscription? = null
    private var offlineSubscription: Subscription? = null
    private var onlineSubscription: Subscription? = null
    private var emailSentSubscription: Subscription? = null
    private var emailFailedSubscription: Subscription? = null
    
    override suspend fun initialize(context: PluginContext) {
        this.context = context
        this.eventBus = context.eventBus
        notificationSkill.initialize()
        
        // Subscribe vào các event từ camera
        alertSubscription = context.eventBus.subscribe("ALERT_DETECTED") { event ->
            val cameraId = event.payload["cameraId"] as? String
            val message = event.payload["message"] as? String
            if (cameraId != null && message != null) {
                val notificationId = notificationSkill.sendNotification(
                    title = "🚨 Cảnh báo từ camera $cameraId",
                    message = message.take(100)
                )
                publishNotificationResult(notificationId, "ALERT_DETECTED")
            }
        }
        
        offlineSubscription = context.eventBus.subscribe("CAMERA_OFFLINE") { event ->
            val cameraId = event.payload["cameraId"] as? String
            if (cameraId != null) {
                val notificationId = notificationSkill.sendNotification(
                    title = "📷 Camera mất kết nối",
                    message = "Camera $cameraId đã offline. Vui lòng kiểm tra!"
                )
                publishNotificationResult(notificationId, "CAMERA_OFFLINE")
            }
        }
        
        onlineSubscription = context.eventBus.subscribe("CAMERA_ONLINE") { event ->
            val cameraId = event.payload["cameraId"] as? String
            if (cameraId != null) {
                val notificationId = notificationSkill.sendNotification(
                    title = "📷 Camera kết nối lại",
                    message = "Camera $cameraId đã online trở lại."
                )
                publishNotificationResult(notificationId, "CAMERA_ONLINE")
            }
        }
        
        // ✅ THÊM: Subscribe vào EMAIL_SENT event
        emailSentSubscription = context.eventBus.subscribe("EMAIL_SENT") { event ->
            val to = event.payload["to"] as? String
            val subject = event.payload["subject"] as? String
            if (to != null) {
                val notificationId = notificationSkill.sendNotification(
                    title = "📧 Email đã gửi",
                    message = "Email ${subject?.let { "'$it' " } ?: ""}đã được gửi tới $to"
                )
                publishNotificationResult(notificationId, "EMAIL_SENT")
            }
        }
        
        // ✅ THÊM: Subscribe vào EMAIL_FAILED event
        emailFailedSubscription = context.eventBus.subscribe("EMAIL_FAILED") { event ->
            val to = event.payload["to"] as? String
            val error = event.payload["error"] as? String
            val notificationId = notificationSkill.sendNotification(
                title = "❌ Gửi email thất bại",
                message = "Không thể gửi email tới $to${error?.let { ": $it" } ?: ""}"
            )
            publishNotificationResult(notificationId, "EMAIL_FAILED")
        }
    }
    
    private suspend fun publishNotificationResult(notificationId: Int, sourceEvent: String) {
        eventBus.publish(PluginEvent(
            type = "NOTIFICATION_SENT",
            source = manifest.id,
            payload = mapOf(
                "notificationId" to notificationId,
                "sourceEvent" to sourceEvent
            )
        ))
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return try {
            when (action) {
                "send" -> {
                    val title = params["title"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("title"),
                            "Bạn muốn gửi thông báo với tiêu đề gì?"
                        )
                    val message = params["message"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("message"),
                            "Nội dung thông báo là gì?"
                        )
                    
                    val notificationId = notificationSkill.sendNotification(title, message)
                    
                    // Publish event khi gửi notification
                    eventBus.publish(PluginEvent(
                        type = "NOTIFICATION_SENT",
                        source = manifest.id,
                        payload = mapOf(
                            "notificationId" to notificationId,
                            "title" to title,
                            "message" to message
                        )
                    ))
                    
                    PluginResult.Success(
                        mapOf(
                            "notificationId" to notificationId,
                            "message" to "✅ Đã gửi thông báo: $title"
                        )
                    )
                }
                else -> PluginResult.Failure("Action không xác định: $action")
            }
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }
    
    override suspend fun onEvent(event: PluginEvent) {
        context.logger.d("NotificationPlugin", "onEvent called with: ${event.type}")
        // onEvent hiện đã được thay thế bằng các subscription riêng biệt
        // Phương thức này vẫn giữ để tương thích với interface Plugin
    }
    
    override suspend fun shutdown() {
        alertSubscription?.unsubscribe()
        offlineSubscription?.unsubscribe()
        onlineSubscription?.unsubscribe()
        emailSentSubscription?.unsubscribe()
        emailFailedSubscription?.unsubscribe()
        notificationSkill.shutdown()
    }
}