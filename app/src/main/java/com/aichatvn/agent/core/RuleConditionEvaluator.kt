package com.aichatvn.agent.core

/**
 * Đánh giá 1 biểu thức điều kiện ĐƠN GIẢN dạng "<field> <op> <value>" dựa trên map dữ liệu
 * trả về từ bước "check" của GoalRuleEntity (PluginResult.Success.data). Cố tình KHÔNG phải
 * 1 expression engine tổng quát (không hỗ trợ AND/OR/ngoặc, không eval code tuỳ ý) — đủ dùng
 * cho các quy tắc quản gia dạng "X lệch ngưỡng/khác thường" và an toàn vì đầu vào tới từ LLM.
 *
 * Hỗ trợ: >=, <=, ==, !=, >, < (so sánh số) và ==, != (so sánh chuỗi/bool).
 * conditionExpr rỗng hoặc chỉ có tên field (không toán tử) -> coi field đó là boolean/truthy.
 */
import java.util.regex.Pattern

object RuleConditionEvaluator {

    // ✅ QUAN TRỌNG VỀ THỨ TỰ: "contains"/"matches_regex" phải được kiểm tra TRƯỚC các toán tử
    // ký hiệu (>, <, ...). Lý do: vế phải của "contains"/"matches_regex" là literal tự do
    // (vd text contains '>10k'), nếu ưu tiên ">" trước sẽ cắt nhầm chuỗi tại ký tự đó.
    // Trong nhóm ký hiệu, thứ tự dài trước để tránh match nhầm ">=" thành ">".
    private val OPERATORS = listOf("matches_regex", "contains", ">=", "<=", "==", "!=", ">", "<")

    fun evaluate(expr: String, data: Map<*, *>): Boolean {
        val trimmed = expr.trim()
        if (trimmed.isBlank()) return true

        val op = OPERATORS.firstOrNull { trimmed.contains(it) }
            ?: return evaluateTruthy(trimmed, data)

        val parts = trimmed.split(op, limit = 2)
        if (parts.size != 2) return evaluateTruthy(trimmed, data)

        val leftVal = resolveValue(parts[0].trim(), data)
        val rightVal = resolveValue(parts[1].trim(), data)
        return compare(leftVal, rightVal, op)
    }

    private fun evaluateTruthy(fieldName: String, data: Map<*, *>): Boolean {
        return when (val v = data[fieldName]) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun resolveValue(token: String, data: Map<*, *>): Any? {
        // Ưu tiên coi token là tên field trong data map; nếu không có mới coi là literal.
        if (data.containsKey(token)) return data[token]
        token.toDoubleOrNull()?.let { return it }
        return when (token.lowercase()) {
            "true" -> true
            "false" -> false
            else -> token.removeSurrounding("\"").removeSurrounding("'")
        }
    }

    private fun compare(left: Any?, right: Any?, op: String): Boolean {
        val leftStr = left?.toString() ?: ""
        val rightStr = right?.toString() ?: ""

        when (op) {
            "contains" -> return leftStr.lowercase().contains(rightStr.lowercase())
            "matches_regex" -> return try {
                Pattern.compile(rightStr).matcher(leftStr).find()
            } catch (e: Exception) {
                false // regex sai cú pháp do LLM sinh lỗi -> coi như không khớp, không crash rule engine
            }
        }

        if (left is Number && right is Number) {
            val l = left.toDouble()
            val r = right.toDouble()
            return when (op) {
                ">=" -> l >= r
                "<=" -> l <= r
                ">" -> l > r
                "<" -> l < r
                "==" -> l == r
                "!=" -> l != r
                else -> false
            }
        }
        return when (op) {
            "==" -> leftStr.equals(rightStr, ignoreCase = true)
            "!=" -> !leftStr.equals(rightStr, ignoreCase = true)
            else -> false // so sánh lớn/bé chỉ hỗ trợ số, tránh kết quả sai lệch khó lường
        }
    }
}