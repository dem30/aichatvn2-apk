package com.aichatvn.agent.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.skills.base.BaseAgentSkill
import dagger.hilt.android.qualifiers.ApplicationContext
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
    
    override suspend fun initialize() {
        createNotificationChannel()
    }
    
    override suspend fun shutdown() {
        // Nothing to do
    }
    
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
    
    suspend fun sendNotification(title: String, message: String, channelId: String = CHANNEL_ID) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    companion object {
        private const val CHANNEL_ID = "aichatvn_alerts"
        private const val CHANNEL_NAME = "Cảnh báo an ninh"
        private const val CHANNEL_DESCRIPTION = "Thông báo cảnh báo từ camera giám sát"
        private const val NOTIFICATION_ID = 1001
    }
}