package com.aichatvn.agent.utils

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptGuard @Inject constructor(
    private val configProvider: AppConfigProvider
) {
    /**
     * Sinh nội dung Prompt chống ảo tưởng (hallucination) và hướng dẫn AI gọi Tool.
     */
    suspend fun buildGuard(routerFailed: Boolean, reason: String? = null): String {
        val maxSentences = configProvider.getInt(AppConfigDefaults.GLOBAL_CHAT_MAX_SENTENCES, 4)
        
        val antiHallucination =
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được.\n" +
            "⚠️ Trả lời NGẮN GỌN, đi thẳng vào trọng tâm — tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ."

        val toolCallingRule =
            "\n\n🚨 QUY TẮC TRUY VẤN DỮ LIỆU LỊCH SỬ:\n" +
            "Nếu người dùng yêu cầu tìm kiếm, liệt kê, kiểm tra, hỏi về quá khứ/lịch sử hoặc " +
            "hỏi thông tin về các sự kiện của camera/thiết bị (ví dụ: 'có con chó nào không', 'liệt kê đi', " +
            "'mấy giờ xe vào', 'ai nhắn tin'...) nhưng trong đoạn chat chưa có dữ liệu lịch sử thô này:\n" +
            "Bạn BẮT BUỘC phải phản hồi bằng một chuỗi JSON thô theo đúng định dạng sau để hệ thống truy vấn " +
            "giúp bạn (không bọc trong markdown, không giải thích gì thêm, không viết chữ nào khác ngoài JSON):\n" +
            "{\"tool\": \"db_search\", \"timeframe\": \"today|yesterday|last_3_days|last_7_days\", \"object\": \"person|car|motorbike|dog|cat|package|all\"}\n" +
            "Nếu dữ liệu lịch sử thô ĐÃ có sẵn trong ngữ cảnh chat trước đó hoặc bạn vừa được hệ thống cung cấp " +
            "ở trên, hãy trả lời trực tiếp một cách tự nhiên, TUYỆT ĐỐI không gọi lại tool nữa."

        return buildString {
            append(antiHallucination)
            if (routerFailed) {
                append("\n⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định được chính xác (lý do nội bộ: ${reason ?: "unknown"}). Hãy báo cho user là lệnh CHƯA thực hiện được và hỏi họ nói rõ hơn, ĐỪNG khẳng định đã làm.")
            }
            append(toolCallingRule)
        }
    }
}