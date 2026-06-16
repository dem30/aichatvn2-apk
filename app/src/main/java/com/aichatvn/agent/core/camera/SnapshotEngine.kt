package com.aichatvn.agent.core.camera

import com.aichatvn.agent.core.telemetry.TelemetryManager
import com.aichatvn.agent.skills.CameraSkill
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
import javax.inject.Inject

class SnapshotEngine @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val telemetryManager: TelemetryManager,
    private val logger: Logger
) : CameraEngine {

    private var engineScope: CoroutineScope? = null
    private var scanJob: Job? = null
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
    private var consecutiveErrors = 0
    private var frameCount = 0L

    private var totalFrames = 0L
    private var totalProcessingTime = 0L
    private var maxProcessingTime = 0L
    private var engineStartTime = 0L

    companion object {
        private const val SCAN_INTERVAL_MS = 15 * 60 * 1000L
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val BACKOFF_INTERVAL_MS = SCAN_INTERVAL_MS * 2
    }

    override suspend fun setConfig(config: Map<String, Any>) {
        mutex.withLock {
            this.config = config
            logger.d("SnapshotEngine", "Config updated")
        }
    }

    override suspend fun start() {
        mutex.withLock {
            if (_status.value is EngineStatus.Running ||
                _status.value is EngineStatus.Starting) {
                logger.w("SnapshotEngine", "Already running or starting")
                return
            }

            consecutiveErrors = 0
            frameCount = 0
            engineStartTime = System.currentTimeMillis()
            totalFrames = 0
            totalProcessingTime = 0
            maxProcessingTime = 0

            _status.value = EngineStatus.Starting
            logger.i("SnapshotEngine", "Starting...")
        }

        engineScope?.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope = engineScope ?: return

        scanJob = scope.launch {
            try {
                _status.value = EngineStatus.Running
                logger.i("SnapshotEngine", "Running")

                while (isActive) {
                    try {
                        val loopStart = System.currentTimeMillis()

                        val frame = fetchSnapshot()
                        if (frame != null) {
                            _frameFlow.emit(frame)
                            frameCount++
                            totalFrames++
                            consecutiveErrors = 0
                            _status.value = EngineStatus.Running
                        } else {
                            consecutiveErrors++
                            if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                                _status.value = EngineStatus.Reconnecting(
                                    attempt = consecutiveErrors,
                                    delayMs = BACKOFF_INTERVAL_MS
                                )
                            }
                        }

                        val processingTime = System.currentTimeMillis() - loopStart
                        totalProcessingTime += processingTime
                        if (processingTime > maxProcessingTime) {
                            maxProcessingTime = processingTime
                        }

                        telemetryManager.recordFrameProcessed()
                        telemetryManager.recordProcessingTime(processingTime)

                        val delayTime = if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                            BACKOFF_INTERVAL_MS
                        } else {
                            SCAN_INTERVAL_MS
                        }
                        val remainingDelay = maxOf(0, delayTime - processingTime)

                        delay(remainingDelay)

                    } catch (e: Exception) {
                        logger.e("SnapshotEngine", "Scan error: ${e.message}", e)
                        _status.value = EngineStatus.Error(
                            message = e.message ?: "Unknown error",
                            cause = e
                        )
                        consecutiveErrors++
                        delay(SCAN_INTERVAL_MS)
                    }
                }
            } finally {
                if (_status.value !is EngineStatus.Stopping) {
                    _status.value = EngineStatus.Idle
                }
                logger.i("SnapshotEngine", "Stopped")
            }
        }
    }

    private suspend fun fetchSnapshot(): CameraFrame? {
        try {
            val result = cameraSkill.scanCamera(null, isDailyReport = false)

            if (result.success) {
                val data = result.data as? Map<*, *>
                val results = data?.get("results") as? List<*>
                val firstResult = results?.firstOrNull() as? Map<*, *>
                val imageBytes = firstResult?.get("imageBytes") as? ByteArray
                val cameraId = firstResult?.get("cameraId") as? String ?: "unknown"

                if (imageBytes != null) {
                    return CameraFrame(
                        data = FrameData.ByteArrayData(imageBytes),
                        source = "snapshot",
                        cameraId = cameraId,
                        width = 0,
                        height = 0,
                        format = "jpeg",
                        metadata = mapOf(
                            "diff" to (firstResult["diff"] ?: 0),
                            "hasChange" to (firstResult["hasChange"] ?: false),
                            "isSuspicious" to (firstResult["isSuspicious"] ?: false)
                        )
                    )
                }
            }
            return null

        } catch (e: Exception) {
            logger.e("SnapshotEngine", "fetchSnapshot error: ${e.message}", e)
            return null
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            if (_status.value is EngineStatus.Idle ||
                _status.value is EngineStatus.Stopping) {
                return
            }
            _status.value = EngineStatus.Stopping
        }

        scanJob?.cancel()
        scanJob = null

        engineScope?.cancel()
        engineScope = null

        _status.value = EngineStatus.Idle
        consecutiveErrors = 0
        logger.i("SnapshotEngine", "Stopped, ready to restart")
    }

    override fun getMetrics(): EngineMetrics {
        return EngineMetrics(
            totalFrames = totalFrames,
            frameCount = frameCount,
            consecutiveErrors = consecutiveErrors,
            avgProcessingTime = if (totalFrames > 0) totalProcessingTime / totalFrames else 0,
            maxProcessingTime = maxProcessingTime,
            uptime = if (engineStartTime > 0) System.currentTimeMillis() - engineStartTime else 0,
            status = _status.value.toString()
        )
    }
}