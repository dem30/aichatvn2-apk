package com.aichatvn.agent.tools.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aichatvn.agent.utils.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
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
            // ⚠️ SỬA: trước đây file ghi xong nằm y nguyên ở getExternalFilesDir() (vùng riêng
            // của app) — KHÔNG hiện trong Gallery/Files/Trình quản lý file, và bị xoá sạch khi gỡ
            // app. FFmpeg vẫn ghi vào đây trước (path riêng, chắc chắn ghi được, không cần quyền
            // nào) — CHỈ SAU KHI ghi xong thành công mới chuyển ra thư mục công khai
            // Movies/AIChatVN/<cameraId>/, để người dùng tìm lại được video bằng ứng dụng bình
            // thường và video vẫn còn nếu sau này gỡ cài đặt app.
            if (ReturnCode.isSuccess(finished.returnCode) && outputFile.exists()) {
                val publicPath = moveRecordingToPublicStorage(outputFile, cameraId)
                logger.i(
                    "CameraRecorder",
                    "Ghi hình xong cameraId=$cameraId state=${finished.state} rc=${finished.returnCode} → " +
                        (publicPath ?: "LỖI chuyển ra bộ nhớ công khai, file tạm vẫn còn ở ${outputFile.absolutePath}")
                )
            } else {
                // Ghi thất bại/bị huỷ giữa chừng — file tạm (nếu có, thường dở dang) không còn
                // giá trị, xoá luôn để không rác vùng lưu trữ riêng của app theo thời gian.
                outputFile.delete()
                logger.i("CameraRecorder", "Ghi hình xong cameraId=$cameraId state=${finished.state} rc=${finished.returnCode} (thất bại/huỷ, đã xoá file tạm)")
            }
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

    /**
     * ✅ MỚI: chuyển file .mp4 vừa ghi xong (đang ở vùng riêng của app) ra thư mục công khai
     * Movies/AIChatVN/<cameraId>/ — chỉ gọi SAU KHI FFmpeg xác nhận ghi thành công, không gọi cho
     * file dở dang.
     *
     * Chia 2 nhánh vì project minSdk=26 (Android 8) nhưng targetSdk=34 — từ Android 10 (API 29,
     * Build.VERSION_CODES.Q) trở lên Google BẮT BUỘC "Scoped Storage": app không còn được tự tạo
     * File() trỏ thẳng ra thư mục public như trước (dù CÓ quyền WRITE_EXTERNAL_STORAGE) — phải
     * qua MediaStore.Video.Media (ContentResolver.insert + openOutputStream), có cờ IS_PENDING
     * trong lúc ghi để Gallery không hiện file dở dang giữa chừng.
     *
     * ⚠️ Android 8-9 (API 26-28, dưới nhánh else): CHƯA có scoped storage, ghi File() trực tiếp
     * vào thư mục public vẫn hoạt động NHƯNG cần quyền dangerous WRITE_EXTERNAL_STORAGE đã được
     * cấp từ trước (runtime permission) — hàm này ở tầng Recorder, không có Activity/Compose nào
     * để tự xin quyền, nơi gọi (chat/schedule action) phải đảm bảo quyền đã có sẵn, giống cách
     * app đã xử lý các quyền dangerous khác (CAMERA, ACCESS_FINE_LOCATION...). Nếu thiếu quyền,
     * copyTo() ở nhánh này sẽ ném SecurityException, bị bắt ở catch bên dưới, trả về null (log lỗi,
     * KHÔNG crash) — file tạm vẫn còn nguyên trong vùng riêng của app để không mất video đã ghi.
     *
     * @return đường dẫn/URI công khai để log, hoặc null nếu chuyển thất bại (file tạm KHÔNG bị
     * xoá trong trường hợp này — giữ lại để không mất video, dù người dùng phải tự vào vùng riêng
     * của app bằng trình quản lý file mới lấy ra được).
     */
    private fun moveRecordingToPublicStorage(tempFile: File, cameraId: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, tempFile.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIChatVN/$cameraId")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null

                resolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { input -> input.copyTo(out) }
                } ?: run {
                    // Không mở được output stream — dọn record MediaStore rỗng vừa tạo, đừng để
                    // lại 1 mục "video 0 byte" ma trong Gallery.
                    resolver.delete(uri, null, null)
                    return null
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                tempFile.delete() // đã có bản chính thức trong MediaStore, bản tạm không cần nữa
                uri.toString()
            } else {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "AIChatVN/$cameraId"
                ).apply { mkdirs() }
                val destFile = File(publicDir, tempFile.name)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                // Quét lại MediaStore ngay — không quét thì file mới copy có thể không hiện trong
                // Gallery/Files cho tới lần quét định kỳ tiếp theo của hệ thống (có thể rất lâu).
                MediaScannerConnection.scanFile(
                    context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null
                )
                destFile.absolutePath
            }
        } catch (e: Exception) {
            logger.e("CameraRecorder", "moveRecordingToPublicStorage lỗi (cameraId=$cameraId): ${e.message}", e)
            null
        }
    }
}