package com.aichatvn.agent.scheduler

object CronParser {
    private val dailyPattern  = Regex("""^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$""")
    private val intervalPattern = Regex("""^\*/(\d+)\s+\*\s+\*\s+\*\s+\*$""")

    fun matches(cron: String, timestamp: Long, lastRunAt: Long): Boolean {
        val nowCalendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowHour = nowCalendar.get(java.util.Calendar.HOUR_OF_DAY)
        val nowMinute = nowCalendar.get(java.util.Calendar.MINUTE)
        val nowDayOfYear = nowCalendar.get(java.util.Calendar.DAY_OF_YEAR)
        val nowYear = nowCalendar.get(java.util.Calendar.YEAR)

        val lastCalendar = java.util.Calendar.getInstance().apply { timeInMillis = lastRunAt }
        val lastDayOfYear = lastCalendar.get(java.util.Calendar.DAY_OF_YEAR)
        val lastYear = lastCalendar.get(java.util.Calendar.YEAR)

        val isDifferentDay = nowYear != lastYear || nowDayOfYear != lastDayOfYear

        // 1. So khớp định dạng lặp định kỳ theo phút (ví dụ: */5 * * * *)
        intervalPattern.matchEntire(cron.trim())?.let { m ->
            val interval = m.groupValues[1].toLongOrNull() ?: return false
            val elapsedMinutes = (timestamp - lastRunAt) / 60_000L
            return elapsedMinutes >= (interval - 1)
        }

        // 2. So khớp định dạng lịch trình hàng ngày (ví dụ: 30 7 * * *)
        dailyPattern.matchEntire(cron.trim())?.let { m ->
            val cronMinute = m.groupValues[1].toIntOrNull() ?: return false
            val cronHour   = m.groupValues[2].toIntOrNull() ?: return false
            
            val currentTotalMinutes = nowHour * 60 + nowMinute
            val targetTotalMinutes = cronHour * 60 + cronMinute
            
            // SỬA BUG LOGIC: Chỉ chấp nhận chạy nếu giờ hiện tại trùng khớp hoặc trễ từ 0 đến 2 phút
            // so với giờ hẹn định sẵn, đồng thời hôm nay chưa chạy lần nào.
            val minutesDifference = currentTotalMinutes - targetTotalMinutes
            return minutesDifference in 0..2 && isDifferentDay
        }

        return false
    }
}