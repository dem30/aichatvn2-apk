package com.aichatvn.agent.utils

import com.aichatvn.agent.data.EventLogDao
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.SearchContract
import com.aichatvn.agent.data.model.QuestionType
import com.aichatvn.agent.data.model.AggregationType
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseSearchHelper @Inject constructor(
    private val eventLogDao: EventLogDao,
    private val timeRangeResolver: TimeRangeResolver,
    private val objectAliasResolver: ObjectAliasResolver,
    private val logger: Logger
) {

    companion object {
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
    }

    /**
     * Tương thích ngược: Giữ nguyên chữ ký hàm cũ cho các lớp di sản gọi.
     * Chuyển đổi các tham số thô thành cấu trúc SearchContract để chạy qua bộ lọc tập trung mới.
     */
    suspend fun executeSearch(
        timeframe: String,
        objectLabel: String,
        allowedEventTypes: List<String>? = null,
        allowedSources: List<String>? = null,
        limit: Int = 20
    ): SearchResult {
        val timeRange = timeRangeResolver.resolve(timeframe)
        val contract = SearchContract(
            questionType = QuestionType.OTHER,
            sinceMs = timeRange.since,
            untilMs = timeRange.until,
            timeframeLabel = timeRange.label,
            sourceCategory = allowedSources?.firstOrNull(),
            targetObject = objectLabel,
            aggregation = AggregationType.NONE
        )
        return executeSearchContract(contract, limit)
    }

    /**
     * NÂNG CẤP: Trung tâm xử lý Hợp đồng tìm kiếm chung.
     */
    suspend fun executeSearchContract(
        contract: SearchContract,
        limit: Int = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        val rawLogs = eventLogDao.getLogsInTimeframe(contract.sinceMs, contract.untilMs)

        val isChatCategory = contract.sourceCategory?.equals("chat", ignoreCase = true) == true
        val isSpecificChatPlatform = contract.sourceCategory in setOf("facebook", "telegram", "website")
        val isBrainCategory = contract.sourceCategory in setOf("system", "brain", "learning")

        var filtered = when {
            isChatCategory -> {
                rawLogs.filter { 
                    it.source.equals("facebook", ignoreCase = true) || 
                    it.source.equals("telegram", ignoreCase = true) || 
                    it.source.equals("website", ignoreCase = true) ||
                    it.source.equals("chat", ignoreCase = true)
                }
            }
            isBrainCategory -> {
                rawLogs.filter { it.source.equals("system", ignoreCase = true) || it.source.equals("brain", ignoreCase = true) }
            }
            contract.sourceCategory != null -> {
                rawLogs.filter { it.source.equals(contract.sourceCategory, ignoreCase = true) }
            }
            else -> {
                rawLogs
            }
        }

        if (!contract.sourceIdOrName.isNullOrBlank()) {
            val normHint = StringSimilarityUtil.normalizeVietnamese(contract.sourceIdOrName.lowercase())
            filtered = filtered.filter { log ->
                val normId = StringSimilarityUtil.normalizeVietnamese(log.sourceId.lowercase())
                val normSummary = StringSimilarityUtil.normalizeVietnamese(log.summary.lowercase())
                
                var matchedInJson = false
                // ✅ ĐÃ SỬA LỖI NHỎ (Bước 3): Đưa "pattern_ignored" vào danh sách bóc tách JSON an toàn
                if (log.eventType in setOf("incoming_message", "chat_session_state_change", "pattern_discovered", "pattern_approved", "pattern_ignored")) {
                    try {
                        val json = JSONObject(log.value)
                        val senderId = json.optString("senderId", "").lowercase()
                        val devName = json.optString("device_name", "").lowercase()
                        matchedInJson = senderId.contains(normHint) || devName.contains(normHint)
                    } catch (_: Exception) {}
                }
                
                normId.contains(normHint) || normSummary.contains(normHint) || matchedInJson
            }
        }

        if (contract.deviceState != null) {
            filtered = filtered.filter { log ->
                log.value.equals(contract.deviceState, ignoreCase = true) ||
                (contract.deviceState == "true" && log.summary.contains("bật", ignoreCase = true)) ||
                (contract.deviceState == "false" && log.summary.contains("tắt", ignoreCase = true))
            }
        }

        if (contract.targetObject != null && contract.targetObject.lowercase() != "all" && contract.targetObject.lowercase() != "none") {
            filtered = filtered.filter { log ->
                objectAliasResolver.matches(log.summary, contract.targetObject)
            }
        }

        if (contract.detailsKeywords.isNotEmpty()) {
            filtered = filtered.filter { log ->
                contract.detailsKeywords.any { keyword ->
                    log.summary.contains(keyword, ignoreCase = true)
                }
            }
        }

        val totalCount = filtered.size
        val isTruncated = totalCount > limit
        val sortedLogs = filtered.sortedByDescending { it.timestamp }
        val truncatedLogs = sortedLogs.take(limit).reversed()

        val summaryText = buildString {
            val resolvedLabel = contract.timeframeLabel ?: "hôm nay"
            
            if (isChatCategory || isSpecificChatPlatform) {
                append("--- Thống kê hoạt động Kênh Chat [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    append("Không ghi nhận tin nhắn mới hay biến động trạng thái phiên chat nào.\n")
                } else {
                    // ✅ ĐÃ SỬA LỖI LỚN (Bước 3): Tính tổng tin nhắn chưa đọc bằng cách gom nhóm theo người gửi (sourceId) và lấy giá trị lớn nhất của nhóm đó
                    val unreadTotal = filtered
                        .filter { it.eventType in setOf("incoming_message", "chat_session_state_change") }
                        .groupBy { it.sourceId }
                        .values
                        .sumOf { logs ->
                            logs.maxByOrNull { it.timestamp }?.let {
                                try { JSONObject(it.value).optInt("unread_count", 0) } catch (e: Exception) { 0 }
                            } ?: 0
                        }

                    // ✅ ĐÃ SỬA LỖI NHỎ (Bước 3): Đếm số lượng khẩn cấp (urgentCount) độc lập theo từng khách hàng để báo cáo chính xác
                    val urgentSenders = mutableSetOf<String>()
                    val activeSenders = mutableSetOf<String>()
                    val intents = mutableMapOf<String, Int>()

                    filtered.forEach { log ->
                        if (log.eventType in setOf("incoming_message", "chat_session_state_change")) {
                            try {
                                val json = JSONObject(log.value)
                                val intent = json.optString("customer_intent", "general")
                                intents[intent] = (intents[intent] ?: 0) + 1
                                
                                val sender = json.optString("senderId")
                                if (sender.isNotEmpty()) {
                                    activeSenders.add(sender)
                                    if (json.optString("urgency") == "high") {
                                        urgentSenders.add(sender)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    append("💡 Chỉ số phiên chat hiện tại:\n")
                    append("• Tổng số khách liên hệ: ${activeSenders.size} khách hàng\n")
                    append("• Số tin chưa đọc đang chờ người trực: $unreadTotal tin nhắn\n")
                    append("• Số yêu cầu khẩn cấp (high urgency): ${urgentSenders.size} trường hợp\n")
                    if (intents.isNotEmpty()) {
                        append("• Ý định của khách: ")
                        append(intents.entries.joinToString(", ") { "${it.key} (${it.value} lần)" })
                        append("\n")
                    }
                    
                    append("\nChi tiết nhật ký hoạt động chat:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                }
            } else if (isBrainCategory) {
                append("--- Nhật ký Đề xuất & Học tập hệ thống [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    append("Hệ thống hoạt động bình thường, chưa ghi nhận thêm thói quen mới nào.\n")
                } else {
                    val approvedList = mutableListOf<String>()
                    val discoveredList = mutableListOf<String>()
                    val ignoredList = mutableListOf<String>()

                    filtered.forEach { log ->
                        try {
                            val json = JSONObject(log.value)
                            val devName = json.optString("device_name", "Thiết bị")
                            val hour = json.optInt("hour", -1)
                            when (log.eventType) {
                                "pattern_discovered" -> discoveredList.add("- Đề xuất bật/tắt $devName vào lúc ${hour}h")
                                "pattern_approved" -> approvedList.add("- Đã duyệt đặt lịch cho $devName lúc ${json.optInt("hour", 12)}h")
                                "pattern_ignored" -> ignoredList.add("- Đã bỏ qua đề xuất của $devName")
                            }
                        } catch (_: Exception) {}
                    }

                    append("💡 Thống kê quá trình tự học thói quen:\n")
                    append("• Số thói quen mới tự động phát hiện: ${discoveredList.size}\n")
                    append("• Số lịch trình được người dùng phê duyệt: ${approvedList.size}\n")
                    append("• Số đề xuất bị từ chối/bỏ qua: ${ignoredList.size}\n")
                    
                    append("\nChi tiết nhật ký học tập gần nhất:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                }
            } else {
                append("--- Nhật ký tìm kiếm tự động [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    if (contract.questionType == QuestionType.YES_NO) {
                        append("💡 Câu trả lời: KHÔNG. Hệ thống không ghi nhận bất kỳ sự kiện nào trùng khớp.\n")
                    } else {
                        append("Hệ thống hoạt động bình thường, không ghi nhận sự kiện phù hợp.\n")
                    }
                } else {
                    when (contract.aggregation) {
                        AggregationType.COUNT -> {
                            append("💡 Thống kê tần suất: Ghi nhận tổng cộng $totalCount lần diễn ra sự kiện.\n")
                            val onCount = filtered.count { it.summary.contains("bật", ignoreCase = true) || it.value == "true" }
                            val offCount = filtered.count { it.summary.contains("tắt", ignoreCase = true) || it.value == "false" }
                            if (onCount > 0 || offCount > 0) {
                                append("-> Trong đó có $onCount lần bật thiết bị và $offCount lần tắt thiết bị.\n")
                            }
                        }
                        AggregationType.COMPARE -> {
                            val cameraCount = filtered.count { it.source == "camera" }
                            val deviceCount = filtered.count { it.source == "tuya" }
                            append("💡 Phân tích so sánh: Camera ghi nhận $cameraCount lần an ninh, Thiết bị có $deviceCount lần thay đổi trạng thái.\n")
                        }
                        AggregationType.NONE -> {
                            if (contract.questionType == QuestionType.YES_NO) {
                                append("💡 Câu trả lời: CÓ. Hệ thống xác nhận ghi nhận sự kiện trùng khớp yêu cầu.\n")
                            }
                        }
                    }

                    append("\nChi tiết nhật ký hoạt động:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                    if (isTruncated) {
                        append("*(Đã ẩn bớt ${totalCount - limit} sự kiện cũ để tối ưu)*\n")
                    }
                }
            }
        }

        SearchResult(
            logs = truncatedLogs,
            summaryText = summaryText,
            totalCount = totalCount,
            isTruncated = isTruncated
        )
    }
}