package com.aichatvn.agent.utils

import com.aichatvn.agent.utils.Logger
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class TimeRange(val since: Long, val until: Long, val label: String)

@Singleton
class TimeRangeResolver @Inject constructor(
    private val logger: Logger
) {

    fun resolve(timeframe: String, now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): TimeRange {
        val zonedDateTime = now.atZone(zoneId)
        val lowerTimeframe = timeframe.lowercase().trim()

        return when {
            lowerTimeframe == "yesterday" -> {
                val since = zonedDateTime.toLocalDate().minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val until = zonedDateTime.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
                TimeRange(since, until, "hôm qua")
            }
            lowerTimeframe.contains("3_days") || lowerTimeframe.contains("3 days") -> {
                val since = zonedDateTime.toLocalDate().minusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
                TimeRange(since, now.toEpochMilli(), "3 ngày gần đây")
            }
            lowerTimeframe.contains("7_days") || lowerTimeframe.contains("7 days") -> {
                val since = zonedDateTime.toLocalDate().minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
                TimeRange(since, now.toEpochMilli(), "7 ngày gần đây")
            }
            lowerTimeframe == "today" -> {
                val since = zonedDateTime.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
                TimeRange(since, now.toEpochMilli(), "hôm nay")
            }
            else -> {
                // ✅ MỚI: enum lạ (AI trả sai/model bịa thêm giá trị ngoài today/yesterday/
                // 3_days/7_days) trước đây ÂM THẦM lùi 3 ngày — người dùng hỏi "tuần trước" (bị
                // model dịch sai thành enum lạ) nhận về kết quả của 3 NGÀY GẦN NHẤT mà không biết
                // hệ thống đã tự đổi phạm vi tìm kiếm. Log cảnh báo để phát hiện các enum lạ này
                // trong thực tế (từ đó bổ sung nhánh xử lý đúng), hành vi trả về vẫn giữ nguyên
                // (không phá vỡ luồng hiện tại) — chỉ thêm khả năng quan sát.
                logger.e("TimeRangeResolver", "Timeframe không nhận diện được: '$timeframe' — dùng mặc định 3 ngày gần nhất, có thể KHÔNG đúng ý người dùng.")
                val since = zonedDateTime.toLocalDate().minusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
                TimeRange(since, now.toEpochMilli(), "$timeframe (mặc định 3 ngày)")
            }
        }
    }
}