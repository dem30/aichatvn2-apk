package com.aichatvn.agent.ui.dashboard

interface DashboardProvider {
   suspend fun getDashboardNodes(): List<DeviceNode>
}