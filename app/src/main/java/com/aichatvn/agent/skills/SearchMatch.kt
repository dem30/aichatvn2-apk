package com.aichatvn.agent.skills

import com.aichatvn.agent.data.model.QAEntity

// ✅ TÁCH BIỆT: Định nghĩa SearchMatch ra tệp tin độc lập giúp compiler và KSP nhận diện toàn cục ổn định nhất
data class SearchMatch(
    val qa: QAEntity,
    val similarity: Float
)