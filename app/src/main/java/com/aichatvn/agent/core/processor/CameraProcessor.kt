package com.aichatvn.agent.core.processor

import com.aichatvn.agent.core.camera.CameraFrame
import com.aichatvn.agent.core.camera.FrameData
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraProcessor @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val logger: Logger
) {

    private val processorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var frameCount = 0L

    fun attach(flow: Flow<CameraFrame>) {
        processingJob?.cancel()
        processingJob = null

        processingJob = flow
            .onEach { frame ->
                processFrame(frame)
            }
            .launchIn(processorScope)

        logger.i("CameraProcessor", "Attached to frame flow")
    }

    fun detach() {
        processingJob?.cancel()
        processingJob = null
        logger.i("CameraProcessor", "Detached from frame flow")
    }

    private suspend fun processFrame(frame: CameraFrame) {
        try {
            frameCount++
            logger.d("CameraProcessor", "Processing frame #$frameCount: ${frame.id}")

            when (val data = frame.data) {
                is FrameData.ByteArrayData -> {
                    processByteArray(data.data, frame)
                }
                is FrameData.ByteBufferData -> {
                    processByteBuffer(data.buffer, frame)
                }
                is FrameData.ReferenceData -> {
                    logger.w("CameraProcessor", "Reference data not supported yet: ${data.reference}")
                }
            }

        } catch (e: Exception) {
            logger.e("CameraProcessor", "Error processing frame: ${e.message}", e)
        }
    }

    private suspend fun processByteArray(data: ByteArray, frame: CameraFrame) {
        try {
            cameraSkill.processImage(frame.cameraId, data)
            logger.d("CameraProcessor", "ByteArray processed for camera: ${frame.cameraId}")
        } catch (e: Exception) {
            logger.e("CameraProcessor", "processByteArray error: ${e.message}", e)
        }
    }

    private suspend fun processByteBuffer(buffer: ByteBuffer, frame: CameraFrame) {
        try {
            val position = buffer.position()
            val limit = buffer.limit()
            val size = limit - position

            logger.d("CameraProcessor", "Processing ByteBuffer, size=$size")

            val bytes = ByteArray(size)
            buffer.get(bytes, position, size)
            cameraSkill.processImage(frame.cameraId, bytes)
            
            logger.d("CameraProcessor", "ByteBuffer processed for camera: ${frame.cameraId}")
        } catch (e: Exception) {
            logger.e("CameraProcessor", "processByteBuffer error: ${e.message}", e)
        }
    }

    fun getMetrics(): ProcessorMetrics {
        return ProcessorMetrics(
            totalFramesProcessed = frameCount,
            isAttached = processingJob?.isActive == true
        )
    }

    fun shutdown() {
        detach()
        logger.i("CameraProcessor", "Shutdown (scope kept alive for reuse)")
    }

    fun releaseScope() {
        detach()
        processorScope.cancel()
        logger.i("CameraProcessor", "processorScope released")
    }
}

data class ProcessorMetrics(
    val totalFramesProcessed: Long = 0,
    val isAttached: Boolean = false
)