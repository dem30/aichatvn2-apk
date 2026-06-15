package com.aichatvn.agent.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.skills.base.BaseAgentSkill
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : BaseAgentSkill {

    override val skillName = "NotificationSkill"

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ID tăng dần — mỗi thông báo có ID riêng, không bị ghi đè
    private val notificationCounter = AtomicInteger(1001)

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

    /**
     * Gửi thông báo với ID tự động tăng dần.
     * Mỗi lần gọi tạo một notification mới, không ghi đè cái trước.
     * Trả về notificationId đã dùng (để caller cancel nếu cần).
     */
    suspend fun sendNotification(
        title: String,
        message: String,
        channelId: String = CHANNEL_ID
    ): Int {
        val id = notificationCounter.getAndIncrement()
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
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
