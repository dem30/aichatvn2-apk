package com.aichatvn.agent.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.local.DeviceIdProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

// ✅ SỬA (fix "phải tắt/mở app mới thấy tab"): trước đây đọc activation_status ĐÚNG 1 LẦN lúc
// init (deviceIdProvider.isActivated()), nên khi ActivationScreen kích hoạt thành công và gọi
// DeviceIdProvider.markActivated() ghi "true" xuống Room, giá trị _isActivated ở đây KHÔNG hề
// biết — nó chỉ đọc lại đúng lúc app khởi động lần sau. AppNavigator lại dùng chính giá trị này
// để quyết định hiện/ẩn bottom nav, nên bottom nav bị ẩn cho tới khi restart app.
// Nay chuyển sang collect DeviceIdProvider.observeIsActivated() (Flow phản ứng theo Room) —
// ngay khi markActivated() upsert xong, Flow tự bắn giá trị mới, _isActivated cập nhật ngay
// trong cùng phiên, không cần tắt/mở lại app.
@HiltViewModel
class ActivationGateViewModel @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider
) : ViewModel() {

    // null = đang đọc lần đầu, true/false = đã có kết quả (và tiếp tục cập nhật theo Room)
    private val _isActivated = MutableStateFlow<Boolean?>(null)
    val isActivated: StateFlow<Boolean?> = _isActivated.asStateFlow()

    init {
        viewModelScope.launch {
            deviceIdProvider.getOrCreateDeviceId() // đảm bảo device_id tồn tại từ lần mở đầu tiên
        }
        deviceIdProvider.observeIsActivated()
            .onEach { activated -> _isActivated.value = activated }
            .launchIn(viewModelScope)
    }
}
