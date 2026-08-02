package com.aichatvn.agent.core.text

import java.util.Calendar

object VietnameseTimeRangeParser {

    data class TimeRange(val since: Long, val until: Long, val label: String)

    private val SPACE_REGEX = Regex("\\s+")
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
    // ✅ ĐÃ SỬA: 30 -> 7. Giới hạn quét tối đa 7 ngày cho mọi truy vấn quá khứ — khớp yêu cầu
    // "search không được hơn 7 ngày" (Cost Guard phía AgentKernel dựa vào đúng hằng số này).
    private const val MAX_DAYS_BACK = 7

    // ✅ MỚI: thay cho hành vi "clamp" (âm thầm cắt bớt khoảng thời gian) đã dùng trước đây ở
    // nhánh "tháng trước"/"tháng này"/"X tháng trước" — cắt bớt khiến người dùng nhận câu trả
    // lời "không có gì xảy ra" trong khi thực ra chỉ mới xem đúng 1 phần rất nhỏ của khoảng họ
    // hỏi (vd hỏi "tháng trước" nhưng chỉ thực sự quét 7/30 ngày) — sai lệch nguy hiểm hơn nhiều
    // so với việc dừng lại hỏi rõ lại người dùng. Khoảng nào tính ra vượt quá MAX_DAYS_BACK thì
    // trả về null (= "chưa xác định được"), để AgentKernel đẩy sang cơ chế hỏi lại khoảng ngày
    // cụ thể (dd/mm/yy - dd/mm/yy) thay vì tự ý đoán/cắt.
    private fun capOrAskAgain(range: TimeRange): TimeRange? =
        if (range.until - range.since > MAX_DAYS_BACK * DAY_MS) null else range

    // ✅ MỚI: chữ số tiếng Việt (không dấu, sau normalize) -> Int, dùng cho rule "tuần N tháng M"
    // khi người dùng nói bằng chữ thay vì số ("tuần một tháng ba" thay vì "tuần 1 tháng 3").
    private val VN_NUMBER_WORDS: Map<String, Int> = mapOf(
        "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4,
        "nam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9,
        "muoi" to 10, "muoi mot" to 11, "muoi hai" to 12
    )

    // Thử khớp cụm 2 từ trước ("muoi mot"/"muoi hai") rồi mới tới 1 từ, để không cắt nhầm
    // "mười một" thành "mười" (10) và làm rơi mất "một".
    private fun parseVnNumber(raw: String): Int? {
        val cleaned = raw.trim()
        cleaned.toIntOrNull()?.let { return it }
        VN_NUMBER_WORDS[cleaned]?.let { return it }
        val parts = cleaned.split(SPACE_REGEX)
        if (parts.size >= 2) VN_NUMBER_WORDS[parts.take(2).joinToString(" ")]?.let { return it }
        return VN_NUMBER_WORDS[parts.firstOrNull() ?: ""]
    }

