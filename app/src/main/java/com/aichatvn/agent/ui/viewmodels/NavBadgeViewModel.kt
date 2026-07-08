package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * NavBadgeViewModel
 *
 * ✅ MỚI: ViewModel RIÊNG, TÁCH BIỆT với ChatViewModel — mục đích duy nhất là cấp dữ liệu cho
 * badge đỏ trên icon tab "Trò chuyện" ở BottomNavigation (xem AppNavigator.kt).
 *
 * Lý do không tái dùng ChatViewModel cho việc này: ChatViewModel được `hiltViewModel()` scope
 * theo TỪNG NavBackStackEntry (mỗi route "chat_screen?username=..." có 1 instance riêng, gắn
 * với 1 khách cụ thể) — không phù hợp để biểu diễn "tổng số tin nhắn chưa đọc trên TOÀN APP".
 * NavBadgeViewModel ngược lại được gọi ngay tại gốc AppNavigator() — bên NGOÀI mọi
 * composable(route) { ... } — nên hiltViewModel() ở đó tự động scope theo Activity/NavGraph,
 * cho ra 1 instance DUY NHẤT sống xuyên suốt phiên, luôn cập nhật đúng dù Admin đang đứng ở
 * tab nào (kể cả khi không hề mở ChatScreen của khách nào).
 */
@HiltViewModel
class NavBadgeViewModel @Inject constructor(
    database: AppDatabase
) : ViewModel() {

    // Tổng số tin nhắn khách (role="user") CHƯA ĐỌC, gộp từ MỌI khách ngoại kênh cùng lúc.
    val totalUnreadCount: StateFlow<Int> = database.chatMessageDao()
        .getUnreadCountsFlow()
        .map { list -> list.sumOf { it.unreadCount } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}