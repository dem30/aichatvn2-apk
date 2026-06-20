package com.aichatvn.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        // ✅ Đã bỏ preload SmolLM2 Router cục bộ (LocalRouterEngine) — gây crash SIGILL
        // trên CPU yếu/cũ (Cortex-A53...). Routing giờ qua Groq (xem AgentKernel.tryDeviceCommand).
        logger.d("MainApplication", "App khởi động")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}