package com.aichatvn.agent.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu trạng thái 1 lệnh điều khiển đang chờ người dùng bổ sung thông tin còn thiếu.
 *
 * ✅ ĐÃ SỬA (per-user scoping): thêm field `username` — trước đây toàn bộ pending
 * intents nằm chung 1 hàng đợi global trong ChatHistoryManager, khiến lệnh dở dang
 * của user A (vd Facebook) có thể bị hủy/ghi đè/trả lời nhầm bởi tin nhắn của user B
 * (vd Telegram) đến gần như cùng lúc, đúng bối cảnh omnichannel gateway của app.
 * `username` giờ là 1 phần định danh của pending, dùng để lọc mọi truy vấn/xóa.
 *
 * ✅ ĐÃ SỬA (trước đó): tách riêng 2 khái niệm thời gian, trước đây gộp chung vào 1 field
 * `createdAt` gây ra 2 lỗi trái ngược nhau cùng lúc:
 *   - `createdAt` giờ CỐ ĐỊNH = thời điểm pending này được tạo mới lần đầu (ở IntentExecutor.kt),
 *     dùng để SẮP THỨ TỰ hàng đợi ổn định qua getActivePendingIntents() — không đổi dù pending
 *     có tiến triển dở dang qua nhiều lượt.
 *   - `lastInteractionAt` = thời điểm gần nhất pending có tiến triển (người dùng vừa điền được
 *     1 tham số), dùng riêng cho TTL cleanup — PHẢI cập nhật mỗi lần có tương tác, để không hủy
 *     oan 1 pending đang được người dùng tích cực trả lời dở dang.
 */
data class PendingIntent(
    val pluginId: String,
    val action: String,
    val knownParams: Map<String, Any>,
    val missingParams: List<String>,
    val askedQuestion: String,
    val username: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastInteractionAt: Long = System.currentTimeMillis()
)

/**
 * ✅ ĐÃ SỬA (per-user scoping toàn diện): TẤT CẢ state trong class này — lịch sử hội thoại,
 * thiết bị vừa nhắc tới, hàng đợi pending, và khóa điều khiển riêng biệt (lockedControl) —
 * trước đây là GLOBAL (dùng chung cho mọi user/kênh chat), giờ được khoanh vùng theo
 * `username`. Mỗi hàm public giờ nhận thêm tham số `username` để đọc/ghi đúng vùng nhớ của
 * riêng user đó, tránh rò rỉ ngữ cảnh chéo giữa các user khác nhau đến gần như đồng thời.
 */
@Singleton
class ChatHistoryManager @Inject constructor() {

    companion object {
        private const val PENDING_INTENT_TTL_MS = 3 * 60 * 1000L // 3 phút
        private const val MAX_HISTORY_TURNS = 2
    }

    private val historyByUser = mutableMapOf<String, MutableList<Pair<String, String>>>() // User to AI, theo username
    private val lastDeviceByUser = mutableMapOf<String, String>()

    // Bộ đệm danh sách đa lệnh dở dang song song, của TẤT CẢ user — lọc theo pending.username khi truy vấn
    private val pendingIntents = mutableListOf<PendingIntent>()

    // Hàng đợi lưu giữ các lệnh vừa bị hủy/hết hạn để hiển thị thông báo cho người dùng — mỗi item tự mang username riêng
    private val expiredIntents = mutableListOf<PendingIntent>()

    // Quản lý trạng thái khóa cứng điều khiển 1 plugin (Không dùng timeout), theo từng user
    data class LockedControl(val pluginId: String)
    private val lockedControlByUser = mutableMapOf<String, LockedControl>()
    private val pendingLockRequestByUser = mutableMapOf<String, String>()

    @Synchronized
    fun setPendingLockRequest(username: String, pluginId: String) {
        pendingLockRequestByUser[username] = pluginId
    }

    @Synchronized
    fun getPendingLockRequest(username: String): String? {
        return pendingLockRequestByUser[username]
    }

    @Synchronized
    fun clearLockRequest(username: String) {
        pendingLockRequestByUser.remove(username)
    }

    @Synchronized
    fun lockPlugin(username: String, pluginId: String) {
        lockedControlByUser[username] = LockedControl(pluginId)
    }

    @Synchronized
    fun getLockedPlugin(username: String = "default_user"): String? {
        return lockedControlByUser[username]?.pluginId
    }

    @Synchronized
    fun unlockPlugin(username: String) {
        lockedControlByUser.remove(username)
    }

    @Synchronized
    fun addTurn(username: String, userMessage: String, aiResponse: String) {
        val h = historyByUser.getOrPut(username) { mutableListOf() }
        if (h.size >= MAX_HISTORY_TURNS) {
            h.removeAt(0)
        }
        h.add(Pair(userMessage, aiResponse))
    }

    @Synchronized
    fun updateLastDevice(username: String, deviceId: String) {
        lastDeviceByUser[username] = deviceId
    }

    @Synchronized
    fun getLastMentionedDevice(username: String): String? {
        return lastDeviceByUser[username]
    }

    @Synchronized
    fun getRecentTurnsAsText(username: String): String {
        val h = historyByUser[username]
        if (h.isNullOrEmpty()) return "No history."
        return h.joinToString("\n") { "User: ${it.first}\nAI: ${it.second}" }
    }

    // Thêm một lệnh dở dang vào hàng đợi của ĐÚNG user (intent.username), tránh ghi đè lệnh khác của cùng user
    @Synchronized
    fun addPendingIntent(intent: PendingIntent) {
        pendingIntents.removeAll {
            it.username == intent.username && it.pluginId == intent.pluginId && it.action == intent.action
        }
        pendingIntents.add(intent)
    }

