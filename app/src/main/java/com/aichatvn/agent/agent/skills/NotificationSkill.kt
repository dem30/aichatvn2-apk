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
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
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

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(notification = true), // Tuyên bố năng lực đẩy thông báo
        routable = true,
        visibleOnDashboard = false,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "send",
                description = "Gửi thông báo đẩy hiển thị trên màn hình thiết bị",
                examples = listOf("gửi thông báo", "gửi cảnh báo"),
                tags = listOf("notification", "alert", "message"),
                parameters = listOf(
                    PluginParameter("title", "string", "Tiêu đề thông báo", true, "string"),
                    PluginParameter("message", "string", "Nội dung chi tiết thông báo", true, "string")
                )
            )
        )
    )

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationCounter = AtomicInteger(1001)

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        createNotificationChannel()
    }

    override suspend fun shutdown() {}

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            else -> AgentKernel.PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleSend(params: Map<String, Any>): AgentKernel.PluginResult {
        val title = params["title"] as? String 
            ?: return AgentKernel.PluginResult.Failure("Tiêu đề thông báo là gì vậy bạn?")
        val message = params["message"] as? String 
            ?: return AgentKernel.PluginResult.Failure("Nội dung thông báo bạn muốn gửi là gì?")

        val id = sendNotification(title, message)

        return AgentKernel.PluginResult.Success(
            mapOf("notificationId" to id, "status" to "sent")
        )
    }

    suspend fun sendNotification(
        title: String,
        message: String,
        channelId: String = CHANNEL_ID,
        // ✅ MỚI: Cho phép nơi gọi (vd CameraSkill) truyền ID ỔN ĐỊNH tính từ alertId, thay vì
        // luôn dùng notificationCounter nội bộ. Nhờ vậy 1 AlertEntity ↔ 1 notification là quan
        // hệ 1-1 xác định được (dùng notificationIdForAlert() bên dưới), cho phép huỷ đúng
        // notification khi alert được đánh dấu đã đọc/xoá (xem cancelNotification()).
        // Nếu không truyền, giữ hành vi cũ (tự tăng số) cho các lời gọi chung chung khác
        // (vd AI tự gọi action "send" của skill này).
        notificationId: Int? = null,
        // ✅ MỚI: Route điều hướng (theo cú pháp NavController.navigate()) để mở ĐÚNG màn hình
        // liên quan khi người dùng bấm vào notification — trước đây luôn dùng generic launch
        // intent nên bấm vào chỉ mở app về màn mặc định, mất hết ngữ cảnh "đây là cảnh báo của
        // camera nào". Cần MainActivity đọc extra DEEP_LINK_EXTRA này rồi gọi navController
        // tương ứng (phần đó cần xem MainActivity.kt để nối nốt).
        deepLinkRoute: String? = null
    ): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                createNotificationChannel()
            }
        }

        val id = notificationId ?: notificationCounter.getAndIncrement()

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (deepLinkRoute != null) {
                    putExtra(DEEP_LINK_EXTRA, deepLinkRoute)
                }
            }

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

    // ✅ MỚI: Huỷ notification hệ thống ứng với 1 alert cụ thể — gọi khi AlertHistoryViewModel
    // đánh dấu đã đọc hoặc xoá alert đó, để notification không còn nằm ì trên thanh trạng thái
    // sau khi Admin đã xử lý xong trong app.
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
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

        // ✅ MỚI: Key extra để MainActivity đọc route điều hướng khi app được mở từ notification
        // (xem sendNotification() phía trên).
        const val DEEP_LINK_EXTRA = "deep_link_route"

        // ✅ MỚI: Hàm DÙNG CHUNG để tính notification ID từ alertId (UUID) — đảm bảo CameraSkill
        // (lúc gửi) và AlertHistoryViewModel (lúc huỷ) luôn tính ra CÙNG 1 con số cho cùng 1
        // alert, mà không cần lưu thêm cột nào trong DB. Notification ID của Android là Int nên
        // dùng hashCode() của UUID string.
        fun notificationIdForAlert(alertId: String): Int = alertId.hashCode()
    }
}