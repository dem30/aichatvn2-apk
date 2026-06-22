package com.aichatvn.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.scheduler.TaskScheduler
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
        logger.d("MainApplication", "App khởi động")

        // ✅ Khởi động TaskScheduler tại đây (Application.onCreate) chứ không phải
        // MainActivity — Application chỉ tạo 1 lần duy nhất trong vòng đời process,
        // còn Activity có thể bị recreate nhiều lần (xoay màn hình, back stack...).
        // ensureRunning() dùng KEEP policy: nếu worker đang chạy rồi thì không restart.
        TaskScheduler.ensureRunning(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}