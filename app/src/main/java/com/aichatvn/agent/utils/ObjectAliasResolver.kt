package com.aichatvn.agent.utils

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObjectAliasResolver @Inject constructor() {

    companion object {
        // Ánh xạ nhãn đối tượng tiếng Anh sang các từ đồng nghĩa tiếng Việt phổ biến
        private val OBJECT_SYNONYMS = mapOf(
            "person" to listOf("người", "nguoi", "trộm", "trom", "xâm nhập", "xam nhap", "ai đó", "ai do", "khách", "khach", "person"),
            "car" to listOf("oto", "ô tô", "xe hơi", "xe hoi", "xe bốn bánh", "car"),
            "motorbike" to listOf("xe máy", "xe may", "xe hai bánh", "motorbike"),
            "dog" to listOf("con chó", "con cho", "chó", "cho", "dog"),
            "cat" to listOf("con mèo", "con meo", "mèo", "meo", "cat"),
            "package" to listOf("gói hàng", "goi hang", "bưu kiện", "buu kien", "shipper", "package")
        )
    }

    /**
     * So khớp thông minh xem nội dung mô tả của sự kiện có chứa vật thể đang tìm kiếm hay không.
     */
    fun matches(summary: String, label: String): Boolean {
        val targetLabel = label.lowercase().trim()
        if (targetLabel == "all" || targetLabel.isBlank()) return true

        val normalizedSummary = summary.lowercase()

        // 1. Ưu tiên kiểm tra nhãn có cấu trúc dạng [objects: dog, car...] do CameraSkill ghi lại
        if (normalizedSummary.contains("[objects:") && normalizedSummary.contains(targetLabel)) {
            return true
        }

        // 2. Phòng vệ tìm kiếm từ đồng nghĩa tiếng Việt nếu dữ liệu cũ chưa được dán nhãn JSON
        val synonyms = OBJECT_SYNONYMS[targetLabel] ?: return false
        return synonyms.any { synonym -> normalizedSummary.contains(synonym) }
    }
}