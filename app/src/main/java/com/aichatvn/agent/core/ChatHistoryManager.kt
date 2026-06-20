package com.aichatvn.agent.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu ngắn hạn (in-memory) 2 lượt hội thoại gần nhất + thiết bị được nhắc đến cuối cùng.
 * Mục đích: giúp LocalRouterEngine giải quyết được các từ chỉ định mơ hồ như "nó", "cái đó"
 * mà không cần lưu toàn bộ lịch sử (tiết kiệm token cho model local nhỏ).
 *
 * Lưu ý: đây là @Singleton dùng chung cho toàn app -> không phân biệt theo username.
 * Nếu app hỗ trợ nhiều người dùng đồng thời, cân nhắc khoá theo username.
 */
@Singleton
class ChatHistoryManager @Inject constructor() {
    private val history = mutableListOf<Pair<String, String>>() // User to AI

    var lastMentionedDeviceId: String? = null
        private set

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

    @Synchronized
    fun clear() {
        history.clear()
        lastMentionedDeviceId = null
    }
}