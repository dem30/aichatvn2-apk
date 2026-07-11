package com.aichatvn.agent.core

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ============================================================================
 * TẦNG 0 - DIALOG MANAGER
 * ============================================================================
 *
 * Đứng trước toàn bộ Router (Tầng 1 -> Tầng 5) hiện tại của AgentKernel.
 * File này HOÀN TOÀN ĐỘC LẬP — không import, không sửa, không phụ thuộc vào
 * AgentKernel.kt, ChatHistoryManager.kt, TrainingSkill.kt hay Plugin Manifest.
 *
 * AgentKernel (ở bước refactor sau) sẽ gọi vào DialogManager qua interface
 * `DialogManager` bên dưới, không truy cập trực tiếp implementation.
 *
 * Trách nhiệm của Tầng 0:
 *   1. Conversation Focus   - nhớ đối tượng đang được nói tới (không chỉ "lastDevice")
 *   2. Pending Manager      - quản lý trạng thái đang chờ người dùng trả lời
 *   3. Cancel Resolver      - chỉ coi là "hủy" khi thực sự đang Pending
 *   4. Pronoun Resolver     - viết lại câu, thay đại từ ("nó", "cái đó"...) bằng
 *                              tên cụ thể lấy từ Conversation Focus, TRƯỚC khi
 *                              câu được đưa vào Tầng 1-5.
 *   5. Chat Classification  - phân loại lượt hội thoại để lọc lịch sử gửi cho LLM
 *   6. Context Builder      - dựng context có cấu trúc (không phải "User:/Assistant:")
 * ============================================================================
 */

// ============================================================================
// DOMAIN MODELS
// ============================================================================

/**
 * Đối tượng đang được "focus" trong hội thoại — đầy đủ hơn một "lastDevice" đơn lẻ.
 * Dùng để Pronoun Resolver suy luận "nó", "cái đó", "thiết bị đó" trỏ về đâu.
 */
data class ConversationFocus(
    val pluginId: String? = null,
    val action: String? = null,
    val deviceId: String? = null,
    val cameraId: String? = null,
    val scheduleId: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val timestamp: Long = 0L,
    val confidence: Double = 0.0
) {
    fun isEmpty(): Boolean =
        pluginId == null && deviceId == null && cameraId == null && scheduleId == null

    /** Focus được coi là "còn hiệu lực" nếu chưa quá cũ (mặc định 5 phút). */
    fun isFresh(nowMillis: Long, maxAgeMillis: Long = 5 * 60 * 1000L): Boolean {
        if (timestamp <= 0L) return false
        return (nowMillis - timestamp) <= maxAgeMillis
    }
}

/**
 * Trạng thái Pending tối giản mà DialogManager cần biết để Cancel Resolver
 * và Pronoun Resolver hoạt động đúng. Đây KHÔNG phải bản thay thế cho
 * PendingIntent của AgentKernel — chỉ là input/output tối thiểu để Tầng 0
 * giao tiếp với phần Pending thật sự (sẽ được nối ở bước refactor sau).
 */
data class PendingState(
    val isActive: Boolean,
    val pluginId: String? = null,
    val action: String? = null,
    val missingParams: List<String> = emptyList(),
    val askedQuestion: String? = null
)

/** Phân loại một lượt hội thoại trong lịch sử. */
enum class TurnType {
    DEVICE_COMMAND,
    DEVICE_RESULT,
    CHAT,
    QUESTION,
    SYSTEM
}

/** Một lượt hội thoại đã được gắn nhãn loại. */
data class ClassifiedTurn(
    val role: String,           // "user" | "assistant"
    val content: String,
    val type: TurnType,
    val timestamp: Long = 0L
)

/** Loại hội thoại hiện tại — dùng để Context Builder lọc lịch sử phù hợp. */
enum class ConversationType {
    DEVICE_CONTROL,
    CHAT,
    UNKNOWN
}

/** Kết quả của Pronoun Resolver. */
data class PronounResolution(
    val rewrittenMessage: String,
    val wasResolved: Boolean,
    val resolvedFrom: String? = null,   // đại từ gốc đã thay thế
    val resolvedTo: String? = null      // giá trị cụ thể đã thay vào
)

