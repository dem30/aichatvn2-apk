package com.aichatvn.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.scheduler.TaskScheduler
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

    // Sử dụng CoroutineScope ở cấp độ ứng dụng để tránh nghẽn luồng Main
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        logger.d("MainApplication", "App khởi động - Khởi tạo plugins")

        // Khởi tạo các plugins bất đồng bộ (Asynchronously)
        initializePlugins()

        TaskScheduler.ensureRunning(this)
    }

    private fun initializePlugins() {
        applicationScope.launch {
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