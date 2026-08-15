package com.aichatvn.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aichatvn.agent.R
import com.aichatvn.agent.devices.mqtt.MqttBrokerElectionManager
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * EmbeddedMqttBrokerService
 *
 * Foreground Service giữ Moquette broker sống khi role == HOSTING_EMBEDDED.
 * Bật/tắt theo BrokerElectionState.role — KHÔNG theo DEVICE_ROLE cứng.
 *
 * ⚠️ Phụ thuộc thư viện Moquette (io.moquette:moquette-broker).
 * Thêm vào app/build.gradle:
 *   implementation "io.moquette:moquette-broker:0.17"
 *
 * Nếu chưa thêm dependency: service vẫn chạy được nhưng broker không listen —
 * log cảnh báo rõ, không crash.
 */
@AndroidEntryPoint
class EmbeddedMqttBrokerService : Service() {

    @Inject lateinit var electionManager: MqttBrokerElectionManager
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var observeJob: Job? = null

    /** Moquette Server — dùng reflection để không bắt buộc compile-time dependency khi scaffold. */
    @Volatile private var brokerServer: Any? = null
    @Volatile private var isHosting = false

    companion object {
        private const val TAG = "EmbeddedMqttBroker"
        private const val CHANNEL_ID = "EmbeddedMqttBrokerChannel"
        private const val NOTIFICATION_ID = 1005

        fun start(context: Context) {
            val intent = Intent(context, EmbeddedMqttBrokerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedMqttBrokerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("MQTT Broker đang khởi động..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("MQTT Broker đang khởi động..."))
        }

        observeJob = scope.launch {
            electionManager.currentState.collectLatest { state ->
                when (state.role) {
                    MqttBrokerElectionManager.BrokerRole.HOSTING_EMBEDDED -> {
                        if (!isHosting) startBroker(state)
                    }
                    else -> {
                        // ✅ SỬA: trước đây chỉ dừng Moquette (stopBroker()) — KHÔNG có nơi nào
                        // gọi EmbeddedMqttBrokerService.stop() từ bên ngoài (chỉ có .start()
                        // được wiring gọi ở PATCHES_EventTriggerWiring.md), nên bản thân
                        // Foreground Service/notification treo vĩnh viễn sau lần start đầu
                        // tiên dù broker đã ngừng chạy. Service TỰ dừng chính nó khi role
                        // không còn là HOSTING_EMBEDDED — đây là điểm duy nhất biết chính xác
                        // lúc nào không còn cần Foreground Service nữa.
                        if (isHosting) stopBroker()
                        stopSelf()
                    }
                }
            }
        }
        logger.i(TAG, "Service created — quan sát BrokerRole")
    }

    private fun startBroker(state: MqttBrokerElectionManager.BrokerElectionState) {
        try {
            // Scaffold: thử load Moquette qua reflection.
            // Khi đã thêm dependency thật, có thể thay bằng:
            //   val mqttConfig = MemoryConfig(Properties().apply {
            //       setProperty("port", electionManager.embeddedBrokerPort.toString())
            //       setProperty("host", "0.0.0.0")
            //       setProperty("allow_anonymous", "true")
            //   })
            //   val server = Server(); server.startServer(mqttConfig); brokerServer = server
            val serverClass = Class.forName("io.moquette.broker.Server")
            val server = serverClass.getDeclaredConstructor().newInstance()
            val memoryConfigClass = Class.forName("io.moquette.broker.config.MemoryConfig")
            val props = java.util.Properties().apply {
                setProperty("port", electionManager.embeddedBrokerPort.toString())
                setProperty("host", "0.0.0.0")
                setProperty("allow_anonymous", "true")
                setProperty("persistence_enabled", "false")
            }
            val config = memoryConfigClass
                .getConstructor(java.util.Properties::class.java)
                .newInstance(props)
            val startMethod = serverClass.methods.firstOrNull {
                it.name == "startServer" && it.parameterCount == 1
            }
            startMethod?.invoke(server, config)
            brokerServer = server
            isHosting = true
            updateNotification(
                "MQTT Broker đang chạy (${state.brokerMode}) — port ${electionManager.embeddedBrokerPort}"
            )
            logger.i(TAG, "✅ Moquette started on port ${electionManager.embeddedBrokerPort} mode=${state.brokerMode}")
        } catch (e: ClassNotFoundException) {
            logger.e(
                TAG,
                "❌ Moquette chưa có trong classpath. Thêm: implementation \"io.moquette:moquette-broker:0.17\""
            )
            updateNotification("MQTT Broker: thiếu thư viện Moquette")
        } catch (e: Exception) {
            logger.e(TAG, "❌ Không start được Moquette: ${e.message}", e)
            updateNotification("MQTT Broker lỗi: ${e.message}")
        }
    }

    private fun stopBroker() {
        try {
            brokerServer?.let { server ->
                val stopMethod = server.javaClass.methods.firstOrNull {
                    it.name == "stopServer" && it.parameterCount == 0
                }
                stopMethod?.invoke(server)
            }
            logger.i(TAG, "🛑 Moquette stopped")
        } catch (e: Exception) {
            logger.e(TAG, "Lỗi stop Moquette: ${e.message}", e)
        } finally {
            brokerServer = null
            isHosting = false
            updateNotification("MQTT Broker đã dừng")
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIChatVN2 MQTT Broker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT Embedded Broker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        if (isHosting) stopBroker()
        scope.cancel()
        super.onDestroy()
        logger.i(TAG, "Service destroyed")
    }
}
