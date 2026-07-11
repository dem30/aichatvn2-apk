package com.aichatvn.agent.core.text

import java.text.Normalizer

/**
 * ============================================================================
 * NGUỒN CHÂN LÝ DUY NHẤT cho việc chuẩn hóa & so khớp cụm từ tiếng Việt.
 * ============================================================================
 *
 * Trước đây hàm normalizeVietnamese() bị viết lặp lại giống hệt nhau ở 3 nơi:
 *   - StringSimilarityUtil.normalizeVietnamese()
 *   - TrainingSkill.normalizeVietnamese()  (private, trong companion)
 *   - DialogManagerImpl.normalizeVietnamese() (private)
 *
 * Gộp về đây để: (1) sửa 1 lần cho cả 3 nơi dùng, (2) tránh nguy cơ 3 bản dần
 * lệch nhau qua thời gian (bug "dừng" trùng "nội dung" chẳng hạn chỉ được vá
 * ở DialogManagerImpl, các nơi khác không có).
 *
 * LƯU Ý: Hai thuật toán tính điểm tương đồng hiện có (TrainingSkill.calculateSimilarity
 * và StringSimilarityUtil.calculateLocalSimilarity) có trọng số/logic KHÁC NHAU
 * (không phải bản sao của nhau) nên KHÔNG được gộp ở bước này — chỉ gộp phần
 * chuẩn hóa chuỗi và so khớp cụm từ trọn vẹn, là phần thực sự trùng lặp 100%.
 */
object VietnameseTextNormalizer {

    private val SPACE_REGEX = Regex("\\s+")

    /**
     * Chuẩn hóa: bỏ dấu tiếng Việt, hạ chữ thường, gộp khoảng trắng thừa.
     * Logic giữ NGUYÊN VẸN so với 3 bản cũ — không đổi hành vi.
     */
    fun normalize(text: String): String {
        val temp = Normalizer.normalize(text, Normalizer.Form.NFD)
        val noDiacritics = "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(temp, "")
        return noDiacritics
            .replace("đ", "d")
            .replace("Đ", "D")
            .lowercase()
            .trim()
            .replace(SPACE_REGEX, " ")
    }

    /**
     * Kiểm tra `phrase` có xuất hiện trong `text` như một CỤM TỪ TRỌN VẸN hay không
     * (có ranh giới không phải chữ/số bao quanh). Không dùng `\b` của Regex vì
     * `\b` không nhận diện đúng ranh giới với tiếng Việt có dấu.
     *
     * Chuyển từ DialogManagerImpl — giữ nguyên logic gốc.
     */
    fun containsWholePhrase(text: String, phrase: String): Boolean {
        return findWholePhraseRange(text, phrase) != null
    }

    /** Trả về range (start, end) nếu `phrase` khớp trọn vẹn trong `text`, ngược lại null. */
    fun findWholePhraseRange(text: String, phrase: String): IntRange? {
        if (phrase.isBlank()) return null
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(phrase, searchFrom)
            if (idx == -1) return null

            val before = if (idx > 0) text[idx - 1] else ' '
            val afterIdx = idx + phrase.length
            val after = if (afterIdx < text.length) text[afterIdx] else ' '

            val isBoundaryBefore = !before.isLetterOrDigit()
            val isBoundaryAfter = !after.isLetterOrDigit()

            if (isBoundaryBefore && isBoundaryAfter) {
                return idx..(idx + phrase.length - 1)
            }
            searchFrom = idx + 1
        }
    }

    /**
     * Thay thế cụm `pronoun` (so khớp không phân biệt dấu/hoa-thường) bằng `replacement`
     * ngay trên chuỗi GỐC (giữ nguyên dấu của các phần còn lại của câu).
     * Trả về null nếu không tìm thấy. Chuyển từ DialogManagerImpl — giữ nguyên logic gốc,
     * kể cả fallback an toàn khi lệch offset do khoảng trắng thừa.
     */
    fun replaceWholePhraseInOriginal(original: String, pronoun: String, replacement: String): String? {
        val normalizedOriginal = normalize(original)
        val normalizedPronoun = normalize(pronoun)
        val range = findWholePhraseRange(normalizedOriginal, normalizedPronoun) ?: return null

        if (normalizedOriginal.length != original.length) {
            val regexSafe = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(pronoun)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            return if (regexSafe.containsMatchIn(original)) {
                regexSafe.replaceFirst(original, replacement)
            } else null
        }

        val before = original.substring(0, range.first)
        val after = original.substring(range.last + 1)
        return before + replacement + after
    }
}
