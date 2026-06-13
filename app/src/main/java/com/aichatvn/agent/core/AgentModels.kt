package com.aichatvn.agent.core

enum class IntentType {
    CHAT_QUERY,
    CAMERA_SCAN,
    CAMERA_ADD,
    CAMERA_UPDATE,
    CAMERA_DELETE,
    CAMERA_DELETE_CUSTOMER,
    CAMERA_SET_SMART_MODE,
    CAMERA_SET_ACTIVE,
    CAMERA_GET_PAGINATED,
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