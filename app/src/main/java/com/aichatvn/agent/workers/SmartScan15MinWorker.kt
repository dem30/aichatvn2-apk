package com.aichatvn.agent.workers

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aichatvn.agent.core.heartbeat.ServiceHeartbeat
import com.aichatvn.agent.services.CameraScanService
import com.aichatvn.agent.utils.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class SmartScan15MinWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val logger: Logger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val lastHeartbeat = ServiceHeartbeat.getLastHeartbeat(applicationContext)
                val now = System.currentTimeMillis()
                val isServiceRunning = ServiceHeartbeat.isRunning

                val heartbeatAge = now - lastHeartbeat
                if (isServiceRunning && heartbeatAge < 120_000L) {
                    logger.d("SmartScan15MinWorker", "Service is running (heartbeat: ${heartbeatAge}ms ago)")
                    return@withContext Result.success()
                }

                logger.w("SmartScan15MinWorker", "Service appears dead (heartbeat: ${heartbeatAge}ms ago), restarting...")
                restartService()

                Result.success()
            } catch (e: Exception) {
                logger.e("SmartScan15MinWorker", "Watchdog error: ${e.message}", e)
                Result.retry()
            }
        }
    }

    private fun restartService() {
        try {
            val intent = Intent(applicationContext, CameraScanService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }

            logger.i("SmartScan15MinWorker", "Service restart triggered")
        } catch (e: Exception) {
            logger.e("SmartScan15MinWorker", "Failed to restart service: ${e.message}", e)
        }
    }
}