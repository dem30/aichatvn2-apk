package com.aichatvn.agent.core.telemetry

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryManager @Inject constructor(
    private val logger: Logger
) {
    
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    
    data class TelemetryData(
        val cpuUsage: Float = 0f,
        val ramUsageMb: Long = 0,
        val totalFramesProcessed: Long = 0,
        val avgProcessingTimeMs: Float = 0f,
        val maxProcessingTimeMs: Long = 0,
        val reconnectCount: Int = 0,
        val uptimeMs: Long = 0,
        val queueSize: Int = 0
    )
    
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()
    
    private val startTime = AtomicLong(0L)
    private val totalFramesProcessed = AtomicLong(0L)
    private val totalProcessingTime = AtomicLong(0L)
    private val maxProcessingTime = AtomicLong(0L)
    private val reconnectCount = AtomicLong(0L)
    private val queueSize = AtomicLong(0L)
    
    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.w("TelemetryManager", "Already running")
            return
        }
        
        startTime.set(System.currentTimeMillis())
        totalFramesProcessed.set(0)
        totalProcessingTime.set(0)
        maxProcessingTime.set(0)
        reconnectCount.set(0)
        queueSize.set(0)
        
        monitorJob?.cancel()
        monitorJob = null
        
        monitorJob = monitoringScope.launch {
            while (isActive && isRunning.get()) {
                try {
                    val cpuUsage = getCpuUsage()
                    val ramUsage = getRamUsage()
                    
                    _telemetry.value = TelemetryData(
                        cpuUsage = cpuUsage,
                        ramUsageMb = ramUsage,
                        totalFramesProcessed = totalFramesProcessed.get(),
                        avgProcessingTimeMs = if (totalFramesProcessed.get() > 0) 
                            totalProcessingTime.get().toFloat() / totalFramesProcessed.get() else 0f,
                        maxProcessingTimeMs = maxProcessingTime.get(),
                        reconnectCount = reconnectCount.get().toInt(),
                        uptimeMs = if (startTime.get() > 0) System.currentTimeMillis() - startTime.get() else 0,
                        queueSize = queueSize.get().toInt()
                    )
                    
                    delay(5_000L)
                } catch (e: Exception) {
                    logger.e("TelemetryManager", "Error collecting telemetry: ${e.message}", e)
                    delay(10_000L)
                }
            }
        }
        
        logger.i("TelemetryManager", "Started")
    }
    
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            logger.w("TelemetryManager", "Already stopped")
            return
        }
        
        monitorJob?.cancel()
        monitorJob = null
        
        logger.i("TelemetryManager", "Stopped")
    }
    
    fun recordFrameProcessed() {
        totalFramesProcessed.incrementAndGet()
    }
    
    fun recordProcessingTime(timeMs: Long) {
        totalProcessingTime.addAndGet(timeMs)
        maxProcessingTime.updateAndGet { maxOf(it, timeMs) }
    }
    
    fun recordReconnect() {
        reconnectCount.incrementAndGet()
    }
    
    fun updateQueueSize(size: Int) {
        queueSize.set(size.toLong())
    }
    
    private fun getCpuUsage(): Float {
        // TODO: Implement CPU usage
        return 0f
    }
    
    private fun getRamUsage(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }
}