    // ✅ MỚI: "tuần N tháng M" — quy ước tuần TRONG THÁNG (không phải tuần ISO theo lịch):
    // tuần 1 = ngày 1-7, tuần 2 = 8-14, tuần 3 = 15-21, tuần 4 = 22-28,
    // tuần 5 = 29-hết tháng (nếu tháng đó có đủ ngày, ngược lại trả null). Kết quả luôn <= 7
    // ngày -> KHÔNG cần đi qua capOrAskAgain(), tự thân đã trong giới hạn cho phép.
    private fun buildWeekOfMonthRange(week: Int, month: Int, explicitYear: Int?, now: Long): TimeRange? {
        val calNow = Calendar.getInstance().apply { timeInMillis = now }
        val year = explicitYear ?: calNow.get(Calendar.YEAR)

        val daysInMonth = try {
            Calendar.getInstance().apply { isLenient = false; set(year, month - 1, 1) }
                .getActualMaximum(Calendar.DAY_OF_MONTH)
        } catch (e: Exception) { return null }

        val startDay = (week - 1) * 7 + 1
        if (startDay > daysInMonth) return null // vd tháng 2 không có "tuần 5"
        val endDay = minOf(startDay + 6, daysInMonth)

        // ✅ Cùng cách "kích hoạt validation ngày" đã dùng ở parseAbsoluteDate(): gọi timeInMillis
        // ngay trong try để exception (nếu có) thực sự được ném ra và bắt được ở đây.
        fun buildCal(day: Int): Calendar = Calendar.getInstance().apply {
            isLenient = false
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        val sinceCal = try { buildCal(startDay) } catch (e: Exception) { return null }

        // Không nêu năm rõ ràng và mốc tính ra lại nằm ở TƯƠNG LAI -> hiểu là năm ngoái,
        // cùng logic đã dùng ở parseAbsoluteDate() bên dưới.
        if (explicitYear == null && sinceCal.timeInMillis > now) {
            return buildWeekOfMonthRange(week, month, year - 1, now)
        }

        val untilCal = try { buildCal(endDay) } catch (e: Exception) { return null }
        untilCal.add(Calendar.DAY_OF_MONTH, 1)
        return TimeRange(sinceCal.timeInMillis, untilCal.timeInMillis - 1, "tuần $week tháng $month/$year")
    }

    fun parse(normalizedMsg: String, now: Long): TimeRange? {
        // 0. NGÀY TUYỆT ĐỐI ("ngay 15 thang 5", "15/5/2026") - Ưu tiên cao nhất
        parseAbsoluteDate(normalizedMsg, now)?.let { return it }

        // 0.5. MỚI: "Tuần N tháng M" — chấp nhận cả số và chữ ("tuần 1 tháng 3" / "tuần một
        // tháng ba"), tối đa 2 từ mỗi phần số (vd "mười một") để bắt được tháng 11/12 bằng chữ.
        // Đặt TRƯỚC các rule "tuần trước"/"tháng trước" chung chung để không bị nuốt mất.
        Regex("tuan\\s+(\\d+|[a-z]+(?:\\s+[a-z]+)?)\\s+thang\\s+(\\d+|[a-z]+(?:\\s+[a-z]+)?)(?:\\s+nam\\s+(\\d{4}))?")
            .find(normalizedMsg)?.let { m ->
                val week = parseVnNumber(m.groupValues[1])
                val month = parseVnNumber(m.groupValues[2])
                val explicitYear = m.groupValues.getOrNull(3)?.toIntOrNull()
                if (week != null && week in 1..5 && month != null && month in 1..12) {
                    buildWeekOfMonthRange(week, month, explicitYear, now)?.let { return it }
                }
            }

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
            // ✅ ĐÃ SỬA: trước đây clamp (cắt bớt) sinceMonth về MAX_DAYS_BACK, cho ra kết quả
            // TRẢ LỜI ĐƯỢC nhưng thiếu dữ liệu mà không nói rõ. Giờ dùng capOrAskAgain(): nếu cả
            // tháng dài hơn 7 ngày (luôn đúng, trừ tháng chạy sát cuối) -> trả null, đẩy sang cơ
            // chế hỏi lại "tuần mấy tháng mấy" hoặc dd/mm/yy - dd/mm/yy ở AgentKernel.
            return capOrAskAgain(TimeRange(sinceMonth, untilMonth, "tháng trước"))
        }
        if (containsAny(normalizedMsg, "thang nay")) {
            return capOrAskAgain(TimeRange(startOfMonth(now), now, "tháng này"))
        }
        if (containsAny(normalizedMsg, "tuan truoc")) {
            val startThisWeek = startOfWeek(now)
            return TimeRange(startThisWeek - 7 * DAY_MS, startThisWeek, "tuần trước")
        }
        if (containsAny(normalizedMsg, "tuan nay")) {
            return TimeRange(startOfWeek(now), now, "tuần này")
        }

        // 4. Parse động "X tháng trước"
        // ✅ ĐÃ SỬA: cùng lý do ở nhánh "tháng trước" cố định — bỏ clamp, dùng capOrAskAgain().
        // Với n>=1 tháng, khoảng luôn > 7 ngày nên rule này thực chất sẽ luôn trả null (đẩy sang
        // hỏi lại) — giữ nguyên rule ở đây (không xoá hẳn) để dễ mở rộng sau này nếu ngưỡng đổi.
        Regex("(\\d+)\\s*thang\\s*(?:truoc|qua|gan day)").find(normalizedMsg)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..12) {
                val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }
                return capOrAskAgain(TimeRange(cal.timeInMillis, now, "$n tháng gần nhất"))
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