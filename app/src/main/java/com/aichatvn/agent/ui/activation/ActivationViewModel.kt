package com.aichatvn.agent.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.local.DeviceIdProvider
import com.aichatvn.agent.data.remote.InviteApiService
import com.aichatvn.agent.data.remote.InviteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ActivationUiState {
    object Idle : ActivationUiState()
    object Loading : ActivationUiState()
    object Success : ActivationUiState()
    data class Error(val message: String) : ActivationUiState()
}

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val deviceIdProvider: DeviceIdProvider,
    private val inviteApiService: InviteApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow<ActivationUiState>(ActivationUiState.Idle)
    val uiState: StateFlow<ActivationUiState> = _uiState.asStateFlow()

    fun activate(code: String) {
        if (code.isBlank()) {
            _uiState.value = ActivationUiState.Error("Vui lòng nhập mã mời.")
            return
        }
        viewModelScope.launch {
            _uiState.value = ActivationUiState.Loading
            val deviceId = deviceIdProvider.getOrCreateDeviceId()
            when (val result = inviteApiService.activateInvite(code, deviceId)) {
                is InviteResult.Success -> {
                    // ✅ SỬA: markActivated() giờ cần activationToken để createInvite() sau
                    // này verify được qua HMAC (xem InviteApiService.kt). Nếu server cũ
                    // (chưa deploy bản có activation_token) trả về rỗng, lưu chuỗi rỗng —
                    // activation local vẫn thành công bình thường, chỉ riêng nút "Mời bạn
                    // dùng thử" sẽ báo lỗi "chưa kích hoạt" cho tới khi server backend được
                    // cập nhật (không crash, không chặn activation).
                    deviceIdProvider.markActivated(result.activationToken.orEmpty())
                    _uiState.value = ActivationUiState.Success
                }
                is InviteResult.Error -> {
                    _uiState.value = ActivationUiState.Error(result.message)
                }
            }
        }
    }
}