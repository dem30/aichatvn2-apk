package com.aichatvn.agent.utils

import com.aichatvn.agent.data.EventLogDao
import com.aichatvn.agent.data.model.EventLogEntity
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
     * Thực hiện tìm kiếm và phân loại dữ liệu có cấu trúc.
     *
     * @param timeframe Khoảng thời gian yêu cầu (today, yesterday...)
     * @param objectLabel Nhãn đối tượng cần lọc (person, dog...)
     * @param allowedEventTypes Danh sách loại sự kiện muốn lọc (ví dụ: ["person_detected", "state_change"]). Nếu null sẽ lấy tất cả.
     * @param allowedSources Danh sách nguồn muốn lọc (ví dụ: ["camera", "tuya"]). Nếu null sẽ lấy tất cả.
     * @param limit Giới hạn tối đa số lượng dòng log hiển thị tóm tắt để chống Token Bloat.
     */
    suspend fun executeSearch(
        timeframe: String,
        objectLabel: String,
        allowedEventTypes: List<String>? = null,
        allowedSources: List<String>? = null,
        limit: Int = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        
        // 1. Phân giải thời gian qua TimeRangeResolver
        val timeRange = timeRangeResolver.resolve(timeframe)
        
        // 2. Tải dữ liệu thô từ EventLogDao (De-couple khỏi AppDatabase)
        val rawLogs = loadEvents(timeRange.since, timeRange.until)
        
        // 3. Lọc theo nguồn (sources) và loại sự kiện (eventTypes) được yêu cầu động
        val categoryFilteredLogs = filterByMetadata(rawLogs, allowedEventTypes, allowedSources)
        
        // 4. Lọc theo nhãn đối tượng qua ObjectAliasResolver
        val objectFilteredLogs = filterByObjectLabel(categoryFilteredLogs, objectLabel)
        
        // 5. Định dạng dữ liệu và đóng gói SearchResult có cấu trúc
        buildSearchResult(objectFilteredLogs, timeRange.label, objectLabel, limit)
    }

    private suspend fun loadEvents(since: Long, until: Long): List<EventLogEntity> {
        return eventLogDao.getLogsInTimeframe(since, until)
    }

    private fun filterByMetadata(
        logs: List<EventLogEntity>,
        allowedEventTypes: List<String>?,
        allowedSources: List<String>?
    ): List<EventLogEntity> {
        return logs.filter { log ->
            val matchType = allowedEventTypes == null || log.eventType in allowedEventTypes
            val matchSource = allowedSources == null || log.source in allowedSources
            matchType && matchSource
        }
    }

    private fun filterByObjectLabel(logs: List<EventLogEntity>, objectLabel: String): List<EventLogEntity> {
        return logs.filter { log ->
            objectAliasResolver.matches(log.summary, objectLabel)
        }
    }

    private fun buildSearchResult(
        logs: List<EventLogEntity>,
        rangeLabel: String,
        objectLabel: String,
        limit: Int
    ): SearchResult {
        val totalCount = logs.size
        val isTruncated = totalCount > limit
        
        // Sắp xếp các sự kiện mới nhất lên đầu để lấy ra (take), sau đó đảo ngược thời gian tăng dần để AI đọc liền mạch câu chuyện
        val sortedLogs = logs.sortedByDescending { it.timestamp }
        val truncatedLogs = sortedLogs.take(limit).reversed()
        
        val activityLines = truncatedLogs.map { log ->
            val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
            "• [$timeStr] ${log.summary}"
        }

        val objectNote = if (objectLabel.lowercase() != "all") " (lọc theo vật thể: $objectLabel)" else ""
        val summaryText = buildString {
            append("--- Nhật ký tìm kiếm tự động$objectNote ($rangeLabel) ---\n")
            if (activityLines.isEmpty()) {
                append("Không tìm thấy sự kiện nào trùng khớp.")
            } else {
                append(activityLines.joinToString("\n"))
            }
            if (isTruncated) {
                append("\n*(Còn ${totalCount - limit} sự kiện cũ hơn đã được ẩn đi để tối ưu ngữ cảnh)*")
            }
        }

        return SearchResult(
            logs = truncatedLogs,
            summaryText = summaryText,
            totalCount = totalCount,
            isTruncated = isTruncated
        )
    }
}