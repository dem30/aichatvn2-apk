package com.aichatvn.agent.tools.camera

import android.content.Context
import com.aichatvn.agent.utils.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * ⚠️ VẤN ĐỀ GỐC (phát hiện qua case camera V380 chỉ có RTSP, không có HTTP snapshot): toàn bộ
 * tính năng AI giám sát (CameraSkill.scanCamera → so sánh ảnh, Groq Vision, cảnh báo) dựa vào
 * SnapshotFetcher.fetchSnapshot(url), mà SnapshotFetcher CHỈ hiểu http/https (url.toHttpUrlOrNull()
 * trả null ngay với rtsp://, xem SnapshotFetcher.kt). Camera chỉ hỗ trợ RTSP (không có endpoint
 * HTTP nào) trước đây KHÔNG THỂ dùng được tính năng cốt lõi của app dù RTSP xem trực tiếp hoàn
 * toàn ổn — đây là lớp bù đắp: bắt 1 khung hình JPEG thật từ RTSP stream, trả về đúng kiểu
 * ByteArray? mà ImageHashTool/CameraSkill đang cần, để không phải sửa lại toàn bộ pipeline AI.
 *
 * Dùng FFmpegKit (com.arthenica.ffmpegkit) — thư viện .so đã có sẵn trong app (libffmpegkit*.so,
 * xác nhận qua log build assembleDebug), chỉ chưa có code Kotlin gọi tới cho mục đích này.
 *
 * ⚠️ GIỚI HẠN ĐÃ BIẾT (theo tài liệu chính thức arthenica/ffmpeg-kit):
 * - Android KHÔNG hỗ trợ UDP streaming trong ffmpeg-kit (thiếu pthread component cần cho udp
 *   module) — RTSP mặc định thử UDP trước rồi mới fallback TCP, trên Android bước UDP sẽ treo im
 *   lặng không gửi được gói nào. BẮT BUỘC ép "-rtsp_transport tcp" trong command, nếu không sẽ
 *   luôn timeout dù camera hoàn toàn bình thường.
 * - executeAsync() không tự có timeout — quá trình kết nối/đọc RTSP có thể treo vô thời hạn nếu
 *   camera không phản hồi (khác lỗi kết nối bị từ chối ngay). Bọc bằng withTimeoutOrNull() ở tầng
 *   gọi, và chủ động FFmpegKit.cancel(sessionId) nếu timeout để không rò rỉ tiến trình ffmpeg chạy
 *   nền vô thời hạn.
 * - File output ghi vào cacheDir (không phải Downloads/external) — đây là ảnh trung gian dùng để
 *   đọc bytes ngay rồi xoá, không phải sản phẩm người dùng cần giữ lại.
 *
 * ✅ QUAN TRỌNG (đã xác nhận qua thực tế build/test): PHẢI co nhỏ frame ngay trong lệnh FFmpeg
 * (`-vf scale=...`) trước khi ghi ra file, KHÔNG được bỏ bước này dù ImageHashTool.optimizeImage()
 * ở tầng sau vẫn downsample an toàn bằng inSampleSize. Lý do: thiếu scale ở đây khiến FFmpeg phải
 * decode + encode JPEG ở ĐÚNG độ phân giải gốc camera (1080p/2K) — chậm hơn hẳn, và pipeline
 * ONVIF → relayMotionEvent() → fetchOneSnapshot() → grabFrame() chạy ngay trong luồng khởi động
 * WebhookGatewayService (serviceScope, chạy cùng lúc app mở) — chậm ở bước native này đủ để kéo
 * theo hàng loạt việc dồn ứ phía sau, từng khiến app không mở được (thu về nền ngay sau splash,
 * không có dialog lỗi rõ ràng). Co nhỏ ngay từ FFmpeg (chỉ vài chục KB thay vì vài trăm KB-vài MB)
 * giữ toàn bộ chuỗi này nhanh và nhẹ ngay từ nguồn.
 */
@Singleton
class RtspFrameGrabber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {
    companion object {
        // Thời gian tối đa cho toàn bộ quá trình kết nối RTSP + decode 1 frame — dài hơn timeout
        // DESCRIBE probe (2.5s ở CameraCapabilityProber) vì đây là mở stream thật + decode video,
        // không chỉ bắt tay giao thức. Camera LAN bình thường chỉ mất 1-3s; 10s đã rất rộng rãi.
        private const val GRAB_TIMEOUT_MS = 10_000L

        // ✅ Giới hạn cạnh dài nhất của frame xuất ra — mục đích ảnh này là pHash so sánh + AI
        // Vision (Groq), không phải xem/lưu trữ, nên không cần giữ nguyên độ phân giải gốc camera
        // (thường 1080p/2K, vài trăm KB/ảnh). 400px cạnh dài đủ chi tiết cho cả 2 mục đích, giảm
        // dung lượng xuống còn vài chục KB — đỡ tốn băng thông khi gửi ảnh kèm báo động ra ngoài
        // LAN, giảm chi phí/thời gian gọi Groq Vision API, và (quan trọng nhất) giữ bước decode/
        // encode native trong FFmpeg đủ nhanh để không kéo chậm chuỗi khởi động ONVIF relay.
        private const val MAX_FRAME_DIMENSION = 400
    }

