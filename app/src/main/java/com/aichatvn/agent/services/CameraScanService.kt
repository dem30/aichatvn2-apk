package com.aichatvn.agent.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aichatvn.agent.MainActivity
import com.aichatvn.agent.R
import com.aichatvn.agent.core.camera.CameraEngine
import com.aichatvn.agent.core.camera.EngineFactory
import com.aichatvn.agent.core.camera.EngineStatus
import com.aichatvn.agent.core.camera.EngineType
import com.aichatvn.agent.core.heartbeat.ServiceHeartbeat  // ✅ THÊM
import com.aichatvn.agent.core.processor.CameraProcessor
import com.aichatvn.agent.core.telemetry.TelemetryManager
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class CameraScanService : Service() {

    @Inject
    lateinit var engineFactory: EngineFactory

    @Inject
    lateinit var cameraProcessor: CameraProcessor

    @Inject
    lateinit var telemetryManager: TelemetryManager

    @Inject
    lateinit var logger: Logger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentEngine: CameraEngine? = null
    private var engineType: EngineType = EngineType.SNAPSHOT
    private var statusJob: Job? = null

    private val isStopping = AtomicBoolean(false)
    
    // ✅ Track notification text để tránh flicker
    private var lastNotificationText: String? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "camera_scan_channel"
        private const val CHANNEL_NAME = "Camera Scan Service"
        private const val STOP_TIMEOUT_MS = 3_000L
    }

    override fun onCreate() {
        super.onCreate()
        logger.i("CameraScanService", "Service created")
        
        // ✅ Đánh dấu service đang chạy
        ServiceHeartbeat.markRunning()
        
        createNotificationChannel()
        telemetryManager.start()

        startForeground(NOTIFICATION_ID, createNotification("Đang khởi tạo..."))
        lastNotificationText = "Đang khởi tạo..."
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.i("CameraScanService", "Service started, flags=$flags, startId=$startId")
        
        // ✅ Chỉ update notification nếu text khác
        val initText = "Đang khởi tạo..."
        if (lastNotificationText != initText) {
            startForeground(NOTIFICATION_ID, createNotification(initText))
            lastNotificationText = initText
        }

        isStopping.set(false)

        if (currentEngine == null) {
            startEngine()
        }

        return START_STICKY
    }

    private fun startEngine() {
        serviceScope.launch {
            try {
                // ✅ Check camera permission trước khi start
                if (!hasCameraPermission()) {
                    logger.w("CameraScanService", "Camera permission not granted, waiting...")
                    updateNotification("⏳ Đợi cấp quyền camera...")
                    return@launch
                }
                
                currentEngine = engineFactory.create(engineType)
                val engine = currentEngine ?: return@launch

                cameraProcessor.attach(engine.frameFlow)

                statusJob?.cancel()
                statusJob = serviceScope.launch {
                    engine.status.collect { status ->
                        val statusText = when (status) {
                            is EngineStatus.Running -> "✅ Đang giám sát..."
                            is EngineStatus.Starting -> "⏳ Đang khởi động..."
                            is EngineStatus.Stopping -> "⏹️ Đang dừng..."
                            is EngineStatus.Idle -> "⏸️ Tạm dừng"
                            is EngineStatus.Error -> "❌ Lỗi: ${status.message.take(30)}"
                            is EngineStatus.Reconnecting -> "🔄 Đang kết nối lại (lần ${status.attempt})"
                        }
                        updateNotification(statusText)
                    }
                }

                logger.i("CameraScanService", "Starting engine: ${engine::class.simpleName}")
                engine.start()

            } catch (e: Exception) {
                logger.e("CameraScanService", "Failed to start engine: ${e.message}", e)
                updateNotification("❌ Lỗi khởi động: ${e.message?.take(30)}")
            }
        }
    }

    // ✅ Gọi updateHeartbeat mỗi khi scan thành công
    private suspend fun updateHeartbeat() {
        try {
            ServiceHeartbeat.updateHeartbeat(applicationContext)
        } catch (e: Exception) {
            logger.e("CameraScanService", "Failed to update heartbeat: ${e.message}", e)
        }
    }

    private suspend fun stopEngineInternal() {
        if (!isStopping.compareAndSet(false, true)) {
            logger.d("CameraScanService", "Already stopping, skip")
            return
        }

        try {
            statusJob?.cancel()
            statusJob = null

            cameraProcessor.detach()

            currentEngine?.stop()
            currentEngine = null
            logger.i("CameraScanService", "Engine stopped")
        } catch (e: Exception) {
            logger.e("CameraScanService", "Error stopping engine: ${e.message}", e)
        } finally {
            isStopping.set(false)
        }
    }

    private fun stopEngine() {
        serviceScope.launch {
            stopEngineInternal()
        }
    }

    // ✅ Chỉ update notification khi text thay đổi
    private fun updateNotification(text: String) {
        if (text == lastNotificationText) {
            return  // ✅ Tránh flicker
        }
        lastNotificationText = text
        
        try {
            val notification = createNotification(text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logger.e("CameraScanService", "Failed to update notification: ${e.message}", e)
        }
    }

    private fun createNotification(contentText: String = "Đang giám sát..."): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📷 AIChatVN2 - Mắt robot")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị trạng thái mắt robot"
                setShowBadge(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    // ✅ Check camera permission
    private fun hasCameraPermission(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        logger.i("CameraScanService", "Service destroying")
        
        // ✅ Đánh dấu service đã dừng
        ServiceHeartbeat.markStopped()
        
        // ✅ Reset notification flag
        lastNotificationText = null

        // ✅ Stop engine với timeout
        runBlocking(Dispatchers.IO) {
            val completed = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                stopEngineInternal()
                true
            }
            if (completed == null) {
                logger.w("CameraScanService", "stopEngineInternal timed out after ${STOP_TIMEOUT_MS}ms, forcing teardown")
            }
        }

        telemetryManager.stop()
        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}