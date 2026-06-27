package com.aichatvn.agent.ui.dashboard

data class DeviceAction(
    val id: String,
    val title: String,
    val icon: String,
    val defaultParams: Map<String, Any> = emptyMap()
)
