package com.aichatvn.agent.core.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed class EngineStatus {
    object Idle : EngineStatus()
    object Starting : EngineStatus()
    object Running : EngineStatus()
    object Stopping : EngineStatus()
    data class Error(val message: String, val cause: Throwable? = null) : EngineStatus()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : EngineStatus()
}

data class EngineMetrics(
    val totalFrames: Long = 0,
    val frameCount: Long = 0,
    val consecutiveErrors: Int = 0,
    val avgProcessingTime: Long = 0,
    val maxProcessingTime: Long = 0,
    val uptime: Long = 0,
    val status: String = "Idle"
)

interface CameraEngine {
    val status: StateFlow<EngineStatus>
    val frameFlow: Flow<CameraFrame>
    
    suspend fun start()
    suspend fun stop()
    suspend fun setConfig(config: Map<String, Any>)
    
    fun getMetrics(): EngineMetrics
}