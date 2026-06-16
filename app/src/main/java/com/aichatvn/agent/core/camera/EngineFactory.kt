package com.aichatvn.agent.core.camera

import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class EngineType {
    SNAPSHOT,
    RTSP_STREAM
}

@Singleton
class EngineFactory @Inject constructor(
    private val snapshotEngineProvider: Provider<SnapshotEngine>,
    private val rtspEngineProvider: Provider<RtspStreamEngine>,
    private val logger: Logger
) {

    companion object {
        // Đổi thành true khi RtspStreamEngine đã implement xong RTSP client thật.
        // Hiện tại RtspStreamEngine chỉ là stub (simulate frame), không dùng cho production.
        private const val RTSP_ENABLED = false
    }

    fun create(type: EngineType): CameraEngine {
        return when (type) {
            EngineType.SNAPSHOT -> {
                logger.i("EngineFactory", "Creating new SnapshotEngine instance via Provider")
                snapshotEngineProvider.get()
            }
            EngineType.RTSP_STREAM -> {
                check(RTSP_ENABLED) {
                    "RtspStreamEngine chưa sẵn sàng cho production (mới chỉ là stub). " +
                        "Bật cờ RTSP_ENABLED trong EngineFactory khi đã implement RTSP client thật."
                }
                logger.i("EngineFactory", "Creating new RtspStreamEngine instance via Provider")
                rtspEngineProvider.get()
            }
        }
    }
}