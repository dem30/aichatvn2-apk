package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentRequest
import com.aichatvn.agent.core.AgentRouter
import com.aichatvn.agent.core.IntentType
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSkill: ChatSkill,
    private val agentRouter: AgentRouter
) : ViewModel() {

    val messages: StateFlow<List<ChatMessageEntity>> = chatSkill.messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            chatSkill.initialize()
        }
    }

    fun sendMessage(message: String) {
        viewModelScope.launch {
            _isLoading.value = true
            agentRouter.route(
                AgentRequest(
                    intent = IntentType.CHAT_QUERY,
                    payload = mapOf("message" to message, "context" to ""),
                    username = "default_user"
                )
            )
            _isLoading.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatSkill.clearHistory("default_user")
        }
    }
}
