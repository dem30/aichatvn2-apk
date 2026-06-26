package com.aichatvn.agent.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import android.app.PendingIntent
import android.content.Intent

@Singleton
class NotificationSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: Logger
) : BaseSkill("notification", "Gửi thông báo", logger), Plugin {

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationCounter = AtomicInteger(1001)

    override suspend fun initialize() {
        createNotificationChannel()
    }

    override suspend fun shutdown() {}

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "send",
                description = "Gửi thông báo",
                parameters = listOf(
                    PluginParameter("title", "string", "Tiêu đề", true),
                    PluginParameter("message", "string", "Nội dung", true)
                )
            )
        )
    }

    override fun getQATriggers(): Map<String, List<String>> = mapOf(
    "send" to listOf("gửi thông báo", "báo cho tôi", "gửi cảnh báo", "thông báo cho tôi")
)
    
    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            else -> AgentKernel.PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleSend(params: Map<String, Any>): AgentKernel.PluginResult {
        val title = params["title"] as? String ?: return AgentKernel.PluginResult.Failure("Thiếu title")
        val message = params["message"] as? String ?: return AgentKernel.PluginResult.Failure("Thiếu message")

        val id = sendNotification(title, message)

        return AgentKernel.PluginResult.Success(
            mapOf("notificationId" to id, "status" to "sent")
        )
    }

    suspend fun sendNotification(
        title: String,
        message: String,
        channelId: String = CHANNEL_ID
    ): Int = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                createNotificationChannel()
            }
        }

        val id = notificationCounter.getAndIncrement()

        // ✅ Thêm: tap notification → mở MainActivity
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)  // ✅ Thêm dòng này
            .build()

        notificationManager.notify(id, notification)
        logger.i("NotificationSkill", "📢 NOTIFICATION POSTED | ID=$id | Title: $title")
        id
    }

    // Thêm hàm định nghĩa Notification Channel để giải quyết lỗi biên dịch
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "aichatvn_alerts"
        private const val CHANNEL_NAME = "Cảnh báo an ninh"
        private const val CHANNEL_DESCRIPTION = "Thông báo từ AI Chat VN"
    }
}