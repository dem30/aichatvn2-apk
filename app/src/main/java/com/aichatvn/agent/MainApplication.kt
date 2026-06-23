package com.aichatvn.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.scheduler.TaskScheduler
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>   // ← Thêm dòng này

    override fun onCreate() {
        super.onCreate()
        logger.d("MainApplication", "App khởi động - Khởi tạo plugins")

        // Khởi tạo tất cả plugins (NotificationSkill, CameraSkill, ...)
        initializePlugins()

        // ✅ TaskScheduler vẫn giữ nguyên
        TaskScheduler.ensureRunning(this)
    }

    /**
     * Khởi tạo tất cả plugin một lần khi app start
     */
    private fun initializePlugins() {
        runBlocking {
            plugins.forEach { plugin ->
                try {
                    plugin.initialize()
                    logger.i("MainApplication", "✅ Initialized plugin: ${plugin.id}")
                } catch (e: Exception) {
                    logger.e("MainApplication", "❌ Failed to initialize ${plugin.id}", e)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}