package com.aichatvn.agent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.VoiceAssistantManager
import com.aichatvn.agent.utils.VoiceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ✅ Foreground Service riêng cho vòng lặp hands-free (nghe -> AI -> nói -> nghe lại), sống
 * độc lập với ChatScreen/Inbox (xem MainApplication.kt).
 *
 * ✅ ĐÃ SỬA (bug crash "chạm tab nào cũng văng app"): Android 14+ bắt buộc phải CÓ quyền
 * RECORD_AUDIO ngay tại thời điểm gọi startForeground() với foregroundServiceType="microphone".
 * Máy mới cài app chưa cấp quyền này -> gọi thẳng sẽ bị hệ thống ném
 * SecurityException/ForegroundServiceStartNotAllowedException làm CHẾT CẢ TIẾN TRÌNH.
 * Giờ Service tự kiểm tra quyền trước, và toàn bộ onCreate() được bọc try/catch — lỗi bất kỳ
 * chỉ làm Service tự dừng (stopSelf), không còn crash cả app.
 *
 * ✅ ĐÃ SỬA (không tắt được mic từ thông báo): thông báo trước đây là dòng chữ TĨNH, không có
 * nút bấm nào. Giờ có nút "Tắt mic"/"Bật mic" ngay trên thông báo, và nội dung cập nhật theo
 * đúng trạng thái thực tế của VoiceAssistantManager (đang nghe / đang xử lý / đang nói / đã tắt).
 */
@AndroidEntryPoint
class VoiceAssistantService : Service() {

    @Inject
    lateinit var voiceManager: VoiceAssistantManager

    @Inject
    lateinit var logger: Logger

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var lastNotificationText = ""

    companion object {
        private const val CHANNEL_ID = "VoiceAssistantServiceChannel"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_STOP_MIC = "com.aichatvn.agent.action.STOP_MIC"
        const val ACTION_START_MIC = "com.aichatvn.agent.action.START_MIC"
    }

    override fun onCreate() {
        super.onCreate()

        if (!hasRecordAudioPermission()) {
            logger.w(
                "VoiceAssistantService",
                "⚠️ Chưa có quyền RECORD_AUDIO -> không khởi động vòng lặp hands-free, tự dừng Service an toàn (không crash app)."
            )
            stopSelf()
            return
        }

        try {
            createNotificationChannel()
            startForegroundService()

            // Phòng hờ: gỡ khóa destroyed nếu Singleton từng bị destroy() ở phiên trước
            voiceManager.reactivate()
            // ✅ ĐÃ SỬA: trước đây gọi thẳng startListening() vô điều kiện mỗi khi Service được
            // tạo (kể cả khi hệ điều hành tự hồi sinh Service do START_STICKY, không phải do
            // người dùng mở app) — khiến mic tự bật lại dù người dùng đã tắt tay từ trước.
            // Giờ startListening() tự bị chặn bên trong nếu voiceManager.micEnabled == false
            // (xem VoiceAssistantManager.canStartListening()), nên gọi ở đây vẫn AN TOÀN và
            // không cần if/else — chỉ còn tác dụng khi mic thực sự đang được phép bật.
            voiceManager.startListening()
            observeVoiceState()

            logger.i(
                "VoiceAssistantService",
                "🎤 Vòng lặp hands-free đã khởi động — độc lập với ChatScreen/Inbox"
            )
        } catch (e: Exception) {
            // ✅ ĐÃ THÊM: không để lỗi bất kỳ (thiếu quyền notification, lỗi hệ thống...) làm
            // crash cả tiến trình app — chỉ log rồi tự dừng riêng Service này.
            logger.e("VoiceAssistantService", "❌ Không thể khởi động vòng lặp hands-free, tự dừng Service", e)
            stopSelf()
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ✅ ĐÃ THÊM: đồng bộ nội dung thông báo theo trạng thái thực tế của mic
    private fun observeVoiceState() {
        serviceScope.launch {
            voiceManager.voiceState.collect { state ->
                val text = when (state) {
                    VoiceState.LISTENING -> "Đang lắng nghe..."
                    VoiceState.PROCESSING -> "Đang xử lý câu lệnh..."
                    VoiceState.SPEAKING -> "Đang trả lời..."
                    VoiceState.RESTARTING -> "Đang khởi động lại mic..."
                    VoiceState.IDLE -> "Mic đã tắt"
                }
                updateNotification(text, listening = state != VoiceState.IDLE)
            }
        }
    }

    private fun startForegroundService() {
        val notification = buildNotification("Trợ lý giọng nói đang khởi động...", listening = true)
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

    private fun updateNotification(contentText: String, listening: Boolean) {
        if (contentText == lastNotificationText) return
        lastNotificationText = contentText
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText, listening))
    }

    // ✅ ĐÃ THÊM: nút "Tắt mic"/"Bật mic" ngay trên thông báo — trước đây không có cách nào
    // tắt mic ngoài việc mở app lên bấm nút trong ChatScreen.
    private fun buildNotification(contentText: String, listening: Boolean): Notification {
        val toggleIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = if (listening) ACTION_STOP_MIC else ACTION_START_MIC
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (listening) "Tắt mic" else "Bật mic"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 Hands-free")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_lock_silent_mode, toggleLabel, togglePendingIntent)
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
        when (intent?.action) {
            ACTION_STOP_MIC -> {
                // ✅ ĐÃ SỬA: dùng setMicEnabled(false) thay vì stopListening() trực tiếp — để lựa
                // chọn này được LƯU BỀN, không bị quên khi Service/tiến trình bị hồi sinh sau đó.
                voiceManager.setMicEnabled(false)
                updateNotification("Mic đã tắt", listening = false)
                logger.i("VoiceAssistantService", "🔇 Người dùng đã tắt mic từ thông báo.")
            }
            ACTION_START_MIC -> {
                voiceManager.reactivate()
                voiceManager.setMicEnabled(true)
                logger.i("VoiceAssistantService", "🎤 Người dùng đã bật lại mic từ thông báo.")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        voiceManager.stopListening()
        logger.i("VoiceAssistantService", "Dịch vụ hands-free đã tắt.")
    }
}