    /**
     * Bắt 1 khung hình từ [rtspUrl] (đã kèm sẵn user:pass@ nếu cần, cùng định dạng
     * CameraCapabilityProber.buildRtspUrlWithCredentials() trả về) và trả về bytes JPEG — hoặc
     * null nếu lỗi/timeout. KHÔNG throw ra ngoài, cùng hợp đồng với SnapshotFetcher.fetchSnapshot().
     */
    suspend fun grabFrame(rtspUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "rtsp_frame_${System.currentTimeMillis()}.jpg")
        try {
            val ok = withTimeoutOrNull(GRAB_TIMEOUT_MS) {
                runFFmpegGrab(rtspUrl, outputFile.absolutePath)
            }
            if (ok != true) {
                logger.e("RtspFrameGrabber", "Timeout hoặc lỗi khi bắt frame từ $rtspUrl")
                return@withContext null
            }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                logger.e("RtspFrameGrabber", "FFmpeg báo thành công nhưng file rỗng/không tồn tại: $rtspUrl")
                return@withContext null
            }
            val bytes = outputFile.readBytes()
            logger.d("RtspFrameGrabber", "Bắt frame OK (${bytes.size} bytes) từ $rtspUrl")
            bytes
        } catch (e: Exception) {
            logger.e("RtspFrameGrabber", "Lỗi bắt frame từ $rtspUrl: ${e.message}", e)
            null
        } finally {
            // Ảnh trung gian, xoá ngay sau khi đọc xong — không để rác tích luỹ trong cacheDir
            // qua mỗi lượt scan định kỳ (CameraSkill.scanCamera chạy theo lịch, có thể rất nhiều
            // lần/ngày).
            runCatching { outputFile.delete() }
        }
    }

    /**
     * Chạy FFmpegKit bất đồng bộ, chuyển callback-based API thành suspend qua
     * suspendCancellableCoroutine — resume(true) khi ReturnCode.isSuccess, resume(false) cho mọi
     * trường hợp khác (fail/cancel). invokeOnCancellation huỷ session ffmpeg thật nếu coroutine bị
     * huỷ (vd do withTimeoutOrNull hết giờ) — tránh tiến trình ffmpeg tiếp tục chạy nền vô ích sau
     * khi caller đã bỏ cuộc.
     *
     * "-rtsp_transport tcp": BẮT BUỘC — xem giới hạn UDP ở ghi chú đầu file.
     * "-frames:v 1": chỉ decode đúng 1 frame rồi dừng, không phải toàn bộ stream.
     * "-vf scale=...": co nhỏ cạnh dài nhất về MAX_FRAME_DIMENSION, giữ nguyên tỉ lệ khung hình
     * gốc (không ép méo ảnh vì camera có thể lắp ngang hoặc dọc). Biểu thức chọn cạnh nào đang
     * dài hơn (iw=chiều rộng, ih=chiều cao gốc) để giới hạn còn MAX_FRAME_DIMENSION, cạnh còn lại
     * tính "-2" (FFmpeg tự suy theo tỉ lệ, luôn làm tròn về số chẵn — bắt buộc với codec JPEG/
     * H.264, số lẻ sẽ lỗi encode). Nếu ảnh gốc đã nhỏ hơn MAX_FRAME_DIMENSION sẵn, scale filter tự
     * động không phóng to lên (dùng min(iw,W) thay vì gán cứng W).
     * "-y": ghi đè nếu file output đã tồn tại (không nên xảy ra vì tên file có timestamp, nhưng an
     * toàn hơn để không bị FFmpeg hỏi xác nhận và treo).
     */
    private suspend fun runFFmpegGrab(rtspUrl: String, outputPath: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val scaleExpr = "scale='if(gt(iw,ih),min(iw,$MAX_FRAME_DIMENSION),-2)':" +
                "'if(gt(iw,ih),-2,min(ih,$MAX_FRAME_DIMENSION))'"
            val command = "-rtsp_transport tcp -i \"$rtspUrl\" -frames:v 1 -vf \"$scaleExpr\" -y \"$outputPath\""
            var session: FFmpegSession? = null
            session = FFmpegKit.executeAsync(command) { completedSession ->
                if (cont.isActive) {
                    val success = ReturnCode.isSuccess(completedSession.returnCode)
                    if (!success) {
                        logger.d(
                            "RtspFrameGrabber",
                            "FFmpeg thất bại rc=${completedSession.returnCode} — ${completedSession.failStackTrace ?: completedSession.output}"
                        )
                    }
                    cont.resume(success)
                }
            }
            cont.invokeOnCancellation {
                session?.let { FFmpegKit.cancel(it.sessionId) }
            }
        }
}