/** Kết quả của Cancel Resolver. */
sealed class CancelDecision {
    /** Không liên quan gì đến hủy — câu phải đi tiếp xuống Router như bình thường. */
    object NotCancel : CancelDecision()

    /** Có từ ngữ "hủy" nhưng KHÔNG có Pending -> phải coi là lệnh thường, đẩy xuống Router. */
    object NotCancelNoPending : CancelDecision()

    /** Thực sự là lệnh hủy Pending hiện tại. */
    data class CancelPending(val pluginId: String?, val action: String?) : CancelDecision()
}

/** Context có cấu trúc để truyền cho LLM, thay cho lịch sử thô "User:/Assistant:". */
data class DialogContext(
    val focusDevice: String?,
    val focusCamera: String?,
    val focusSchedule: String?,
    val lastPluginId: String?,
    val lastAction: String?,
    val pendingParam: String?,
    val pendingQuestion: String?,
    val conversationType: ConversationType,
    val relevantHistory: List<ClassifiedTurn>
) {
    /**
     * Render context thành text có cấu trúc rõ ràng (key: value theo block),
     * KHÔNG dùng định dạng "User:\n...\nAssistant:\n...".
     */
    fun toPromptString(): String = buildString {
        if (focusDevice != null) append("Focus Device:\n$focusDevice\n\n")
        if (focusCamera != null) append("Focus Camera:\n$focusCamera\n\n")
        if (focusSchedule != null) append("Focus Schedule:\n$focusSchedule\n\n")
        if (lastAction != null) append("Last Action:\n$lastAction\n\n")
        if (lastPluginId != null) append("Last Plugin:\n$lastPluginId\n\n")
        if (pendingParam != null) append("Pending:\n$pendingParam\n\n")
        if (pendingQuestion != null) append("Pending Question:\n$pendingQuestion\n\n")
        append("Conversation Type:\n${conversationType.name.lowercase()}\n")
        if (relevantHistory.isNotEmpty()) {
            append("\nRelevant History:\n")
            relevantHistory.forEach { turn ->
                append("[${turn.type.name}] ${turn.role}: ${turn.content}\n")
            }
        }
    }.trim()
}

// ============================================================================
// PUBLIC INTERFACE — đây là điểm AgentKernel sẽ gọi vào (ở bước refactor sau)
// ============================================================================

interface DialogManager {

    /** Lấy Focus hiện tại của một phiên (theo username). */
    fun getFocus(username: String): ConversationFocus

    /** Cập nhật Focus sau khi một intent được resolve/thực thi thành công. */
    fun updateFocus(username: String, focus: ConversationFocus)

    /** Xóa Focus (vd. khi người dùng đổi chủ đề hẳn sang chat thường). */
    fun clearFocus(username: String)

    /**
     * Quyết định một câu nói có phải là "hủy Pending" hay không.
     * Bắt buộc truyền vào trạng thái Pending thật sự (lấy từ ChatHistoryManager ở bước nối sau).
     */
    fun resolveCancel(userMessage: String, pending: PendingState): CancelDecision

    /**
     * Viết lại câu nói, thay các đại từ ("nó", "cái đó", "thiết bị đó"...) bằng
     * giá trị cụ thể lấy từ Focus. Nếu không tìm thấy đại từ nào hoặc Focus rỗng/cũ,
     * trả về nguyên văn câu gốc với wasResolved = false.
     */
    fun resolvePronoun(userMessage: String, username: String, nowMillis: Long): PronounResolution

    /** Phân loại một lượt hội thoại để phục vụ Context Builder. */
    fun classifyTurn(role: String, content: String, timestamp: Long): ClassifiedTurn

    /**
     * Dựng context có cấu trúc để gửi cho LLM, dựa trên Focus + Pending + lịch sử
     * đã được lọc theo loại hội thoại hiện tại (không gửi nguyên lịch sử thô).
     */
    fun buildContext(
        username: String,
        pending: PendingState,
        conversationType: ConversationType,
        history: List<ClassifiedTurn>,
        maxHistoryTurns: Int = 6
    ): DialogContext
}

