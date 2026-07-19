package com.aichatvn.agent.utils

import com.aichatvn.agent.data.EventLogDao
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.SearchContract
import com.aichatvn.agent.data.model.QuestionType
import com.aichatvn.agent.data.model.AggregationType
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Thực hiện bóc tách, lọc sâu, dán nhãn Yes/No, đếm tần suất và tính toán logic tự động.
     */
    suspend fun executeSearchContract(
        contract: SearchContract,
        limit: Int = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        // Tải các dòng dữ liệu thô trong khung thời gian yêu cầu
        val rawLogs = eventLogDao.getLogsInTimeframe(contract.sinceMs, contract.untilMs)

        // 1. ✅ NÂNG CẤP: Lọc theo Phân loại nguồn (Camera / Thiết bị Tuya / Kênh Chat)
        // Nếu sourceCategory là "chat", tự động mở rộng truy vấn tìm kiếm trên cả 3 nền tảng facebook, telegram, website
        var filtered = if (contract.sourceCategory != null) {
            if (contract.sourceCategory.equals("chat", ignoreCase = true)) {
                rawLogs.filter { 
                    it.source.equals("facebook", ignoreCase = true) || 
                    it.source.equals("telegram", ignoreCase = true) || 
                    it.source.equals("website", ignoreCase = true) 
                }
            } else {
                rawLogs.filter { it.source.equals(contract.sourceCategory, ignoreCase = true) }
            }
        } else {
            rawLogs
        }

        // 2. ✅ SỬA: Sử dụng isNullOrBlank() an toàn. Nếu chuỗi rỗng "" hoặc blank lọt xuống đây,
        // hệ thống sẽ bỏ qua, không kích hoạt bộ lọc theo ID để tương thích đúng với logic fallback của AgentKernel.
        if (!contract.sourceIdOrName.isNullOrBlank()) {
            val normHint = StringSimilarityUtil.normalizeVietnamese(contract.sourceIdOrName.lowercase())
            filtered = filtered.filter { log ->
                val normId = StringSimilarityUtil.normalizeVietnamese(log.sourceId.lowercase())
                val normSummary = StringSimilarityUtil.normalizeVietnamese(log.summary.lowercase())
                normId.contains(normHint) || normSummary.contains(normHint)
            }
        }

        // 3. Lọc theo trạng thái vật lý của thiết bị Tuya nếu có (true = bật, false = tắt)
        if (contract.deviceState != null) {
            filtered = filtered.filter { log ->
                log.value.equals(contract.deviceState, ignoreCase = true) ||
                (contract.deviceState == "true" && log.summary.contains("bật", ignoreCase = true)) ||
                (contract.deviceState == "false" && log.summary.contains("tắt", ignoreCase = true))
            }
        }

        // 4. Lọc theo lớp vật thể an ninh bằng ObjectAliasResolver (person, car, dog...)
        if (contract.targetObject != null && contract.targetObject.lowercase() != "all" && contract.targetObject.lowercase() != "none") {
            filtered = filtered.filter { log ->
                objectAliasResolver.matches(log.summary, contract.targetObject)
            }
        }

        // 5. Lọc sâu theo các từ khóa miêu tả mở rộng
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

        // 6. TIẾN HÀNH TỔNG HỢP VÀ TỰ TÍNH TOÁN (Heuristic Query Aggregation)
        val summaryText = buildString {
            val resolvedLabel = contract.timeframeLabel ?: "hôm nay"
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

        SearchResult(
            logs = truncatedLogs,
            summaryText = summaryText,
            totalCount = totalCount,
            isTruncated = isTruncated
        )
    }
}