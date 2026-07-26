package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.skills.CallSkill
import dagger.hilt.android.lifecycle.HiltViewModel
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
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    val callSkill: CallSkill
) : ViewModel() {

    val callUiState = callSkill.callUiState
    val localVideoTrack = callSkill.localVideoTrack
    val remoteVideoTrack = callSkill.remoteVideoTrack

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
}