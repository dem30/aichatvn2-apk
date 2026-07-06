package com.aichatvn.agent.core

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu trạng thái 1 lệnh điều khiển đang chờ người dùng bổ sung thông tin còn thiếu.
 */
data class PendingIntent(
    val pluginId: String,
    val action: String,
    val knownParams: Map<String, Any>,
    val missingParams: List<String>,
    val askedQuestion: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Singleton
class ChatHistoryManager @Inject constructor() {

    companion object {
        private const val PENDING_INTENT_TTL_MS = 3 * 60 * 1000L // 3 phút
    }

    private val history = mutableListOf<Pair<String, String>>() // User to AI
    var lastMentionedDeviceId: String? = null
        private set

    // Bộ đệm danh sách đa lệnh dở dang song song
    private val pendingIntents = mutableListOf<PendingIntent>()
    
    // Hàng đợi lưu giữ các lệnh vừa bị hủy/hết hạn để hiển thị thông báo cho người dùng
    private val expiredIntents = mutableListOf<PendingIntent>()

    // ✅ Quản lý trạng thái khóa cứng điều khiển theo từng USERNAME để tránh rò rỉ toàn cục [1]
    data class LockedControl(val pluginId: String)
    private val lockedControls = ConcurrentHashMap<String, LockedControl>()
    private val pendingLockRequests = ConcurrentHashMap<String, String>()

    // ✅ TƯƠNG THÍCH NGƯỢC: Khôi phục thuộc tính pendingLockRequest dạng Getter để AgentKernel cũ truy cập không bị lỗi [1]
    val pendingLockRequest: String?
        get() = getPendingLockRequest("default_user")

    // ─── CÁC PHƯƠNG THỨC NẠP CHỒNG (OVERLOADS) ĐỂ KHỚP CẢ HAI PHIÊN BẢN CODE ───

    fun setPendingLockRequest(pluginId: String) { 
        setPendingLockRequest("default_user", pluginId) 
    }

    fun setPendingLockRequest(username: String, pluginId: String) { 
        pendingLockRequests[username] = pluginId 
    }

    fun clearLockRequest() { 
        clearLockRequest("default_user") 
    }

    fun clearLockRequest(username: String) { 
        pendingLockRequests.remove(username) 
    }

    fun lockPlugin(pluginId: String) {
        lockPlugin("default_user", pluginId)
    }

    fun lockPlugin(username: String, pluginId: String) {
        lockedControls[username] = LockedControl(pluginId)
    }

    fun getLockedPlugin(): String? {
        return getLockedPlugin("default_user")
    }

    fun getLockedPlugin(username: String): String? {
        return lockedControls[username]?.pluginId
    }

    fun unlockPlugin() { 
        unlockPlugin("default_user") 
    }

    fun unlockPlugin(username: String) { 
        lockedControls.remove(username) 
    }

    fun getPendingLockRequest(): String? {
        return getPendingLockRequest("default_user")
    }

    fun getPendingLockRequest(username: String): String? {
        return pendingLockRequests[username]
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Synchronized
    fun addTurn(userMessage: String, aiResponse: String) {
        if (history.size >= 2) {
            history.removeAt(0)
        }
        history.add(Pair(userMessage, aiResponse))
    }

    @Synchronized
    fun updateLastDevice(deviceId: String) {
        this.lastMentionedDeviceId = deviceId
    }

    @Synchronized
    fun getRecentTurnsAsText(): String {
        if (history.isEmpty()) return "No history."
        return history.joinToString("\n") { "User: ${it.first}\nAI: ${it.second}" }
    }

    // Thêm một lệnh dở dang vào hàng đợi, tránh ghi đè lệnh khác
    @Synchronized
    fun addPendingIntent(intent: PendingIntent) {
        pendingIntents.removeAll { it.pluginId == intent.pluginId && it.action == intent.action }
        pendingIntents.add(intent)
    }

    // Xóa riêng lẻ một lệnh dở dang khi đã xử lý xong
    @Synchronized
    fun removePendingIntent(pluginId: String, action: String) {
        pendingIntents.removeAll { it.pluginId == pluginId && it.action == action }
    }

    /**
     * Hàm dùng chung thực hiện lọc dọn dẹp các lệnh đã quá thời gian chờ (TTL 3 phút)
     */
    @Synchronized
    private fun performTtlCleanup(now: Long) {
        val (expired, active) = pendingIntents.partition { now - it.createdAt > PENDING_INTENT_TTL_MS }
        if (expired.isNotEmpty()) {
            pendingIntents.removeAll(expired)
            expiredIntents.addAll(expired)
        }
    }

    @Synchronized
    fun getActivePendingIntents(): List<PendingIntent> {
        performTtlCleanup(System.currentTimeMillis())
        return pendingIntents.toList()
    }

    @Synchronized
    fun addExpiredNotification(pending: PendingIntent) {
        expiredIntents.add(pending)
    }

    @Synchronized
    fun popExpiredNotifications(): List<PendingIntent> {
        val expired = expiredIntents.toList()
        expiredIntents.clear()
        return expired
    }

    /**
     * Trả về thông báo lý do hủy tiến trình dở dang (Nếu có) cho người dùng cuối
     */
    @Synchronized
    fun popExpiredNotificationMessage(plugins: Set<com.aichatvn.agent.core.plugin.Plugin>): String? {
        // Thực hiện cleanup trước khi lấy danh sách thông báo
        performTtlCleanup(System.currentTimeMillis())

        if (expiredIntents.isEmpty()) return null

        val msg = expiredIntents.joinToString("\n") { expired ->
            val targetPlugin = plugins.find { it.manifest.id == expired.pluginId }
            val displayName = targetPlugin?.manifest?.name ?: expired.pluginId
            
            // Sửa Bug 1: Phân biệt chính xác lý do thông qua cờ cancelReason thay vì so sánh chuỗi
            if (expired.knownParams["_cancelReason"] == "no_progress") {
                "⚠️ Đã hủy yêu cầu \"$displayName\" do không nhận diện được câu trả lời liên tiếp."
            } else {
                "⚠️ Đã tự động hủy yêu cầu \"$displayName\" do quá thời gian phản hồi (3 phút)."
            }
        }
        expiredIntents.clear()
        return msg
    }

    // Cầu nối tương thích ngược cho các lời gọi cũ lẻ tẻ
    @Synchronized
    fun getActivePendingIntent(): PendingIntent? {
        return getActivePendingIntents().firstOrNull()
    }

    @Synchronized
    fun setPendingIntent(intent: PendingIntent) {
        addPendingIntent(intent)
    }

    @Synchronized
    fun clearPendingIntent() {
        pendingIntents.clear()
    }

    @Synchronized
    fun clear() {
        history.clear()
        lastMentionedDeviceId = null
        pendingIntents.clear()
        lockedControls.clear()
        pendingLockRequests.clear()
        expiredIntents.clear()
    }
}