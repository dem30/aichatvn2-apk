package com.aichatvn.agent.workers

import android.app.AlarmManager
import android.app.PendingIntent
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

                logger.w("SmartScan15MinWorker", "Service appears dead (heartbeat: ${heartbeatAge}ms ago), scheduling restart via AlarmManager...")
                
                // ✅ Dùng AlarmManager thay vì startForegroundService trực tiếp
                scheduleServiceRestart()

                Result.success()
            } catch (e: Exception) {
                logger.e("SmartScan15MinWorker", "Watchdog error: ${e.message}", e)
                Result.retry()
            }
        }
    }

    /**
     * ✅ Sử dụng AlarmManager để restart service
     * Android 12+ không cho phép startForegroundService từ Worker
     * AlarmManager được phép start foreground service
     */
    private fun scheduleServiceRestart() {
        try {
            val intent = Intent(applicationContext, CameraScanService::class.java)
            
            // PendingIntent với FLAG_IMMUTABLE (bắt buộc từ Android 12)
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Set alarm sau 1 giây
            val triggerTime = System.currentTimeMillis() + 1000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            logger.i("SmartScan15MinWorker", "✅ Service restart scheduled via AlarmManager (trigger in 1s)")
        } catch (e: Exception) {
            logger.e("SmartScan15MinWorker", "Failed to schedule restart via AlarmManager: ${e.message}", e)
            
            // Fallback: thử start service trực tiếp (có thể vẫn fail nhưng log rõ)
            try {
                logger.w("SmartScan15MinWorker", "Falling back to direct startService...")
                val intent = Intent(applicationContext, CameraScanService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
                logger.i("SmartScan15MinWorker", "Service restart triggered (fallback)")
            } catch (e2: Exception) {
                logger.e("SmartScan15MinWorker", "Fallback also failed: ${e2.message}", e2)
            }
        }
    }
}