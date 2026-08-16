package com.aichatvn.agent.tools.camera.discovery

import com.aichatvn.agent.tools.camera.DiscoveredCamera

/**
 * Hợp đồng chung cho MỌI cách dò tìm camera trên LAN — mỗi implementation là 1 "kênh" độc lập
 * (ONVIF WS-Discovery, HTTP snapshot port-scan, vendor UDP riêng...). [CameraDiscoveryEngine]
 * chạy tất cả probe đã đăng ký rồi gộp + khử trùng kết quả theo IP.
 *
 * ⚠️ CHỦ Ý kiến trúc: 1 probe CHỈ có nhiệm vụ TÌM & XÁC NHẬN camera tồn tại (identity/IP/protocol
 * capability) — KHÔNG chịu trách nhiệm lấy ảnh/stream thật. Việc lấy ảnh/stream là của
 * CameraCapabilityProber (đã có sẵn, dùng cho camera đã biết IP) hoặc CameraConnectionManager
 * (chưa có, dự kiến track sau). Tách 2 việc này để probe mới (vd VendorUdpDiscoveryProbe) không
 * cần tự implement lại logic fetch/validate JPEG hay RTSP DESCRIBE.
 */
interface CameraProbe {
    /** Tên hiển thị trong log — vd "ONVIF WS-Discovery", "HTTP Snapshot Scan". */
    val name: String

    /**
     * Chạy probe, trả về danh sách camera tìm được. KHÔNG được throw ra ngoài — lỗi nội bộ phải
     * tự bắt và trả emptyList(), giống hợp đồng cũ của CameraAutoDiscoveryTool.discoverCameras(),
     * để 1 probe lỗi không làm hỏng kết quả của các probe khác chạy cùng lượt.
     *
     * @param onProgress callback tuỳ chọn để UI hiển thị tiến độ — probe nào không có khái niệm
     * tiến độ rõ ràng (vd ONVIF multicast chỉ có 1 bước "đang chờ phản hồi") có thể gọi 1 lần duy
     * nhất hoặc bỏ qua hoàn toàn.
     */
    suspend fun discover(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<DiscoveredCamera>
}
