package com.aichatvn.agent.core

enum class IntentType {
    CHAT_QUERY,
    CAMERA_SCAN,
    CAMERA_ADD,
    CAMERA_UPDATE,
    CAMERA_DELETE,
    TRAINING_ADD,
    TRAINING_UPDATE,
    TRAINING_DELETE,
    TRAINING_SEARCH,
    IOT_CONTROL,
    SEND_EMAIL,
    SEND_NOTIFICATION,
    GET_DIAGNOSTICS,
    SYNC_CLOUD
}

data class AgentRequest(
    val intent: IntentType,
    val payload: Map<String, Any>,
    val username: String,
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentResponse(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)