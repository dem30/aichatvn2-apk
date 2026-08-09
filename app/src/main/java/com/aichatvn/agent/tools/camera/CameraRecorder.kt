package com.aichatvn.agent.tools.camera

import android.content.Context
import com.aichatvn.agent.utils.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        // Giới hạn cứng — tránh trường hợp lệnh chat/schedule quên truyền durationSec hoặc
        // truyền số quá lớn làm đầy bộ nhớ máy nếu người dùng quên có file đang ghi.
        private const val MAX_DURATION_SEC = 300
    }

    private val activeSessions = ConcurrentHashMap<String, FFmpegSession>()

    fun isRecording(cameraId: String): Boolean = activeSessions.containsKey(cameraId)

    /**
     * Bắt đầu ghi — CHỈ remux container (-c copy), KHÔNG decode/encode lại luồng. Camera đã tự
     * nén H.264 sẵn, việc của app chỉ là đóng gói lại thành .mp4 xem được, không phân tích gì.
     * Trả về đường dẫn file (chưa chắc đã ghi xong) hoặc null nếu cameraId đang ghi dở.
     */
    fun startRecording(cameraId: String, rtspUrl: String, durationSec: Int): String? {
        if (activeSessions.containsKey(cameraId)) {
            logger.w("CameraRecorder", "startRecording: cameraId=$cameraId đang ghi dở, bỏ qua yêu cầu mới")
            return null
        }
        val dir = File(context.getExternalFilesDir(null), "recordings/$cameraId").apply { mkdirs() }
        val outputFile = File(dir, "${System.currentTimeMillis()}.mp4")
        val clamped = durationSec.coerceIn(1, MAX_DURATION_SEC)

        val command = "-rtsp_transport tcp -i \"$rtspUrl\" -t $clamped -c copy \"${outputFile.absolutePath}\""
        val session = FFmpegKit.executeAsync(command) { finished ->
            activeSessions.remove(cameraId)
            logger.i("CameraRecorder", "Ghi hình xong cameraId=$cameraId state=${finished.state} rc=${finished.returnCode}")
        }
        activeSessions[cameraId] = session
        logger.i("CameraRecorder", "startRecording cameraId=$cameraId → ${outputFile.absolutePath} (${clamped}s)")
        return outputFile.absolutePath
    }

    fun stopRecording(cameraId: String): Boolean {
        val session = activeSessions.remove(cameraId) ?: return false
        FFmpegKit.cancel(session.sessionId)
        logger.i("CameraRecorder", "stopRecording cameraId=$cameraId (dừng sớm)")
        return true
    }
}