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

    override val routable: Boolean = true
    override val visibleOnDashboard: Boolean = false
    override val autoGenerateQA: Boolean = true

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationCounter = AtomicInteger(1001)

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        createNotificationChannel()
    }

    override suspend fun shutdown() {}

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "send",
                description = "Gửi thông báo đẩy hiển thị trên màn hình thiết bị",
                examples = listOf("gửi thông báo"),
                aliases = listOf("gửi thông báo", "cảnh báo", "báo tin"),
                tags = listOf("notification", "alert", "message"),
                parameters = listOf(
                    PluginParameter("title", "string", "Tiêu đề thông báo", true, "string"),
                    PluginParameter("message", "string", "Nội dung chi tiết thông báo", true, "string")
                )
            )
        )
    }

    // RÚT GỌN TỐI ƯU: Chỉ giữ lại 2 từ khóa kích hoạt nguyên bản và khái quát nhất
    override fun getQATriggers(): Map<String, List<String>> = mapOf(
        "send" to listOf("gửi thông báo", "gửi cảnh báo")
    )
    
    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            else -> AgentKernel.PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleSend(params: Map<String, Any>): AgentKernel.PluginResult {
        // Cập nhật thông điệp lỗi tự nhiên phục vụ slot filling
        val title = params["title"] as? String 
            ?: return AgentKernel.PluginResult.Failure("Tiêu đề thông báo là gì vậy bạn?")
        val message = params["message"] as? String 
            ?: return AgentKernel.PluginResult.Failure("Nội dung thông báo bạn muốn gửi là gì?")

        val id = sendNotification(title, message)

        return AgentKernel.PluginResult.Success(
            mapOf("notificationId" to id, "status" to "sent")
        )
    }

    // Di chuyển toàn bộ tiến trình phân tích PackageManager và dựng Builder sang Dispatchers.IO để giải phóng luồng chính
    suspend fun sendNotification(
        title: String,
        message: String,
        channelId: String = CHANNEL_ID
    ): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                createNotificationChannel()
            }
        }

        val id = notificationCounter.getAndIncrement()

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
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(id, notification)
        logger.i("NotificationSkill", "📢 NOTIFICATION POSTED | ID=$id | Title: $title")
        id
    }

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