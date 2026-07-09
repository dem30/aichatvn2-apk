package com.aichatvn.agent.core

import com.aichatvn.agent.skills.TrainingSkill

/**
 * Ngữ cảnh điều hướng (Pipeline State) đóng vai trò lưu giữ toàn bộ dữ liệu 
 * tính toán trích xuất được xuyên suốt từ Tầng 0 sang các tầng xử lý kế thừa phía dưới.
 * Đã tích hợp globalMatchResult tính toán 1 lần duy nhất với aliasThreshold = 0.0f.
 *
 * ✅ MỚI: Thêm traces để gom vết thực thi (call graph) phục vụ màn hình
 * Diagnostics/Pipeline Graph — hoàn toàn không ảnh hưởng logic điều hướng cũ vì
 * có default = mutableListOf(), mọi nơi khởi tạo RoutingContext cũ (không truyền
 * traces) vẫn compile và chạy y hệt như trước.
 */
data class RoutingContext(
    val originalQuery: String,
    val resolvedQuery: String,       // Câu lệnh sau khi xử lý đại từ ở Tầng 0
    val username: String,
    val clauses: List<String>,       // Mệnh đề rã một lần duy nhất
    val globalMatchResult: TrainingSkill.MatchResult, // Fuzzy Match tĩnh tính toán 1 lần duy nhất cho toàn câu
    val localEntities: Map<String, Any>, // Các thực thể phân tích bằng Heuristic
    val traces: MutableList<TraceNode> = mutableListOf() // ✅ MỚI: Vết thực thi (call graph) của request này
)