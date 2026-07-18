package com.aichatvn.agent.utils

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class TimeRange(val since: Long, val until: Long, val label: String)

@Singleton
class TimeRangeResolver @Inject constructor() {

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
                // Mặc định lùi lại 3 ngày
                val since = zonedDateTime.toLocalDate().minusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
                TimeRange(since, now.toEpochMilli(), "$timeframe (mặc định 3 ngày)")
            }
        }
    }
}