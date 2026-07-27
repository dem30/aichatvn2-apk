package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.CallContactEntity
import com.aichatvn.agent.data.model.CallLogEntity
import com.aichatvn.agent.skills.CallSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CallViewModel
 *
 * CallSkill là @Singleton (Plugin dùng chung cho AgentKernel), không phải ViewModel — nên
 * không thể hiltViewModel<CallSkill>() thẳng trong Compose. Lớp này chỉ bọc mỏng lại, expose
 * StateFlow sẵn có của CallSkill và các hàm gọi execute() qua viewModelScope, để DialScreen/
 * CallScreen dùng hiltViewModel<CallViewModel>() theo đúng pattern các màn khác trong app
 * (ChatViewModel, DashboardViewModel, HouseManagerViewModel...).
 *
 * LƯU Ý: chỉ nên hiltViewModel<CallViewModel>() DUY NHẤT 1 lần ở scope Activity (trong
 * AppNavigator, ngoài NavHost) rồi truyền xuống DialScreen/CallScreen — không gọi lại trong
 * từng composable(route), để tránh tạo nhiều instance ViewModel lệch nhau khi debug (dù cùng
 * trỏ 1 CallSkill @Singleton nên state cuối cùng vẫn đúng).
 *
 * ✅ MỚI: expose thêm danh bạ (contacts) và lịch sử gọi (callLogs) dưới dạng StateFlow —
 * quan sát trực tiếp Room qua CallSkill, tự cập nhật realtime khi có cuộc gọi mới/contact mới,
 * không cần DialScreen tự query lại.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    val callSkill: CallSkill
) : ViewModel() {

    val callUiState = callSkill.callUiState
    val localVideoTrack = callSkill.localVideoTrack
    val remoteVideoTrack = callSkill.remoteVideoTrack

    val contacts: StateFlow<List<CallContactEntity>> = callSkill.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val callLogs: StateFlow<List<CallLogEntity>> = callSkill.observeCallLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun getMyCode(): String = callSkill.getOrCreateMyDeviceCode()

    fun startCall(targetDeviceCode: String, video: Boolean) {
        viewModelScope.launch {
            callSkill.execute(
                "start_call",
                mapOf("targetDeviceCode" to targetDeviceCode, "video" to video)
            )
        }
    }

    fun answer(callId: String) {
        viewModelScope.launch {
            callSkill.execute("answer_call", mapOf("callId" to callId))
        }
    }

    fun reject(callId: String) {
        viewModelScope.launch {
            callSkill.execute("reject_call", mapOf("callId" to callId))
        }
    }

    fun hangup() {
        callSkill.endCall()
    }

    // ✅ MỚI
    fun toggleMute() = callSkill.toggleMute()

    fun toggleSpeaker() = callSkill.toggleSpeaker()

    fun saveContact(deviceCode: String, displayName: String) {
        viewModelScope.launch { callSkill.saveContact(deviceCode, displayName) }
    }

    fun deleteContact(deviceCode: String) {
        viewModelScope.launch { callSkill.deleteContact(deviceCode) }
    }
}