// ============================================================================
// IMPLEMENTATION
// ============================================================================

@Singleton
class DialogManagerImpl @Inject constructor() : DialogManager {


  companion object {
        private val SPACE_REGEX = Regex("\\s+")

        // Các đại từ tham chiếu, sắp xếp từ dài -> ngắn để ưu tiên khớp cụm dài trước.
        // Mỗi entry map cụm đại từ -> loại đối tượng nó ám chỉ ("device" | "camera" | "schedule" | "generic").
        // ✅ ĐÃ SỬA: private -> internal để AgentKernel đọc trực tiếp khi ghi TraceNode
        // cho màn Diagnostics/Pipeline Graph — không có nghĩa vụ gì khác thay đổi,
        // "internal" vẫn giới hạn truy cập trong cùng module app, không lộ ra bên ngoài.
        internal val PRONOUN_MAP: List<Pair<String, String>> = listOf(
            "thiết bị đó" to "device",
            "thiết bị này" to "device",
            "thiết bị kia" to "device",
            "camera đó" to "camera",
            "camera này" to "camera",
            "camera kia" to "camera",
            "lịch đó" to "schedule",
            "lịch này" to "schedule",
            "cái đó" to "generic",
            "cái kia" to "generic",
            "cái này" to "generic",
            "nó" to "generic",
            "đó" to "generic"
        ).sortedByDescending { it.first.length }

        // Từ ngữ thể hiện ý định "hủy" — chỉ có hiệu lực khi đang Pending.
        // ✅ ĐÃ SỬA: private -> internal (lý do như trên)
        internal val CANCEL_PHRASES = setOf(
            "hủy", "huỷ", "thôi", "bỏ qua", "không làm nữa", "đừng làm nữa", "dừng lại", "dừng"
        )

        // Các từ này nếu xuất hiện CÙNG với từ hủy thì chứng tỏ câu KHÔNG phải ý định
        // hủy Pending, mà là một lệnh thiết bị khác có chứa từ "hủy"/"thôi" như một phần
        // ngữ nghĩa (vd. "hủy lịch tưới cây"). Ta coi đây là dấu hiệu câu có object cụ thể
        // đi kèm -> không phải lệnh hủy ngữ cảnh (context-free cancel).
        // LƯU Ý: không dùng Regex("\\b...\\b") vì \b không nhận đúng ranh giới với
        // ký tự tiếng Việt có dấu — dùng containsWholePhrase() tự viết bên dưới thay thế.
        // ✅ ĐÃ SỬA: private -> internal (lý do như trên)
        internal val OBJECT_INDICATOR_WORDS = setOf(
            "lịch", "đèn", "quạt", "máy", "camera", "thông báo", "email", "báo thức", "hẹn giờ"
        )
    }

    

    /** Focus theo từng username, threadsafe. */
    private val focusStore = ConcurrentHashMap<String, ConversationFocus>()

    // ------------------------------------------------------------------
    // 1. CONVERSATION FOCUS
    // ------------------------------------------------------------------

    override fun getFocus(username: String): ConversationFocus =
        focusStore[username] ?: ConversationFocus()

    override fun updateFocus(username: String, focus: ConversationFocus) {
        focusStore[username] = focus
    }

    override fun clearFocus(username: String) {
        focusStore.remove(username)
    }

    // ------------------------------------------------------------------
    // 2 & 3. PENDING MANAGER (state được truyền vào từ ngoài) + CANCEL RESOLVER
    // ------------------------------------------------------------------

