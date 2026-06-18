package com.aichatvn.agent.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction          // ✅ THÊM
import com.aichatvn.agent.core.plugin.PluginParameter       // ✅ THÊM
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: Logger
) : BaseSkill("notification", "Gửi thông báo", logger), Plugin {

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationCounter = AtomicInteger(1001)

    // ==================== PLUGIN IMPLEMENTATION ====================

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            else -> failure("Action không xác định: $action")
        }
    }

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "send",
                description = "Gửi thông báo",
                parameters = listOf(
                    PluginParameter("title", "string", "Tiêu đề thông báo", true),
                    PluginParameter("message", "string", "Nội dung thông báo", true)
                )
            )
        )
    }

    private suspend fun handleSend(params: Map<String, Any>): AgentKernel.PluginResult {
        val title = params["title"] as? String
            ?: return needMoreInfo(listOf("title"), "Bạn muốn gửi thông báo với tiêu đề gì?")
        
        val message = params["message"] as? String
            ?: return needMoreInfo(listOf("message"), "Nội dung thông báo là gì?")
        
        val notificationId = sendNotification(title, message)
        
        return success(
            message = "✅ Đã gửi thông báo: $title",
            data = mapOf("notificationId" to notificationId)
        )
    }

    // ==================== CORE SKILL METHODS ====================

    override suspend fun initialize() {
        createNotificationChannel()
    }

    override suspend fun shutdown() {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    suspend fun sendNotification(
        title: String,
        message: String,
        channelId: String = CHANNEL_ID
    ): Int {
        val id = notificationCounter.getAndIncrement()
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // ✅ SỬA
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
        return id
    }

    fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    companion object {
        private const val CHANNEL_ID = "aichatvn_alerts"
        private const val CHANNEL_NAME = "Cảnh báo an ninh"
        private const val CHANNEL_DESCRIPTION = "Thông báo cảnh báo từ camera giám sát"
    }
}