    // Xóa riêng lẻ một lệnh dở dang của ĐÚNG user khi đã xử lý xong
    @Synchronized
    fun removePendingIntent(username: String, pluginId: String, action: String) {
        pendingIntents.removeAll { it.username == username && it.pluginId == pluginId && it.action == action }
    }

    /**
     * Hàm dùng chung thực hiện lọc dọn dẹp các lệnh đã quá thời gian chờ (TTL 3 phút), áp dụng
     * cho tất cả user cùng lúc — an toàn vì chỉ so sánh `lastInteractionAt` độc lập theo từng item.
     *
     * ✅ ĐÃ SỬA: dùng `lastInteractionAt` (lần tương tác gần nhất) thay vì `createdAt` (lần tạo
     * đầu tiên) để tính hạn. Trước đây dùng `createdAt` — nếu để nguyên `createdAt` không đổi
     * qua các lượt (như bây giờ, cho mục đích sắp thứ tự hàng đợi), một pending đang được người
     * dùng tích cực trả lời dở dang qua nhiều lượt (vd điền subject rồi body) có thể vượt quá 3
     * phút TÍNH TỪ LÚC TẠO dù vẫn đang có tiến triển thật, và bị hủy oan giữa chừng.
     */
    @Synchronized
    private fun performTtlCleanup(now: Long) {
        val (expired, active) = pendingIntents.partition { now - it.lastInteractionAt > PENDING_INTENT_TTL_MS }
        if (expired.isNotEmpty()) {
            pendingIntents.removeAll(expired)
            expiredIntents.addAll(expired)
        }
    }

    /**
     * ✅ MỚI: lọc theo ĐÚNG `username` rồi sắp xếp theo `createdAt` (thời điểm tạo đầu tiên,
     * KHÔNG đổi qua các lượt cập nhật) trước khi trả về, đảm bảo mỗi user chỉ thấy hàng đợi
     * của riêng mình và thứ tự hàng đợi đó ỔN ĐỊNH.
     *
     * LÝ DO sort theo createdAt: `addPendingIntent()` luôn `removeAll` rồi `add` vào CUỐI list mỗi
     * khi 1 pending được cập nhật (kể cả khi chỉ đang tiếp tục dở dang, không phải tạo mới) — nên
     * nếu trả về theo thứ tự vật lý trong list, pending nào vừa "nhích" được 1 bước sẽ bị đẩy xuống
     * cuối hàng đợi, dù nó lẽ ra đang đứng đầu. Sort theo `createdAt` (cố định, không bị chạm vào
     * khi tiếp tục dở dang — xem PendingIntentResolver.kt) khôi phục đúng thứ tự FIFO.
     */
    @Synchronized
    fun getActivePendingIntents(username: String): List<PendingIntent> {
        performTtlCleanup(System.currentTimeMillis())
        return pendingIntents.filter { it.username == username }.sortedBy { it.createdAt }
    }

    @Synchronized
    fun addExpiredNotification(pending: PendingIntent) {
        expiredIntents.add(pending)
    }

    @Synchronized
    fun popExpiredNotifications(username: String): List<PendingIntent> {
        val mine = expiredIntents.filter { it.username == username }
        expiredIntents.removeAll(mine)
        return mine
    }

    /**
     * Trả về thông báo lý do hủy tiến trình dở dang (Nếu có) cho ĐÚNG user hỏi
     */
    @Synchronized
    fun popExpiredNotificationMessage(username: String, plugins: Set<com.aichatvn.agent.core.plugin.Plugin>): String? {
        // Thực hiện cleanup trước khi lấy danh sách thông báo
        performTtlCleanup(System.currentTimeMillis())

        val mine = expiredIntents.filter { it.username == username }
        if (mine.isEmpty()) return null

        val msg = mine.joinToString("\n") { expired ->
            val targetPlugin = plugins.find { it.manifest.id == expired.pluginId }
            val displayName = targetPlugin?.manifest?.name ?: expired.pluginId

            // Sửa Bug 1: Phân biệt chính xác lý do thông qua cờ cancelReason thay vì so sánh chuỗi
            if (expired.knownParams["_cancelReason"] == "no_progress") {
                "⚠️ Đã hủy yêu cầu \"$displayName\" do không nhận diện được câu trả lời liên tiếp."
            } else {
                "⚠️ Đã tự động hủy yêu cầu \"$displayName\" do quá thời gian phản hồi (3 phút)."
            }
        }
        expiredIntents.removeAll(mine)
        return msg
    }

    // Cầu nối tương thích ngược cho các lời gọi cũ lẻ tẻ
    @Synchronized
    fun getActivePendingIntent(username: String): PendingIntent? {
        return getActivePendingIntents(username).firstOrNull()
    }

    @Synchronized
    fun setPendingIntent(intent: PendingIntent) {
        addPendingIntent(intent)
    }

    @Synchronized
    fun clearPendingIntent(username: String) {
        pendingIntents.removeAll { it.username == username }
    }

    // Xóa toàn bộ trạng thái của riêng 1 user (vd user logout / đổi kênh hẳn) — KHÔNG đụng tới user khác
    @Synchronized
    fun clearUser(username: String) {
        historyByUser.remove(username)
        lastDeviceByUser.remove(username)
        pendingIntents.removeAll { it.username == username }
        expiredIntents.removeAll { it.username == username }
        lockedControlByUser.remove(username)
        pendingLockRequestByUser.remove(username)
    }

    // Xóa TOÀN BỘ trạng thái của TẤT CẢ user — chỉ dùng cho reset toàn hệ thống (vd factory reset app)
    @Synchronized
    fun clear() {
        historyByUser.clear()
        lastDeviceByUser.clear()
        pendingIntents.clear()
        lockedControlByUser.clear()
        pendingLockRequestByUser.clear()
        expiredIntents.clear()
    }
}