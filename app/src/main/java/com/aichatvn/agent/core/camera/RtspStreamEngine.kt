package com.aichatvn.agent.core.camera

import com.aichatvn.agent.core.processor.CameraProcessor
import com.aichatvn.agent.core.telemetry.TelemetryManager
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import javax.inject.Inject

/**
 * RTSP Stream Engine - Dành cho tương lai
 * Chưa implement, chỉ chuẩn bị interface
 */
class RtspStreamEngine @Inject constructor(
    private val cameraProcessor: CameraProcessor,
    private val telemetryManager: TelemetryManager,
    private val logger: Logger
) : CameraEngine {
    
    private var engineScope: CoroutineScope? = null
    private var streamJob: Job? = null
    private val mutex = Mutex()
    
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    
    private val _frameFlow = MutableSharedFlow<CameraFrame>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val frameFlow = _frameFlow.filterNotNull()
    
    private var config = mapOf<String, Any>()
    private var reconnectAttempt = 0
    private var frameCount = 0L
    private var startTime = 0L
    
    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
    }
    
    override suspend fun setConfig(config: Map<String, Any>) {
        mutex.withLock {
            this.config = config
            logger.d("RtspStreamEngine", "Config updated")
        }
    }
    
    override suspend fun start() {
        mutex.withLock {
            if (_status.value is EngineStatus.Running || 
                _status.value is EngineStatus.Starting) {
                logger.w("RtspStreamEngine", "Already running or starting")
                return
            }
            _status.value = EngineStatus.Starting
        }
        
        engineScope?.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope = engineScope ?: return
        
        startTime = System.currentTimeMillis()
        
        streamJob = scope.launch {
            try {
                _status.value = EngineStatus.Running
                logger.i("RtspStreamEngine", "RTSP stream started")
                
                // TODO: Implement RTSP client
                // while (isActive) {
                //     val frame = rtspClient.readFrame()
                //     _frameFlow.emit(frame)
                //     frameCount++
                //     telemetryManager.recordFrameProcessed()
                // }
                
                // Temporary: simulate frames
                while (isActive) {
                    yield()
                    delay(1000) // 1 fps
                }
                
            } catch (e: Exception) {
                logger.e("RtspStreamEngine", "Stream error: ${e.message}", e)
                _status.value = EngineStatus.Error(e.message ?: "Unknown", e)
                handleReconnect()
            } finally {
                if (_status.value !is EngineStatus.Stopping) {
                    _status.value = EngineStatus.Idle
                }
            }
        }
    }
    
    private suspend fun handleReconnect() {
        reconnectAttempt = 0
        while (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempt++
            val delay = calculateBackoffDelay(reconnectAttempt)
            
            _status.value = EngineStatus.Reconnecting(reconnectAttempt, delay)
            logger.w("RtspStreamEngine", "Reconnecting attempt $reconnectAttempt in ${delay}ms")
            
            delay(delay)
            
            try {
                // TODO: Thử reconnect
                _status.value = EngineStatus.Running
                logger.i("RtspStreamEngine", "Reconnected successfully")
                reconnectAttempt = 0
                return
            } catch (e: Exception) {
                logger.e("RtspStreamEngine", "Reconnect failed: ${e.message}", e)
            }
        }
        
        _status.value = EngineStatus.Error("Max reconnect attempts reached")
    }
    
    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1))
        return minOf(delay, MAX_RECONNECT_DELAY_MS)
    }
    
    override suspend fun stop() {
        mutex.withLock {
            if (_status.value is EngineStatus.Idle || 
                _status.value is EngineStatus.Stopping) {
                return
            }
            _status.value = EngineStatus.Stopping
        }
        
        streamJob?.cancel()
        streamJob = null
        
        engineScope?.cancel()
        engineScope = null
        
        _status.value = EngineStatus.Idle
        reconnectAttempt = 0
        logger.i("RtspStreamEngine", "Stopped")
    }
    
    override fun getMetrics(): EngineMetrics {
        return EngineMetrics(
            totalFrames = frameCount,
            frameCount = frameCount,
            consecutiveErrors = reconnectAttempt,
            avgProcessingTime = 0,
            maxProcessingTime = 0,
            uptime = if (startTime > 0) System.currentTimeMillis() - startTime else 0,
            status = _status.value.toString()
        )
    }
}