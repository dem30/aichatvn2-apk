package com.aichatvn.agent.core.text

import java.util.Calendar

object VietnameseTimeRangeParser {

    data class TimeRange(val since: Long, val until: Long, val label: String)

    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val MAX_DAYS_BACK = 30 // Giới hạn quét tối đa 30 ngày cho mọi truy vấn quá khứ

    fun parse(normalizedMsg: String, now: Long): TimeRange? {
        // 0. NGÀY TUYỆT ĐỐI ("ngay 15 thang 5", "15/5/2026") - Ưu tiên cao nhất
        parseAbsoluteDate(normalizedMsg, now)?.let { return it }

        // 1. Cụm từ thời gian rất ngắn / gần đây
        if (containsAny(normalizedMsg, "luc nay", "vua roi", "vua xong", "moi day")) {
            return TimeRange(now - 2 * HOUR_MS, now, "2 giờ gần nhất")
        }

        // Parse động "X giờ / X tiếng" (vd: "3 gio truoc", "5 tieng qua", "1 gio gan day")
        Regex("(\\d+)\\s*(?:gio|tieng)(?:\\s*(?:truoc|qua|gan day|vua qua))?").find(normalizedMsg)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            if (h != null && h in 1..72) { // Tối đa 72 giờ
                return TimeRange(now - h * HOUR_MS, now, "$h giờ gần nhất")
            }
        }

        // 2. Ngày tương đối cố định
        val startToday = startOfDay(now)

        if (containsAny(normalizedMsg, "hom kia")) { // ✅ Đã bổ sung "hôm kia"
            return TimeRange(startToday - 2 * DAY_MS, startToday - DAY_MS, "hôm kia")
        }
        if (containsAny(normalizedMsg, "hom qua")) {
            return TimeRange(startToday - DAY_MS, startToday, "hôm qua")
        }
        if (containsAny(normalizedMsg, "hom nay")) {
            return TimeRange(startToday, now, "hôm nay")
        }

        // 3. Tuần / Tháng tương đối
        if (containsAny(normalizedMsg, "thang truoc", "thang qua")) {
            val untilMonth = startOfMonth(now)
            val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -1) }
            val sinceMonth = startOfMonth(cal.timeInMillis)
            // Kiểm tra không vượt quá MAX_DAYS_BACK
            val clampedSince = maxOf(sinceMonth, now - MAX_DAYS_BACK * DAY_MS)
            return TimeRange(clampedSince, untilMonth, "tháng trước")
        }
        if (containsAny(normalizedMsg, "thang nay")) {
            return TimeRange(startOfMonth(now), now, "tháng này")
        }
        if (containsAny(normalizedMsg, "tuan truoc")) {
            val startThisWeek = startOfWeek(now)
            return TimeRange(startThisWeek - 7 * DAY_MS, startThisWeek, "tuần trước")
        }
        if (containsAny(normalizedMsg, "tuan nay")) {
            return TimeRange(startOfWeek(now), now, "tuần này")
        }

        // 4. Parse động "X tháng trước"
        Regex("(\\d+)\\s*thang\\s*(?:truoc|qua|gan day)").find(normalizedMsg)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..12) {
                val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }
                val calculatedSince = cal.timeInMillis
                // ✅ Áp dụng trần MAX_DAYS_BACK đồng bộ
                val clampedSince = maxOf(calculatedSince, now - MAX_DAYS_BACK * DAY_MS)
                return TimeRange(clampedSince, now, "$n tháng gần nhất")
            }
        }

        // 5. Parse động "X ngày" - ✅ Yêu cầu bắt buộc có từ chỉ định quá khứ/khoảng thời gian
        Regex("(\\d+)\\s*ngay\\s*(?:truoc|qua|gan day|do lai)").find(normalizedMsg)?.let { m ->
            val d = m.groupValues[1].toIntOrNull()
            if (d != null && d in 1..MAX_DAYS_BACK) {
                return TimeRange(now - d * DAY_MS, now, "$d ngày gần nhất")
            }
        }

        return null
    }

    private fun parseAbsoluteDate(normalizedMsg: String, now: Long): TimeRange? {
        val wordForm = Regex("ngay\\s+(\\d{1,2})\\s*thang\\s+(\\d{1,2})(?:\\s*nam\\s+(\\d{4}))?")
            .find(normalizedMsg)
        val numForm = Regex("\\bngay\\s+(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{2,4}))?\\b")
            .find(normalizedMsg) ?: Regex("\\b(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{4})\\b")
            .find(normalizedMsg)

        val match = wordForm ?: numForm ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        if (day !in 1..31 || month !in 1..12) return null

        val explicitYear = match.groupValues.getOrNull(3)?.toIntOrNull()
            ?.let { if (it < 100) it + 2000 else it }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var year = explicitYear ?: cal.get(Calendar.YEAR)

        // ✅ Chống tràn ngày bằng cách bật isLenient = false
        val candidate = Calendar.getInstance().apply {
            isLenient = false
            try {
                set(year, month - 1, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis // Kích hoạt validation ngày
            } catch (e: Exception) {
                return null // Ngày không hợp lệ (vd: 31/4, 30/2)
            }
        }

        if (explicitYear == null && candidate.timeInMillis > now) {
            candidate.add(Calendar.YEAR, -1)
            year -= 1
        }

        val since = candidate.timeInMillis
        return TimeRange(since, since + DAY_MS, "ngày $day/$month/$year")
    }

    private fun containsAny(text: String, vararg keywords: String) =
        keywords.any { text.contains(it) }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfWeek(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}