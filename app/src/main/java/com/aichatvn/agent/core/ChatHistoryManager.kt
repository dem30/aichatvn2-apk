package com.aichatvn.agent.core

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

    // Quản lý trạng thái khóa cứng điều khiển 1 plugin (Không dùng timeout)
    data class LockedControl(val pluginId: String)
    private var lockedControl: LockedControl? = null
    var pendingLockRequest: String? = null
        private set

    @Synchronized
    fun setPendingLockRequest(pluginId: String) { 
        pendingLockRequest = pluginId 
    }

    @Synchronized
    fun clearLockRequest() { 
        pendingLockRequest = null 
    }

    @Synchronized
    fun lockPlugin(pluginId: String) {
        lockedControl = LockedControl(pluginId)
    }

    @Synchronized
    fun getLockedPlugin(): String? {
        return lockedControl?.pluginId
    }

    @Synchronized
    fun unlockPlugin() { 
        lockedControl = null 
    }

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

    // Trả về toàn bộ danh sách lệnh dở dang còn hiệu lực
    @Synchronized
    fun getActivePendingIntents(): List<PendingIntent> {
        val now = System.currentTimeMillis()
        pendingIntents.removeAll { now - it.createdAt > PENDING_INTENT_TTL_MS }
        return pendingIntents.toList()
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
        lockedControl = null
        pendingLockRequest = null
    }
}