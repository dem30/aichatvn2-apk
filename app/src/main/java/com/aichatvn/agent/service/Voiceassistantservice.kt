package com.aichatvn.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.VoiceAssistantManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ✅ MỚI: Foreground Service riêng cho vòng lặp hands-free (nghe -> AI -> nói -> nghe lại).
 *
 * TRƯỚC ĐÂY: vòng lặp này được ChatViewModel.init{}/onForeground()/onBackground() điều khiển,
 * nghĩa là nó sống và chết theo vòng đời của ChatScreen (Compose). Rời màn hình, tắt màn hình,
 * hoặc quay lại Inbox là mic mất tác dụng ngay — đi ngược lại mục tiêu "hands-free cho người
 * dùng hạn chế vận động" của toàn bộ app.
 *
 * BÂY GIỜ: VoiceAssistantManager là @Singleton, nên Service này chỉ cần start nó 1 lần khi
 * app khởi chạy (xem MainApplication.kt) — giống hệt cách WebhookGatewayService đã làm cho
 * phần Gateway/Webhook. Người dùng có thể ra lệnh thoại bất cứ lúc nào, kể cả khi app đang ở
 * nền hoặc màn hình đang tắt, không phụ thuộc ChatScreen có đang mở hay không.
 *
 * Nút Mic thủ công trên ChatScreen (toggleVoiceMode) vẫn hoạt động bình thường — nó gọi trực
 * tiếp lên VoiceAssistantManager (cùng 1 Singleton instance với Service này), chỉ là hành động
 * TẮT/MỞ tạm thời do người dùng chủ động bấm, không phải tự động theo vòng đời màn hình.
 */
@AndroidEntryPoint
class VoiceAssistantService : Service() {

    @Inject
    lateinit var voiceManager: VoiceAssistantManager

    @Inject
    lateinit var logger: Logger

    companion object {
        private const val CHANNEL_ID = "VoiceAssistantServiceChannel"
        private const val NOTIFICATION_ID = 1003
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForegroundService()

        try {
            // Phòng hờ: gỡ khóa destroyed nếu Singleton từng bị destroy() ở phiên trước
            voiceManager.reactivate()
            voiceManager.startListening()
            logger.i(
                "VoiceAssistantService",
                "🎤 Vòng lặp hands-free đã khởi động — độc lập với ChatScreen/Inbox"
            )
        } catch (e: Exception) {
            logger.e("VoiceAssistantService", "❌ Không thể khởi động vòng lặp hands-free", e)
        }
    }

    private fun startForegroundService() {
        val notification = buildNotification("Trợ lý giọng nói đang lắng nghe...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Hands-free")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Assistant Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
        logger.i("VoiceAssistantService", "Dịch vụ hands-free đã tắt.")
    }
}