    override fun resolveCancel(userMessage: String, pending: PendingState): CancelDecision {
        val normalized = normalizeVietnamese(userMessage)

        val matchedCancelPhrase = CANCEL_PHRASES.any { phrase ->
            val normalizedPhrase = normalizeVietnamese(phrase)
            if (containsWholePhrase(normalized, normalizedPhrase)) {
                // Khắc phục lỗi đồng hóa dấu tiếng Việt (ví dụ "dừng" -> "dung" trùng với "nội dung", "thôi" -> "thoi" trùng với "thời gian")
                when (normalizedPhrase) {
                    "dung" -> {
                        val originalWords = userMessage.lowercase().split(SPACE_REGEX)
                        originalWords.any { it == "dừng" || it == "dưng" } // Chỉ chấp nhận khi từ gốc thực sự là "dừng" hoặc "dưng"
                    }
                    "thoi" -> {
                        val originalWords = userMessage.lowercase().split(SPACE_REGEX)
                        originalWords.any { it == "thôi" } // Chỉ chấp nhận khi từ gốc thực sự là "thôi"
                    }
                    else -> true
                }
            } else {
                false
            }
        }

        if (!matchedCancelPhrase) return CancelDecision.NotCancel

        // Có từ "hủy"/"thôi"... nhưng KHÔNG đang Pending -> đây chắc chắn là lệnh
        // bình thường (vd "hủy lịch tưới cây" khi không có gì đang chờ).
        if (!pending.isActive) return CancelDecision.NotCancelNoPending

        // Đang Pending thật sự. Nhưng nếu câu còn chứa một "object indicator" rõ ràng
        // (vd "hủy LỊCH tưới cây", "hủy ĐÈN phòng khách") thì nhiều khả năng người
        // dùng đang ra một lệnh hủy có chủ đích cụ thể, không phải hủy Pending hiện tại
        // một cách context-free. Trường hợp này vẫn ưu tiên coi là hủy Pending CHỈ KHI
        // toàn câu chỉ gồm cụm hủy ngắn gọn (không có object đi kèm).
        val hasExplicitObject = OBJECT_INDICATOR_WORDS.any { word ->
            containsWholePhrase(normalized, normalizeVietnamese(word))
        }
        val isShortStandaloneCancel = normalized.trim().split(SPACE_REGEX).size <= 3

        return if (hasExplicitObject && !isShortStandaloneCancel) {
            // "hủy lịch tưới cây" trong lúc đang pending việc khác -> không tự ý
            // suy diễn là hủy pending, đẩy xuống Router để xử lý như lệnh riêng.
            CancelDecision.NotCancelNoPending
        } else {
            CancelDecision.CancelPending(pending.pluginId, pending.action)
        }
    }

    // ------------------------------------------------------------------
    // 4. PRONOUN RESOLVER
    // ------------------------------------------------------------------

    override fun resolvePronoun(userMessage: String, username: String, nowMillis: Long): PronounResolution {
        val focus = getFocus(username)
        if (focus.isEmpty() || !focus.isFresh(nowMillis)) {
            return PronounResolution(userMessage, wasResolved = false)
        }

        val normalizedMsg = normalizeVietnamese(userMessage)

        for ((pronoun, kind) in PRONOUN_MAP) {
            val pronounNorm = normalizeVietnamese(pronoun)
            val matchRange = findWholePhraseRange(normalizedMsg, pronounNorm) ?: continue

            val replacement = when (kind) {
                "device" -> focus.deviceId
                "camera" -> focus.cameraId
                "schedule" -> focus.scheduleId
                else -> focus.deviceId ?: focus.cameraId ?: focus.scheduleId
            } ?: continue

            val rewritten = replaceWholePhraseInOriginal(userMessage, pronoun, replacement)
                ?: continue

            return PronounResolution(
                rewrittenMessage = rewritten,
                wasResolved = true,
                resolvedFrom = pronoun,
                resolvedTo = replacement
            )
        }

        return PronounResolution(userMessage, wasResolved = false)
    }

    // ------------------------------------------------------------------
    // 5. CHAT CLASSIFICATION
    // ------------------------------------------------------------------

    override fun classifyTurn(role: String, content: String, timestamp: Long): ClassifiedTurn {
        val type = classifyContent(role, content)
        return ClassifiedTurn(role = role, content = content, type = type, timestamp = timestamp)
    }

