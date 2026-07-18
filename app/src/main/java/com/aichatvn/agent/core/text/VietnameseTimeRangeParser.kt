package com.aichatvn.agent.core.text

import java.util.Calendar

/**
 * Phân giải các mốc thời gian TƯƠNG ĐỐI trong câu hỏi tiếng Việt, dùng để khoanh vùng
 * [since, until] khi TRUY VẤN NGƯỢC lịch sử (event_logs) trong ChatSkill.buildMemoryContext().
 *
 * KHÁC với DateTimeParser (utils/DateTimeParser.kt): DateTimeParser trả về biểu thức CRON
 * để LẬP LỊCH CHẠY TƯƠNG LAI (schedule.add), còn parser này trả về một khoảng thời gian cụ
 * thể trong QUÁ KHỨ để giới hạn phạm vi quét log. Hai mục đích khác nhau nên không gộp chung,
 * dù cùng xử lý "thời gian tiếng Việt".
 *
 * Nhận đầu vào đã được chuẩn hóa qua VietnameseTextNormalizer.normalize() (bỏ dấu, hạ chữ
 * thường) — khác DateTimeParser vốn làm việc trực tiếp trên chuỗi CÓ dấu (vì cần phân biệt
 * "sáng/chiều/tối/đêm" theo dấu). Ở đây không cần phân biệt dấu nên dùng bản normalize chung,
 * nhất quán với cách ChatSkill.buildMemoryContext() đã chuẩn hóa userMessage để so khớp
 * camera/thiết bị/kênh.
 */
object VietnameseTimeRangeParser {

    data class TimeRange(val since: Long, val until: Long, val label: String)

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_DAYS_BACK = 30 // chặn trần hợp lý, tránh câu hỏi kiểu "60 ngày trước" quét quá sâu

    /**
     * Trả về null nếu câu hỏi không chứa tín hiệu thời gian rõ ràng — bên gọi (ChatSkill) tự
     * quyết định khoảng mặc định (hiện là DEFAULT_MEMORY_LOOKBACK_DAYS = 3 ngày).
     *
     * @param normalizedMsg chuỗi ĐÃ chuẩn hóa qua VietnameseTextNormalizer.normalize(...)
     */
    fun parse(normalizedMsg: String, now: Long): TimeRange? {
        // 0. NGÀY TUYỆT ĐỐI ("ngay 15 thang 5", "15/5", "15/5/2026") — ưu tiên CAO NHẤT, vì đây
        // là tín hiệu cụ thể nhất, phải khớp trước mọi từ khóa tương đối bên dưới (nếu không,
        // câu "ngày 15 tháng 5" có thể vô tình bị nuốt bởi 1 nhánh khác trước khi tới đây).
        parseAbsoluteDate(normalizedMsg, now)?.let { return it }

        // Cụm hẹp nhất khớp trước — "luc nay"/"vua roi" phải thắng "hom nay" nếu câu chứa cả 2
        // ý (ví dụ không thực tế nhưng để đúng nguyên tắc "cụ thể hơn ưu tiên hơn" như phần
        // matchedCamera/matchedDevice/matchedPlatform đã áp dụng trong ChatSkill).
        if (containsAny(normalizedMsg, "luc nay", "vua roi", "vua xong", "moi day")) {
            return TimeRange(now - 2 * 60 * 60 * 1000L, now, "2 giờ gần nhất")
        }
        if (containsAny(normalizedMsg, "hom qua")) {
            val startToday = startOfDay(now)
            return TimeRange(startToday - DAY_MS, startToday, "hôm qua")
        }
        if (containsAny(normalizedMsg, "hom nay")) {
            return TimeRange(startOfDay(now), now, "hôm nay")
        }
        // "tháng qua/trước" phải khớp TRƯỚC "tuần" và "ngày" vì phạm vi rộng hơn hẳn — nếu để
        // dưới, 1 câu như "tháng trước có 3 ngày mưa" (giả định) có thể bị pattern "X ngày" phía
        // dưới cướp mất, dù không thực tế lắm với domain smarthome nhưng giữ đúng thứ tự an toàn.
        if (containsAny(normalizedMsg, "thang truoc", "thang qua")) {
            val untilMonth = startOfMonth(now)
            val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -1) }
            return TimeRange(startOfMonth(cal.timeInMillis), untilMonth, "tháng trước")
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
        // "X tháng trước" (vd "2 tháng trước") — bắt trước "X ngày" vì cùng cấu trúc số + đơn vị,
        // để "2 thang truoc" không bị Regex "(\\d+)\\s*ngay" phía dưới bỏ sót/hiểu nhầm.
        Regex("(\\d+)\\s*thang\\s*truoc").find(normalizedMsg)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..12) {
                val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }
                return TimeRange(cal.timeInMillis, now, "$n tháng gần nhất")
            }
        }
        // "X ngày trước / gần đây / qua" — bắt số đứng ngay trước "ngay"
        Regex("(\\d+)\\s*ngay").find(normalizedMsg)?.let { m ->
            val d = m.groupValues[1].toIntOrNull()
            if (d != null && d in 1..MAX_DAYS_BACK) {
                return TimeRange(now - d * DAY_MS, now, "$d ngày gần nhất")
            }
        }
        return null
    }

    /**
     * Bắt ngày tuyệt đối dạng chữ ("ngay 15 thang 5 [nam 2026]") hoặc dạng số ("15/5",
     * "15/5/2026", "15-5-2026"). Input đã normalize() nên "tháng"->"thang", "năm"->"nam".
     *
     * ⚠️ GIỚI HẠN ĐÃ BIẾT: dạng số "D/M" có thể trùng với các chuỗi số khác không phải ngày
     * tháng (vd tỉ lệ, mã số) nếu chúng tình cờ xuất hiện trong câu hỏi — domain smarthome hiện
     * tại ít gặp trường hợp này nên chấp nhận rủi ro, nhưng nếu sau này có param dạng "A/B" khác
     * xuất hiện trong câu hỏi, cần thắt lại pattern (vd bắt buộc có chữ "ngày" đứng trước).
     */
    private fun parseAbsoluteDate(normalizedMsg: String, now: Long): TimeRange? {
        val wordForm = Regex("ngay\\s+(\\d{1,2})\\s*thang\\s+(\\d{1,2})(?:\\s*nam\\s+(\\d{4}))?")
            .find(normalizedMsg)
        val numForm = Regex("\\bngay\\s+(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{2,4}))?\\b")
            .find(normalizedMsg) ?: Regex("\\b(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{4})\\b")
            .find(normalizedMsg) // dạng số CHỈ nhận khi có đủ 3 phần (D/M/YYYY) để giảm nhầm lẫn

        val match = wordForm ?: numForm ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        if (day !in 1..31 || month !in 1..12) return null

        val explicitYear = match.groupValues.getOrNull(3)?.toIntOrNull()
            ?.let { if (it < 100) it + 2000 else it }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var year = explicitYear ?: cal.get(Calendar.YEAR)

        val candidate = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        // Không ghi rõ năm và ngày suy ra rơi vào TƯƠNG LAI so với hiện tại -> lùi về năm trước
        // (giả định hợp lý cho domain hỏi lịch sử: người dùng hỏi về quá khứ, không hỏi ngày
        // chưa tới).
        if (explicitYear == null && candidate.timeInMillis > now) {
            candidate.add(Calendar.YEAR, -1)
            year -= 1
        }
        val since = candidate.timeInMillis
        return TimeRange(since, since + DAY_MS, "ngày $day/$month/$year")
    }

    private fun startOfMonth(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
}