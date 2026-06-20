package com.aichatvn.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.core.LocalRouterEngine
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var localRouterEngine: LocalRouterEngine

    @Inject
    lateinit var logger: Logger

    private val appScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        logger.d(
            "MainApplication",
            "App khởi động -> preload SmolLM Router"
        )

        try {
            localRouterEngine.prefetchModelAsync(appScope)
        } catch (e: Exception) {
            logger.e(
                "MainApplication",
                "Lỗi preload model",
                e
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}