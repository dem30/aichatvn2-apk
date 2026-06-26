package com.aichatvn.agent.ui.dashboard

interface DashboardProvider {
    fun getDashboardNodes(): List<DeviceNode>
}