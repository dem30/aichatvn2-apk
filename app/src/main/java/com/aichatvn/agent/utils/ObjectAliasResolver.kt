package com.aichatvn.agent.utils

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObjectAliasResolver @Inject constructor() {

    companion object {
        // ✅ SỬA (bug chuẩn hoá dấu): danh sách này lưu CẢ 2 biến thể có dấu/không dấu để "phòng
        // vệ", nhưng vì matches() cũ chỉ .lowercase() (không bỏ dấu) nên chỉ biến thể ĐÚNG BOOL
        // trùng dấu với summary mới khớp — với Groq Vision (luôn trả có dấu: "người", "chó"...)
        // thì các biến thể không dấu ("nguoi", "cho") trong list này chưa từng có tác dụng thật,
        // chỉ là chết code. Giờ matches() tự normalize cả 2 phía (xem bên dưới) nên list chỉ cần
        // giữ 1 biến thể có dấu chuẩn — nhưng vẫn giữ cả 2 để không phụ thuộc hoàn toàn vào
        // normalizeVietnamese (an toàn nếu logic normalize đổi sau này).
        // ✅ MỚI: mở rộng thêm từ đồng nghĩa/khẩu ngữ vùng miền hay gặp trong log Groq Vision thực
        // tế, để tăng recall khi Groq mô tả bằng từ khác thay vì đúng nhãn enum.
        private val OBJECT_SYNONYMS = mapOf(
            "person" to listOf(
                "người", "nguoi", "trộm", "trom", "kẻ trộm", "ke trom", "xâm nhập", "xam nhap",
                "ai đó", "ai do", "khách", "khach", "kẻ lạ", "ke la", "người lạ", "nguoi la",
                "kẻ đột nhập", "ke dot nhap", "bóng người", "bong nguoi", "có người", "co nguoi",
                "đàn ông", "dan ong", "phụ nữ", "phu nu", "bé", "tre em", "trẻ em", "person"
            ),
            "car" to listOf(
                "oto", "ô tô", "xe hơi", "xe hoi", "xe bốn bánh", "xe 4 bánh", "xe con", "car"
            ),
            "motorbike" to listOf(
                "xe máy", "xe may", "xe hai bánh", "xe 2 bánh", "xe gắn máy", "xe gan may", "motorbike"
            ),
            "dog" to listOf("con chó", "con cho", "chó", "cho", "dog"),
            "cat" to listOf("con mèo", "con meo", "mèo", "meo", "cat"),
            "package" to listOf(
                "gói hàng", "goi hang", "bưu kiện", "buu kien", "shipper", "giao hàng", "giao hang", "package"
            ),
            // ✅ MỚI: bổ sung nhãn động vật gộp và phương tiện chung — GLOBAL_CAMERA_KEYWORDS/
            // AgentKernel đã dùng các nhóm này (xem "động vật"/"vehicle" trong derivedEventType
            // của CameraSkill.saveAlertToHistory) nhưng ObjectAliasResolver trước đây KHÔNG có
            // nhánh tương ứng, nên targetObject="animal"/"vehicle" luôn trả false (không khớp gì).
            "animal" to listOf("động vật", "dong vat", "con vật", "con vat", "thú cưng", "thu cung", "animal"),
            "vehicle" to listOf("xe", "phương tiện", "phuong tien", "vehicle")
        )
    }

    /**
     * So khớp thông minh xem nội dung mô tả của sự kiện có chứa vật thể đang tìm kiếm hay không.
     */
    fun matches(summary: String, label: String): Boolean {
        val targetLabel = StringSimilarityUtil.normalizeVietnamese(label.lowercase().trim())
        if (targetLabel == "all" || targetLabel.isBlank()) return true

        // ✅ SỬA (bug chính): trước đây chỉ .lowercase(), KHÔNG bỏ dấu — khiến summary có dấu
        // ("phát hiện người lạ") không bao giờ khớp targetLabel/synonym không dấu ("nguoi la"),
        // và ngược lại. normalizeVietnamese() đồng bộ cả 2 phía về cùng 1 dạng trước khi so khớp,
        // giống cách filter sourceIdOrName/detailsKeywords ở DatabaseSearchHelper đã làm đúng.
        val normalizedSummary = StringSimilarityUtil.normalizeVietnamese(summary.lowercase())

        // 1. Ưu tiên kiểm tra nhãn có cấu trúc dạng [objects: dog, car...] do CameraSkill ghi lại
        //    (nhãn gốc trong ngoặc luôn là tiếng Anh không dấu, nên so trực tiếp targetLabel là đủ)
        if (normalizedSummary.contains("[objects:") && normalizedSummary.contains(targetLabel)) {
            return true
        }

        // 2. Phòng vệ tìm kiếm từ đồng nghĩa tiếng Việt nếu dữ liệu cũ chưa được dán nhãn JSON
        val synonyms = OBJECT_SYNONYMS[targetLabel] ?: return false
        return synonyms.any { synonym ->
            normalizedSummary.contains(StringSimilarityUtil.normalizeVietnamese(synonym.lowercase()))
        }
    }
}