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
                    deviceIdProvider.markActivated()
                    _uiState.value = ActivationUiState.Success
                }
                is InviteResult.Error -> {
                    _uiState.value = ActivationUiState.Error(result.message)
                }
            }
        }
    }
}
