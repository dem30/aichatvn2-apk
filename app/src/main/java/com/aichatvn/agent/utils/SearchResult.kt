package com.aichatvn.agent.utils

import com.aichatvn.agent.data.model.EventLogEntity

data class SearchResult(
    val logs: List<EventLogEntity>,      // Danh sách sự kiện đã được lọc và cắt bớt theo giới hạn
    val summaryText: String,             // Chuỗi văn bản tóm tắt định dạng sẵn để nạp nhanh vào prompt
    val totalCount: Int,                 // Tổng số lượng sự kiện tìm thấy thực tế trong DB
    val isTruncated: Boolean             // Cờ đánh dấu dữ liệu có bị cắt bớt do vượt quá limit hay không
)