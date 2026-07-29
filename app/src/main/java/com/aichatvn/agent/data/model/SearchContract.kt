package com.aichatvn.agent.data.model

enum class QuestionType { YES_NO, QUANTITY, WHAT, WHEN, WHERE, WHY, HOW, OTHER }
enum class AggregationType { COUNT, COMPARE, NONE }

data class SearchContract(
    val questionType: QuestionType = QuestionType.OTHER,
    val sinceMs: Long,
    val untilMs: Long,
    // ✅ SỬA: Chuyển sang kiểu String? để tương thích tuyệt đối với mọi kiểu dữ liệu nền tảng trả về từ parser
    val timeframeLabel: String? = "hôm nay", 
    val sourceCategory: String? = null, // camera, tuya, chat
    val sourceIdOrName: String? = null,
    val targetObject: String? = null,   // person, car, dog...
    val detailsKeywords: List<String> = emptyList(),
    val deviceState: String? = null,     // "true", "false"
    val aggregation: AggregationType = AggregationType.NONE,
    // ✅ MỚI: "summary" | "detail" — do chính AI (Groq) điền qua tool call db_search khi nó
    // biết câu hỏi chỉ cần số liệu tổng hợp (vd "hôm nay có mấy cuộc gọi"), không cần liệt kê
    // từng dòng log thô. Mặc định "detail" để giữ nguyên hành vi cũ (liệt kê chi tiết) cho mọi
    // caller chưa biết tới field này (QA Mode cục bộ, tool call cũ chưa có granularity).
    val granularity: String = "detail"
)