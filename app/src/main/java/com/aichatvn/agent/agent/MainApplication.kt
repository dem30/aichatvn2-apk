package com.aichatvn.agent

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.QAInitBuilder
import com.aichatvn.agent.scheduler.TaskScheduler
import com.aichatvn.agent.service.WebhookGatewayService
import com.aichatvn.agent.service.VoiceAssistantService
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>

    @Inject
    lateinit var qaInitBuilder: QAInitBuilder

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        logger.d("MainApplication", "App khởi động - Khởi tạo plugins")

        initializePlugins()

        TaskScheduler.ensureRunning(this)

        // ✅ ĐÃ SỬA: Tự động khởi chạy WebhookGatewayService khi mở ứng dụng
        try {
            val serviceIntent = Intent(this, WebhookGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            logger.i("MainApplication", "🚀 WebhookGatewayService started successfully on App Launch")
        } catch (e: Exception) {
            logger.e("MainApplication", "❌ Failed to start WebhookGatewayService on App Launch", e)
        }

        // ✅ ĐÃ THÊM: Khởi chạy vòng lặp hands-free (mic) ngầm, độc lập với ChatScreen/Inbox —
        // để người dùng hạn chế vận động ra lệnh thoại được bất cứ lúc nào, kể cả màn hình tắt.
        // ✅ ĐÃ SỬA: kiểm tra quyền RECORD_AUDIO trước — máy mới cài chưa cấp quyền này, gọi
        // thẳng startForegroundService(type=microphone) sẽ bị hệ thống crash cả app (Android 14+).
        // Service tự nó cũng tự kiểm tra lại quyền này (double-check), nhưng chặn sớm ở đây để
        // tránh tốn 1 lượt gọi service vô ích khi biết chắc sẽ bị từ chối.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val voiceServiceIntent = Intent(this, VoiceAssistantService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(voiceServiceIntent)
                } else {
                    startService(voiceServiceIntent)
                }
                logger.i("MainApplication", "🎤 VoiceAssistantService started successfully on App Launch")
            } catch (e: Exception) {
                logger.e("MainApplication", "❌ Failed to start VoiceAssistantService on App Launch", e)
            }
        } else {
            logger.w(
                "MainApplication",
                "⚠️ Chưa có quyền RECORD_AUDIO -> bỏ qua khởi động VoiceAssistantService lần này. " +
                    "Cần gọi lại startForegroundService(VoiceAssistantService) sau khi người dùng cấp quyền mic."
            )
        }
    }

    private fun initializePlugins() {
        applicationScope.launch {
            plugins.forEach { plugin ->
                try {
                    plugin.initialize()
                    logger.i("MainApplication", "✅ Initialized plugin: ${plugin.manifest.id}")
                } catch (e: Exception) {
                    logger.e("MainApplication", "❌ Failed to initialize ${plugin.manifest.id}", e)
                }
            }
            try {
                qaInitBuilder.buildInitialQA()
            } catch (e: Exception) {
                logger.e("MainApplication", "❌ Failed to build initial QA", e)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}