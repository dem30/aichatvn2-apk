package com.aichatvn.agent.utils

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessManager @Inject constructor() {
    /**
     * Kiểm tra xem tài khoản người dùng có quyền đọc nhật ký/lịch sử hay không.
     */
    fun canReadEventLog(username: String): Boolean {
        // Hiện tại chỉ tài khoản chủ nhà default_user có quyền.
        // Thiết kế này giúp dễ dàng mở rộng sang Multi-owner/Multi-role từ DB/Cloud về sau.
        return username == "default_user"
    }
}