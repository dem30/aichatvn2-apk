package com.aichatvn.agent.tools.camera.discovery

import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Điều phối trung tâm cho toàn bộ multi-protocol camera discovery — thay thế vai trò
 * CameraAutoDiscoveryTool.discoverCameras() cũ (file đó vẫn giữ lại, không xoá, xem ghi chú trong
 * HttpSnapshotProbe.kt).
 *
 * Thứ tự chạy CÓ Ý NGHĨA: probe nhanh/rẻ chạy trước, probe tốn thời gian (quét 254 IP) chạy sau —
 * để onProgress phản ánh đúng cảm giác "đang làm việc" thay vì đứng im 1 chỗ lâu ở bước rẻ.
 * 1. OnvifWsDiscoveryProbe — UDP multicast, ~4s cố định, không phụ thuộc số IP trong mạng.
 * 2. VendorUdpDiscoveryProbe — UDP broadcast riêng V380, ~4s cố định (đã implement thật, xem
 *    VendorUdpDiscoveryProbe.kt — dựa trên source đã reverse-engineer, không còn là skeleton).
 * 3. HttpSnapshotProbe — quét 254 IP × nhiều port/path, có thể mất 30s-vài phút, LÀM SAU CÙNG.
 *
 * Gộp kết quả: khử trùng theo IP. Nếu 1 IP được nhiều probe cùng tìm thấy (vd vừa trả lời ONVIF
 * vừa có HTTP snapshot lộ ra), ƯU TIÊN giữ kết quả có snapshotUrl không rỗng (thường là
 * HttpSnapshotProbe, vì nó đã xác nhận được ảnh thật) — nhưng vẫn giữ lại onvifXAddr/protocol
 * gốc nếu bản ONVIF đến trước đã có, để không mất thông tin capability đã biết.
 */
@Singleton
class CameraDiscoveryEngine @Inject constructor(
    private val onvifProbe: OnvifWsDiscoveryProbe,
    private val vendorUdpProbe: VendorUdpDiscoveryProbe,
    private val httpSnapshotProbe: HttpSnapshotProbe,
    private val logger: Logger,
) {
    /**
     * Điểm vào chính — chạy tuần tự các probe theo thứ tự ưu tiên ở trên, gộp kết quả cuối cùng.
     * Chạy TUẦN TỰ (không song song giữa các probe) có chủ đích: OnvifWsDiscoveryProbe và
     * HttpSnapshotProbe đều là I/O nặng trên cùng 1 Wi-Fi chip của điện thoại — chạy song song dễ
     * gây nhiễu lẫn nhau (giống lý do MAX_CONCURRENT_SCANS giới hạn concurrency trong
     * HttpSnapshotProbe). Tổng thời gian dài hơn 1 chút nhưng ổn định hơn trên máy tầm trung/thấp.
     *
     * @param onProgress callback tiến độ tổng — quy đổi tiến độ của từng probe về 1 thang chung để
     * UI (DiscoveryState.Scanning) hiển thị mượt xuyên suốt nhiều probe thay vì nhảy về 0 giữa chừng.
     */
    suspend fun discoverCameras(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<DiscoveredCamera> {
        val merged = LinkedHashMap<String, DiscoveredCamera>() // key = IP

        val probes = listOf(onvifProbe, vendorUdpProbe, httpSnapshotProbe)
        // Trọng số ước lượng thời gian tương đối giữa các probe — chỉ để chia thang tiến độ hiển
        // thị cho mượt, không ảnh hưởng logic quét thật. HttpSnapshotProbe nặng nhất nên chiếm
        // phần lớn thang điểm.
        val weights = listOf(1, 1, 8)
        val totalWeight = weights.sum()
        var completedWeight = 0

        for ((index, probe) in probes.withIndex()) {
            logger.i("CameraDiscoveryEngine", "▶️ Bắt đầu probe: ${probe.name}")
            val baseCompleted = completedWeight
            val probeWeight = weights[index]

            val results = try {
                probe.discover { current, total ->
                    val fraction = if (total > 0) current.toDouble() / total else 1.0
                    val displayCurrent = baseCompleted + (fraction * probeWeight).toInt()
                    onProgress?.invoke(displayCurrent, totalWeight)
                }
            } catch (e: Exception) {
                // Hợp đồng CameraProbe yêu cầu tự bắt lỗi nội bộ, nhưng bọc thêm 1 lớp ở đây để
                // 1 probe lỗi ngoài dự kiến (implementation sai hợp đồng) không làm hỏng các
                // probe còn lại trong danh sách.
                logger.e("CameraDiscoveryEngine", "Probe ${probe.name} lỗi ngoài dự kiến: ${e.message}", e)
                emptyList()
            }

            logger.i("CameraDiscoveryEngine", "✅ ${probe.name} xong — tìm thấy ${results.size} camera")
            mergeResults(merged, results)

            completedWeight = baseCompleted + probeWeight
            onProgress?.invoke(completedWeight, totalWeight)
        }

        logger.i("CameraDiscoveryEngine", "🏁 Tổng hợp xong — ${merged.size} camera duy nhất (đã khử trùng theo IP)")
        return merged.values.toList()
    }

    private fun mergeResults(merged: LinkedHashMap<String, DiscoveredCamera>, newResults: List<DiscoveredCamera>) {
        for (cam in newResults) {
            val existing = merged[cam.ip]
            if (existing == null) {
                merged[cam.ip] = cam
                continue
            }

            // Đã có kết quả từ probe khác cho cùng IP — ưu tiên giữ bản có snapshotUrl không rỗng
            // (đã xác nhận ảnh thật), nhưng bổ sung onvifXAddr/protocol từ bản mới nếu bản cũ chưa có,
            // để không mất thông tin capability đã biết từ probe khác.
            val preferExisting = existing.snapshotUrl.isNotBlank() || cam.snapshotUrl.isBlank()
            val winner = if (preferExisting) existing else cam
            val loser = if (preferExisting) cam else existing

            merged[cam.ip] = winner.copy(
                onvifXAddr = winner.onvifXAddr ?: loser.onvifXAddr
            )
        }
    }
}
