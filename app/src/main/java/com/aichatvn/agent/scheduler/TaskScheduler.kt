package com.aichatvn.agent.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.utils.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmSuppressWildcards

@HiltWorker
class TaskScheduler @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val logger: Logger
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "aichatvn_scheduler"
        private const val CHECK_INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .setRequiresStorageNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<TaskScheduler>(
                CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TaskScheduler>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun ensureRunning(context: Context) {
            schedule(context)
        }
    }

    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                logger.d("TaskScheduler", "Checking schedules...")
                
                val schedules = database.scheduleDao().getAllSchedules()
                val now = System.currentTimeMillis()
                
                var executedCount = 0
                for (schedule in schedules) {
                    if (schedule.enabled != 1) continue
                    
                    if (shouldRunNow(schedule, now)) {
                        runSchedule(schedule)
                        database.scheduleDao().updateLastRun(schedule.id, now)
                        executedCount++
                    }
                }
                
                logger.d("TaskScheduler", "Executed $executedCount schedules")
                Result.success()
                
            } catch (e: Exception) {
                logger.e("TaskScheduler", "Error: ${e.message}", e)
                Result.retry()
            }
        }
    }

    private fun shouldRunNow(schedule: ScheduleEntity, now: Long): Boolean {
        if (schedule.cron.isNotEmpty()) {
            return CronParser.matches(schedule.cron, now, schedule.lastRunAt)
        }
        
        if (schedule.intervalMinutes > 0) {
            val lastRun = schedule.lastRunAt
            // Thêm dung sai trễ 10 giây để đảm bảo không bỏ sót tác vụ do trễ dịch vụ WorkManager
            return (now - lastRun) >= (schedule.intervalMinutes * 60_000L - 10_000L)
        }
        
        return false
    }

    private suspend fun runSchedule(schedule: ScheduleEntity) {
        try {
            val plugin = plugins.find { it.id == schedule.pluginId }
            if (plugin == null) {
                logger.w("TaskScheduler", "Plugin not found: ${schedule.pluginId}")
                return
            }
            
            val params = if (schedule.params.isNotEmpty()) {
                JSONObject(schedule.params).toMap()
            } else {
                emptyMap()
            }
            
            val result = plugin.execute(schedule.action, params)
            
            when (result) {
                is AgentKernel.PluginResult.Success -> {
                    logger.i("TaskScheduler", "✅ ${schedule.pluginId}.${schedule.action} completed")
                }
                is AgentKernel.PluginResult.Failure -> {
                    logger.e("TaskScheduler", "❌ ${schedule.pluginId}.${schedule.action} failed: ${result.error}")
                }
                else -> {
                    logger.w("TaskScheduler", "⚠️ ${schedule.pluginId}.${schedule.action} returned: $result")
                }
            }
        } catch (e: Exception) {
            logger.e("TaskScheduler", "❌ ${schedule.pluginId}.${schedule.action} exception: ${e.message}")
        }
    }
}

// ============================================================
// CRON PARSER - HỖ TRỢ DUNG SAI TRỄ HỆ THỐNG
// ============================================================

object CronParser {
    private val dailyPattern  = Regex("""^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$""")
    private val intervalPattern = Regex("""^\*/(\d+)\s+\*\s+\*\s+\*\s+\*$""")

    fun matches(cron: String, timestamp: Long, lastRunAt: Long): Boolean {
        val nowCalendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowHour = nowCalendar.get(java.util.Calendar.HOUR_OF_DAY)
        val nowMinute = nowCalendar.get(java.util.Calendar.MINUTE)
        val nowDayOfYear = nowCalendar.get(java.util.Calendar.DAY_OF_YEAR)
        val nowYear = nowCalendar.get(java.util.Calendar.YEAR)

        val lastCalendar = java.util.Calendar.getInstance().apply { timeInMillis = lastRunAt }
        val lastDayOfYear = lastCalendar.get(java.util.Calendar.DAY_OF_YEAR)
        val lastYear = lastCalendar.get(java.util.Calendar.YEAR)

        // Kiểm tra xem lịch trình đã chạy trong ngày hôm nay chưa
        val isDifferentDay = nowYear != lastYear || nowDayOfYear != lastDayOfYear

        // "*/N * * * *" — chạy mỗi N phút
        intervalPattern.matchEntire(cron.trim())?.let { m ->
            val interval = m.groupValues[1].toLongOrNull() ?: return false
            val elapsedMinutes = (timestamp - lastRunAt) / 60_000L
            return elapsedMinutes >= (interval - 1) // Cho phép lệch tối đa 1 phút
        }

        // "MINUTE HOUR * * *" — chạy đúng giờ:phút mỗi ngày (hỗ trợ lệch giờ của HĐH)
        dailyPattern.matchEntire(cron.trim())?.let { m ->
            val cronMinute = m.groupValues[1].toIntOrNull() ?: return false
            val cronHour   = m.groupValues[2].toIntOrNull() ?: return false
            
            val currentTotalMinutes = nowHour * 60 + nowMinute
            val targetTotalMinutes = cronHour * 60 + cronMinute
            
            // Nếu đã vượt qua thời gian hẹn trong ngày và chưa từng chạy trong ngày hôm nay
            return currentTotalMinutes >= targetTotalMinutes && isDifferentDay
        }

        return false
    }
}

private fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        // Chặn sớm rủi ro kiểu dữ liệu rác JSONObject.NULL khi chuyển sang Map của Kotlin
        if (value != org.json.JSONObject.NULL) {
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is org.json.JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item != org.json.JSONObject.NULL) {
                            list.add(
                                when (item) {
                                    is JSONObject -> item.toMap()
                                    else -> item
                                }
                            )
                        }
                    }
                    list
                }
                else -> value
            }
        }
    }
    return map
}