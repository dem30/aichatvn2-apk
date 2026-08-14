package com.aichatvn.agent.ui.dashboard

interface DashboardProvider {
    suspend fun getDashboardNodes(): List<DeviceNode>

    /**
     * ✅ MỚI: đọc trạng thái ON/OFF nhanh, KHÔNG build lại toàn bộ DeviceNode (x/y/room/
     * scene/supportedActions...) — dùng cho vòng poll tần suất cao (vd 5s/lần khi Dashboard
     * đang mở) mà getDashboardNodes() quá nặng để gọi lặp lại. Trả về Map<deviceId, isOn>.
     *
     * Có giá trị mặc định (rỗng) để KHÔNG bắt buộc mọi plugin implement DashboardProvider
     * phải override — driver nào cần poll nhanh (vd SmartSwitchSkill/Tuya) thì override,
     * driver nào không cần (vd camera) thì bỏ qua, không phá gì đã có.
     */
    suspend fun pollLightweightStates(): Map<String, Boolean> = emptyMap()
}