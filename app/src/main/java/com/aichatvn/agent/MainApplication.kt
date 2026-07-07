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
import com.thingclips.smart.home.sdk.ThingHomeSdk // ✅ BỔ SUNG: Import thư viện Thing Smart Home SDK
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
        logger.d("MainApplication", "App khởi động - Khởi tạo plugins và Thing Smart SDK")

        // ===== ✅ BỔ SUNG: Khởi tạo Thing Smart SDK =====
        try {
            // Cho phép hiển thị log hệ thống của SDK khi ở chế độ gỡ lỗi (Debug) để tiện điều tra
            ThingHomeSdk.setDebugMode(BuildConfig.DEBUG)
            // Kích hoạt SDK
            ThingHomeSdk.init(this)
            logger.i("MainApplication", "🔑 Thing Smart Life App SDK initialized successfully")
        } catch (e: Exception) {
            logger.e("MainApplication", "❌ Failed to initialize Thing Smart Life App SDK", e)
        }

        initializePlugins()

        TaskScheduler.ensureRunning(this)

        // ✅ KHÔNG ĐỔI: Tự động khởi chạy WebhookGatewayService
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

        // ✅ KHÔNG ĐỔI: Khởi chạy vòng lặp hands-free (mic) ngầm độc lập
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
                "⚠️ Chưa có quyền RECORD_AUDIO -> bỏ qua khởi động VoiceAssistantService lần này."
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