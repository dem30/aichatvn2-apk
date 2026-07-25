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
            
            // 🌟 SỬA: Bỏ giới hạn trên (cũ: chỉ chấp nhận nếu trễ 0-2 phút so với giờ hẹn).
            // Lý do: bộ đếm định kỳ gọi matches() tối thiểu mỗi ~15 phút (mức sàn cứng của
            // WorkManager PeriodicWorkRequest, xem SCHEDULE_CAMERA_SCAN_INTERVAL_MIN mặc định
            // 15). Cửa sổ 3 phút cũ hẹp hơn hẳn chu kỳ kiểm tra 15 phút -> xác suất 1 lần check
            // rơi trúng đúng 3 phút đó chỉ ~20%, nghĩa là ~80% khả năng lịch hàng ngày bị bỏ lỡ
            // nguyên ngày hôm đó. Giờ chỉ cần "đã qua giờ hẹn của hôm nay" + "hôm nay chưa chạy
            // lần nào" (isDifferentDay) là đủ để kích hoạt — không giới hạn trễ tối đa bao nhiêu
            // phút, vì lastRunAt được cập nhật ngay sau khi chạy nên isDifferentDay tự đảm bảo
            // không chạy lặp lại 2 lần trong cùng 1 ngày.
            val minutesDifference = currentTotalMinutes - targetTotalMinutes
            return minutesDifference >= 0 && isDifferentDay
        }

        return false
    }
}