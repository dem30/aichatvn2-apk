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
        private const val WORK_NAME = "aichatvn_scheduler"
        private const val CHECK_INTERVAL_MINUTES = 5L

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
            return CronParser.matches(schedule.cron, now)
        }
        
        if (schedule.intervalMinutes > 0) {
            val lastRun = schedule.lastRunAt
            return (now - lastRun) >= schedule.intervalMinutes * 60_000L
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
// CRON PARSER - ĐƠN GIẢN
// ============================================================

object CronParser {
    private val patterns = listOf(
        Regex("""^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$"""),
        Regex("""^\*\/(\d+)\s+\*\s+\*\s+\*\s+\*$"""),
        Regex("""^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$""")
    )
    
    fun matches(cron: String, timestamp: Long): Boolean {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = timestamp
        }
        
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        
        for (pattern in patterns) {
            val match = pattern.matchEntire(cron.trim())
            if (match != null) {
                val groups = match.groupValues
                when {
                    cron.contains("*/") -> {
                        val interval = groups[1].toIntOrNull() ?: continue
                        val totalMinutes = hour * 60 + minute
                        return totalMinutes % interval == 0
                    }
                    groups.size >= 3 -> {
                        val cronMinute = groups[1].toIntOrNull() ?: continue
                        val cronHour = groups[2].toIntOrNull() ?: continue
                        return minute == cronMinute && hour == cronHour
                    }
                }
            }
        }
        
        return false
    }
}

private fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = when (value) {
            is JSONObject -> value.toMap()
            is org.json.JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    val item = value.get(i)
                    list.add(
                        when (item) {
                            is JSONObject -> item.toMap()
                            else -> item
                        }
                    )
                }
                list
            }
            else -> value
        }
    }
    return map
}