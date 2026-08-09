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
        // ⚠️ SỬA (bảo mật): rtspUrl được nối thẳng vào command string của FFmpeg bên dưới, bọc
        // trong dấu ngoặc kép. Nếu URL chứa dấu " hoặc \ (ví dụ mật khẩu camera nhập tay chưa qua
        // percent-encode), ký tự đó có thể thoát khỏi cặp ngoặc và chèn thêm tham số FFmpeg tuỳ ý
        // (command injection). Từ chối ngay tại đây thay vì để lệnh chạy sai/không an toàn.
        if (rtspUrl.contains('"') || rtspUrl.contains('\\')) {
            logger.e("CameraRecorder", "startRecording: rtspUrl chứa ký tự không an toàn, từ chối ghi hình cameraId=$cameraId")
            return null
        }

        // ⚠️ SỬA (race condition): trước đây containsKey() rồi put() riêng biệt, không atomic —
        // 2 lệnh "bắt đầu ghi" gọi gần như đồng thời (vd người dùng bấm 2 lần nhanh) có thể cả
        // hai cùng pass qua check trước khi cái đầu kịp ghi vào map, tạo ra 2 FFmpeg session cùng
        // ghi cho 1 cameraId. Tạo session trước, dùng putIfAbsent để đảm bảo chỉ 1 session được
        // giữ lại; nếu đã có session khác thắng, huỷ session vừa tạo và thoát.
        val dir = File(context.getExternalFilesDir(null), "recordings/$cameraId").apply { mkdirs() }
        val outputFile = File(dir, "${System.currentTimeMillis()}.mp4")
        val clamped = durationSec.coerceIn(1, MAX_DURATION_SEC)

        val command = "-rtsp_transport tcp -i \"$rtspUrl\" -t $clamped -c copy \"${outputFile.absolutePath}\""
        val session = FFmpegKit.executeAsync(command) { finished ->
            activeSessions.remove(cameraId)
            logger.i("CameraRecorder", "Ghi hình xong cameraId=$cameraId state=${finished.state} rc=${finished.returnCode}")
        }

        val previous = activeSessions.putIfAbsent(cameraId, session)
        if (previous != null) {
            // Một session khác đã thắng race — huỷ session vừa tạo, không để nó chạy song song.
            FFmpegKit.cancel(session.sessionId)
            logger.w("CameraRecorder", "startRecording: cameraId=$cameraId đang ghi dở, bỏ qua yêu cầu mới")
            return null
        }

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