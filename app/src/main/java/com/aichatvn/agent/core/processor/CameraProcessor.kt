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

    // processorScope sống theo lifecycle @Singleton (toàn bộ app process).
    // KHÔNG cancel scope này trong shutdown()/detach() vì CameraProcessor được
    // tái sử dụng giữa các lần đổi engine (snapshot <-> RTSP) — chỉ flow nguồn
    // thay đổi, scope xử lý vẫn giữ nguyên để tránh tạo lại coroutine liên tục.
    // Chỉ cancel thật sự qua releaseScope() khi cần dừng vĩnh viễn.
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
    // ✅ BỎ COMMENT - Gọi CameraSkill xử lý ảnh
    try {
        cameraSkill.processImage(frame.cameraId, data)
    } catch (e: Exception) {
        logger.e("CameraProcessor", "processByteArray error: ${e.message}", e)
    }
}

private suspend fun processByteBuffer(buffer: ByteBuffer, frame: CameraFrame) {
    val position = buffer.position()
    val limit = buffer.limit()
    val size = limit - position

    logger.d("CameraProcessor", "Processing ByteBuffer, size=$size")

    // ✅ BỎ COMMENT - Xử lý trực tiếp ByteBuffer cho RTSP
    try {
        // Chuyển ByteBuffer thành ByteArray để xử lý
        val bytes = ByteArray(size)
        buffer.get(bytes, position, size)
        cameraSkill.processImage(frame.cameraId, bytes)
    } catch (e: Exception) {
        logger.e("CameraProcessor", "processByteBuffer error: ${e.message}", e)
    }
}

    private suspend fun processByteBuffer(buffer: ByteBuffer, frame: CameraFrame) {
        val position = buffer.position()
        val limit = buffer.limit()
        val size = limit - position

        logger.d("CameraProcessor", "Processing ByteBuffer, size=$size")

        // TODO: Xử lý trực tiếp ByteBuffer cho RTSP
        // cameraSkill.processImageBuffer(frame.cameraId, buffer, size)
    }

    fun getMetrics(): ProcessorMetrics {
        return ProcessorMetrics(
            totalFramesProcessed = frameCount,
            isAttached = processingJob?.isActive == true
        )
    }

    /**
     * Gọi giữa các lần đổi engine (vd snapshot -> RTSP). KHÔNG cancel processorScope,
     * chỉ detach khỏi flow hiện tại để có thể attach lại flow mới sau đó.
     */
    fun shutdown() {
        detach()
        logger.i("CameraProcessor", "Shutdown (scope kept alive for reuse)")
    }

    /**
     * Chỉ gọi khi muốn dừng vĩnh viễn instance này (vd: app teardown hoàn toàn,
     * test cleanup). Sau khi gọi, instance không thể attach lại được nữa.
     */
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