    private fun classifyContent(role: String, content: String): TurnType {
        val normalized = normalizeVietnamese(content)

        val deviceResultMarkers = listOf("✅", "❌", "⚠️", "đã thực hiện", "đã bật", "đã tắt", "đã huỷ", "đã hủy")
        if (role == "assistant" && deviceResultMarkers.any { content.contains(it) || normalized.contains(normalizeVietnamese(it)) }) {
            return TurnType.DEVICE_RESULT
        }

        val deviceCommandMarkers = listOf(
            "bật", "tắt", "mở", "đóng", "đặt lịch", "hẹn giờ", "lên lịch",
            "xem camera", "gửi email", "thông báo"
        )
        if (role == "user" && deviceCommandMarkers.any { normalized.contains(normalizeVietnamese(it)) }) {
            return TurnType.DEVICE_COMMAND
        }

        if (role == "assistant" && content.trim().endsWith("?")) {
            return TurnType.QUESTION
        }
        if (role == "user" && content.trim().endsWith("?")) {
            return TurnType.QUESTION
        }

        if (role == "system") return TurnType.SYSTEM

        return TurnType.CHAT
    }

    // ------------------------------------------------------------------
    // 6. CONTEXT BUILDER
    // ------------------------------------------------------------------

    override fun buildContext(
        username: String,
        pending: PendingState,
        conversationType: ConversationType,
        history: List<ClassifiedTurn>,
        maxHistoryTurns: Int
    ): DialogContext {
        val focus = getFocus(username)

        // Chỉ giữ lại lịch sử PHÙ HỢP với loại hội thoại hiện tại.
        val relevantTypes: Set<TurnType> = when (conversationType) {
            ConversationType.DEVICE_CONTROL -> setOf(TurnType.DEVICE_COMMAND, TurnType.DEVICE_RESULT, TurnType.QUESTION)
            ConversationType.CHAT -> setOf(TurnType.CHAT, TurnType.QUESTION)
            ConversationType.UNKNOWN -> setOf(TurnType.DEVICE_COMMAND, TurnType.DEVICE_RESULT, TurnType.CHAT, TurnType.QUESTION)
        }

        val filteredHistory = history
            .filter { it.type in relevantTypes }
            .takeLast(maxHistoryTurns)

        return DialogContext(
            focusDevice = focus.deviceId,
            focusCamera = focus.cameraId,
            focusSchedule = focus.scheduleId,
            lastPluginId = focus.pluginId,
            lastAction = focus.action,
            pendingParam = pending.missingParams.firstOrNull(),
            pendingQuestion = pending.askedQuestion,
            conversationType = conversationType,
            relevantHistory = filteredHistory
        )
    }

    // ------------------------------------------------------------------
    // HELPERS — Unicode-safe Vietnamese text matching
    // ✅ ĐÃ SỬA: Toàn bộ 4 hàm (normalizeVietnamese, containsWholePhrase,
    // findWholePhraseRange, replaceWholePhraseInOriginal) trước đây cài đặt riêng
    // trong file này. Giờ ủy quyền sang VietnameseTextNormalizer (nguồn chân lý
    // duy nhất, dùng chung với StringSimilarityUtil và TrainingSkill) — logic giữ
    // NGUYÊN VẸN, chỉ đổi nơi cài đặt. Giữ nguyên tên hàm private để không phải
    // sửa các lời gọi khác trong file này (resolveCancel, resolvePronoun...).
    // ------------------------------------------------------------------

    private fun normalizeVietnamese(text: String): String =
        com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(text)

    private fun containsWholePhrase(text: String, phrase: String): Boolean =
        com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(text, phrase)

    private fun findWholePhraseRange(text: String, phrase: String): IntRange? =
        com.aichatvn.agent.core.text.VietnameseTextNormalizer.findWholePhraseRange(text, phrase)

    private fun replaceWholePhraseInOriginal(original: String, pronoun: String, replacement: String): String? =
        com.aichatvn.agent.core.text.VietnameseTextNormalizer.replaceWholePhraseInOriginal(original, pronoun, replacement)
}
