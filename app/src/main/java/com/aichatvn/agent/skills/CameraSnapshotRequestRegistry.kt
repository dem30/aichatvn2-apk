package com.aichatvn.agent.skills

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraSnapshotRequestRegistry
 *
 * Khớp cặp camera_snapshot_request/camera_snapshot_response qua requestId — cần thiết vì
 * HouseholdEventPublisher/WebhookGatewayService chỉ có 1 kênh SSE broadcast DÙNG CHUNG cho cả
 * household (không phải request/response point-to-point thật), và có thể không có máy nào
 * cùng LAN trả lời cả (không ai ở nhà) — registry lo phần "chờ có giới hạn thời gian, tự dọn
 * nếu không ai trả lời" mà HouseholdEventPublisher/WebhookGatewayService không nên biết.
 *
 * KHÔNG lưu gì liên quan tới cameraId lâu dài — mỗi entry chỉ sống trong đúng 1 lần gọi
 * await(), tự xoá dù thành công, thất bại hay timeout.
 */
@Singleton
class CameraSnapshotRequestRegistry @Inject constructor() {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /**
     * Đăng ký chờ requestId rồi publish request (do caller tự làm — xem
     * CameraDetailViewModel.requestSnapshotFromCameraNode()) — trả về imageUrl nếu có máy cùng
     * LAN trả lời trong vòng [timeoutMs], hoặc null nếu hết giờ / không ai trả lời.
     */
    suspend fun await(requestId: String, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        pending[requestId] = deferred
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            pending.remove(requestId)
        }
    }

    /** Gọi từ WebhookGatewayService khi nhận được camera_snapshot_response khớp requestId. */
    fun complete(requestId: String, imageUrl: String?) {
        pending.remove(requestId)?.complete(imageUrl)
    }
}
