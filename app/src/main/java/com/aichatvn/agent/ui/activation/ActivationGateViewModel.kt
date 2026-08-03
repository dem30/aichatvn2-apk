package com.aichatvn.agent.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.local.DeviceIdProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ✅ MỚI: ViewModel mỏng riêng cho việc đọc activation_status 1 lần lúc app khởi động, để
// AppNavigator không phải tự gọi DeviceIdProvider trực tiếp trong Composable (Compose chỉ nên
// lấy dependency qua hiltViewModel()).
@HiltViewModel
class ActivationGateViewModel @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider
) : ViewModel() {

    // null = đang đọc, true/false = đã có kết quả
    private val _isActivated = MutableStateFlow<Boolean?>(null)
    val isActivated: StateFlow<Boolean?> = _isActivated.asStateFlow()

    init {
        viewModelScope.launch {
            deviceIdProvider.getOrCreateDeviceId() // đảm bảo device_id tồn tại từ lần mở đầu tiên
            _isActivated.value = deviceIdProvider.isActivated()
        }
    }
}
