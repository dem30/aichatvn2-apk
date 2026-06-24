package com.aichatvn.agent.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu trạng thái 1 lệnh điều khiển đang chờ người dùng bổ sung thông tin còn thiếu.
 *
 * pluginId / action  : lệnh đích đã được router xác định chắc chắn (không cần đoán lại)
 * knownParams        : các param đã có giá trị từ lượt hỏi trước
 * missingParams      : tên các param còn thiếu, cần lượt trả lời tiếp theo điền vào
 * askedQuestion      : câu hỏi đã hỏi user (để model "điền param" có ngữ cảnh đối chiếu)
 * createdAt          : dùng để tự hết hạn (PENDING_INTENT_TTL_MS) nếu user không trả lời kịp
 */
data class PendingIntent(
    val pluginId: String,
    val action: String,
    val knownParams: Map<String, Any>,
    val missingParams: List<String>,
    val askedQuestion: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Lưu ngắn hạn (in-memory) 2 lượt hội thoại gần nhất + thiết bị được nhắc đến cuối cùng
 * + 1 pending intent (lệnh đang chờ bổ sung param, nếu có).
 * Mục đích: giúp LocalRouterEngine giải quyết được các từ chỉ định mơ hồ như "nó", "cái đó"
 * mà không cần lưu toàn bộ lịch sử (tiết kiệm token cho model local nhỏ).
 *
 * Lưu ý: đây là @Singleton dùng chung cho toàn app -> không phân biệt theo username.
 * Nếu app hỗ trợ nhiều người dùng đồng thời, cân nhắc khoá theo username.
 */
@Singleton
class ChatHistoryManager @Inject constructor() {

    companion object {
        /** Sau thời gian này (ms) mà user chưa trả lời, pending intent tự bị bỏ —
         *  tránh lượt chat hoàn toàn không liên quan bị "ghép nhầm" vào lệnh cũ. */
        private const val PENDING_INTENT_TTL_MS = 3 * 60 * 1000L // 3 phút
    }

    private val history = mutableListOf<Pair<String, String>>() // User to AI

    var lastMentionedDeviceId: String? = null
        private set

    private var pendingIntent: PendingIntent? = null

    @Synchronized
    fun addTurn(userMessage: String, aiResponse: String) {
        if (history.size >= 2) {
            history.removeAt(0) // Giữ tối đa 2 lượt gần nhất để tiết kiệm token cho model local
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

    /** Lưu lệnh đang chờ bổ sung param — gọi khi plugin trả NeedMoreInfo. */
    @Synchronized
    fun setPendingIntent(intent: PendingIntent) {
        this.pendingIntent = intent
    }

    /** Xoá pending intent — gọi khi đã đủ param, đã thực thi, hoặc user đổi sang chuyện khác. */
    @Synchronized
    fun clearPendingIntent() {
        this.pendingIntent = null
    }

    /**
     * Trả về pending intent nếu còn "tươi" (chưa quá PENDING_INTENT_TTL_MS kể từ lúc hỏi).
     * Tự xoá luôn nếu đã quá cũ, để lượt chat tiếp theo không bị ghép nhầm vào lệnh đã quá hạn.
     */
    @Synchronized
    fun getActivePendingIntent(): PendingIntent? {
        val p = pendingIntent ?: return null
        if (System.currentTimeMillis() - p.createdAt > PENDING_INTENT_TTL_MS) {
            pendingIntent = null
            return null
        }
        return p
    }

    @Synchronized
    fun clear() {
        history.clear()
        lastMentionedDeviceId = null
        pendingIntent = null
    }
}