package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.skills.HouseManagerSkill
import com.aichatvn.agent.skills.PlanStatus
import com.aichatvn.agent.skills.WorkflowGroup
import com.aichatvn.agent.skills.workflowGroupsFromJson
import com.aichatvn.agent.skills.workflowGroupsToJson
import com.aichatvn.agent.utils.WorldStateHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HouseManagerViewModel @Inject constructor(
    private val houseManagerSkill: HouseManagerSkill,
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider,
    private val agentKernel: AgentKernel // Lấy danh sách plugin để hiển thị dropdown chọn plugin trong AlertActionFormSheet
) : ViewModel() {

    private val _situation = MutableStateFlow<HouseSituation?>(null)
    val situation: StateFlow<HouseSituation?> = _situation.asStateFlow()

    private val _activePlans = MutableStateFlow<List<PlanStatus>>(emptyList())
    val activePlans: StateFlow<List<PlanStatus>> = _activePlans.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Các luồng trạng thái động đồng bộ hai chiều với UI Screen
    private val _isSilentNightPolicyEnabled = MutableStateFlow(true)
    val isSilentNightPolicyEnabled: StateFlow<Boolean> = _isSilentNightPolicyEnabled.asStateFlow()

    private val _isVacationSafetyPolicyEnabled = MutableStateFlow(true)
    val isVacationSafetyPolicyEnabled: StateFlow<Boolean> = _isVacationSafetyPolicyEnabled.asStateFlow()

    private val _lastLearningRunTime = MutableStateFlow("Chưa chạy")
    val lastLearningRunTime: StateFlow<String> = _lastLearningRunTime.asStateFlow()

    // ✅ MỚI: Khung giờ "Đang ngủ / Ban đêm" chủ nhà tự chỉnh — thay cho hardcode cứng
    // 22h-6h trước đây trong HouseManagerSkillImpl.isNightTime().
    private val _sleepStartHour = MutableStateFlow(22)
    val sleepStartHour: StateFlow<Int> = _sleepStartHour.asStateFlow()

    private val _sleepEndHour = MutableStateFlow(6)
    val sleepEndHour: StateFlow<Int> = _sleepEndHour.asStateFlow()

    // ✅ MỚI: Ánh xạ thiết bị Quản gia (Device Mapping) — đồng bộ hai chiều với
    // Bảng cấu hình Thiết bị Răn đe trên HouseManagerScreen. Thay cho hardcode
    // "đèn sân trước" / "còi báo động" / "cam_01" trong kịch bản Planner.
    private val _protectLightDevice = MutableStateFlow("đèn sân trước")
    val protectLightDevice: StateFlow<String> = _protectLightDevice.asStateFlow()

    private val _protectSirenDevice = MutableStateFlow("còi báo động")
    val protectSirenDevice: StateFlow<String> = _protectSirenDevice.asStateFlow()

    // Lưu dạng chuỗi "id1,id2" giống AppConfig; Screen tự split thành List để hiển thị picker chọn nhiều.
    private val _protectCameraIds = MutableStateFlow("cam_01")
    val protectCameraIds: StateFlow<String> = _protectCameraIds.asStateFlow()

    // Danh sách thiết bị Tuya / camera thật đang có trong nhà — nạp cho picker chọn lựa
    // (thay vì bắt chủ nhà gõ tay tên thiết bị, dễ gõ sai khiến kịch bản gãy).
    private val _availableTuyaDevices = MutableStateFlow<List<TuyaDeviceEntity>>(emptyList())
    val availableTuyaDevices: StateFlow<List<TuyaDeviceEntity>> = _availableTuyaDevices.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val availableCameras: StateFlow<List<CameraConfigEntity>> = _availableCameras.asStateFlow()

    // ✅ MỚI: Danh sách thớt chat khách hàng thật đang có trong Bản sao số (world_state,
    // source = "chat") — nạp cho picker chatSession trong AlertActionFormSheet.
    private val _availableChatSessions = MutableStateFlow<List<String>>(emptyList())
    val availableChatSessions: StateFlow<List<String>> = _availableChatSessions.asStateFlow()

    // ⚠️ ĐÃ XÓA: _protectActions/protectActions (bản mirror 1 chiều từ nhóm "wf_security") chỉ
    // được CustomPlannerCard đọc/ghi trên UI — Card đó đã bị xóa (dead code, không còn được gọi ở
    // đâu). Xóa luôn state này để tránh vòng đồng bộ mồ côi không ai đọc.

    // ✅ MỚI: Toàn bộ các Nhóm kịch bản (Workflow Groups) — nguồn sự thật duy nhất cho việc
    // kích hoạt tự động; UI đa nhóm (MultiWorkflowPlannerSection) đọc/ghi trực tiếp qua đây.
    private val _workflowGroups = MutableStateFlow<List<WorkflowGroup>>(emptyList())
    val workflowGroups: StateFlow<List<WorkflowGroup>> = _workflowGroups.asStateFlow()

    // Danh sách plugin khả dụng để hiển thị trong dropdown chọn plugin/action của AlertActionFormSheet
    val alertActionPlugins: List<Plugin> = agentKernel.getAvailablePluginsForUI()

    init {
        refreshAll()
        startPlanMonitoringLoop()
    }

    fun refreshAll() {
        viewModelScope.launch {
            performRefresh()
        }
    }

    // ✅ ĐÃ SỬA: Tách phần lõi thành hàm suspend riêng (performRefresh) để các hàm
    // setAwayMode/togglePolicy/mineHabitsNow có thể "await" trực tiếp thay vì gọi
    // refreshAll() cũ (chỉ bắn 1 coroutine rời rạc, không đợi -> _isRefreshing bị
    // tắt sớm trong khi dữ liệu vẫn đang tải, gây race trên trạng thái loading).
    private suspend fun performRefresh() {
        _isRefreshing.value = true
        try {
            // 1. Quy nạp trạng thái tình huống sống
            val sit = houseManagerSkill.evaluateSituation()
            _situation.value = sit
            _activePlans.value = houseManagerSkill.getActivePlans()

            // 2. Đọc cấu hình chính sách từ Bản sao số (World State)
            val silentNight = withContext(Dispatchers.IO) {
                WorldStateHelper.getAttribute(database.worldStateDao(), "system", "policy", "silent_night") ?: "true"
            }
            _isSilentNightPolicyEnabled.value = silentNight == "true"

            val vacationSafety = withContext(Dispatchers.IO) {
                WorldStateHelper.getAttribute(database.worldStateDao(), "system", "policy", "vacation_safety") ?: "true"
            }
            _isVacationSafetyPolicyEnabled.value = vacationSafety == "true"

            // 3. Đọc mốc thời gian tự học thói quen gần nhất
            val lastRunMillis = withContext(Dispatchers.IO) {
                WorldStateHelper.getAttribute(database.worldStateDao(), "system", "brain", "last_learning_run")
            }
            _lastLearningRunTime.value = lastRunMillis?.toLongOrNull()?.let {
                val sdf = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
                sdf.format(Date(it))
            } ?: "Chưa chạy"

            // 3b. Đọc khung giờ ngủ (Sleep Schedule) do chủ nhà tự cấu hình
            withContext(Dispatchers.IO) {
                _sleepStartHour.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_SLEEP_START_HOUR, "22")
                    .toIntOrNull()?.coerceIn(0, 23) ?: 22
                _sleepEndHour.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_SLEEP_END_HOUR, "6")
                    .toIntOrNull()?.coerceIn(0, 23) ?: 6
            }

            // 4. Đọc cấu hình ánh xạ thiết bị Quản gia + nạp danh sách thiết bị/camera thật
            // của chủ nhà để hiển thị picker chọn lựa trên Bảng cấu hình Thiết bị Răn đe.
            withContext(Dispatchers.IO) {
                _protectLightDevice.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_LIGHT, "đèn sân trước")
                _protectSirenDevice.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_SIREN, "còi báo động")
                _protectCameraIds.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_CAMERAS, "cam_01")

                // ✅ Đọc 1 lần từ HOUSE_MANAGER_WORKFLOWS để populate danh sách đầy đủ (UI đa nhóm).
                val workflowsJson = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_WORKFLOWS, "[]")
                val groups = workflowGroupsFromJson(workflowsJson)
                _workflowGroups.value = groups

                _availableTuyaDevices.value = database.tuyaDeviceDao().getAllDevices()
                _availableCameras.value = database.cameraDao().getAllCameras()

                // ✅ MỚI (Đọc thớt chat): Lọc tất cả trạng thái trong Bản sao số có source = "chat"
                // để lấy danh sách username động (vd: facebook_1234, telegram_5678) cho picker.
                _availableChatSessions.value = database.worldStateDao().getAllStatesFlow().first()
                    .filter { it.source == "chat" }
                    .map { it.sourceId.trim() }
            }

        } catch (e: Exception) {
            _situation.value = null
        } finally {
            _isRefreshing.value = false
        }
    }

    fun setAwayMode(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Cập nhật trạng thái away_mode vào SQLite
                WorldStateHelper.setAttribute(database.worldStateDao(), "system", "house", "away_mode", enabled.toString())
            }
            // Quy nạp lại tình huống ngay lập tức để UI Screen đổi màu theo Mood (đã await)
            performRefresh()
        }
    }

    fun togglePolicy(policyId: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                WorldStateHelper.setAttribute(database.worldStateDao(), "system", "policy", policyId, enabled.toString())
            }
            if (policyId == "silent_night") {
                _isSilentNightPolicyEnabled.value = enabled
            } else if (policyId == "vacation_safety") {
                _isVacationSafetyPolicyEnabled.value = enabled
            }
            performRefresh()
        }
    }

    fun mineHabitsNow() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                houseManagerSkill.mineUserHabits()
            } catch (e: Exception) {
                // Xử lý lỗi
            }
            performRefresh()
        }
    }

    // Lưu ánh xạ thiết bị khi chủ nhà chọn lại trên picker của Bảng cấu hình Thiết bị Răn đe.
    fun saveDeviceMappings(lightDevice: String, sirenDevice: String, cameraIds: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configProvider.set(AppConfigDefaults.HOUSE_MANAGER_PROTECT_LIGHT, lightDevice)
                configProvider.set(AppConfigDefaults.HOUSE_MANAGER_PROTECT_SIREN, sirenDevice)
                configProvider.set(AppConfigDefaults.HOUSE_MANAGER_PROTECT_CAMERAS, cameraIds.joinToString(","))
            }
            performRefresh()
        }
    }

    // ✅ MỚI: Lưu khung giờ ngủ khi chủ nhà chỉnh trên SleepScheduleCard.
    fun saveSleepSchedule(startHour: Int, endHour: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configProvider.set(AppConfigDefaults.HOUSE_MANAGER_SLEEP_START_HOUR, startHour.coerceIn(0, 23).toString())
                configProvider.set(AppConfigDefaults.HOUSE_MANAGER_SLEEP_END_HOUR, endHour.coerceIn(0, 23).toString())
            }
            performRefresh()
        }
    }

    // ⚠️ ĐÃ XÓA: addProtectAction/removeProtectAction/saveProtectActions — nơi gọi duy nhất là
    // CustomPlannerCard (đã xóa ở màn hình). Việc thêm/xóa bước cho nhóm "wf_security" giờ đi
    // hoàn toàn qua addStepToGroup/removeStepFromGroup bên dưới (dùng chung cho MỌI nhóm kịch
    // bản, được WorkflowGroupCard gọi) — không cần một cặp hàm riêng chỉ để xử lý 1 nhóm.

    // ─── CÁC API ĐIỀU HÀNH ĐA NHÓM DÀNH CHO TRÌNH SOẠN THẢO TRỰC QUAN ───

    private fun saveAllWorkflows(groups: List<WorkflowGroup>) {
        viewModelScope.launch(Dispatchers.IO) {
            configProvider.set(AppConfigDefaults.HOUSE_MANAGER_WORKFLOWS, workflowGroupsToJson(groups))
            performRefresh()
        }
    }

    // Tạo mới một nhóm kịch bản — người dùng chọn qua VisualTriggerBuilderDialog, không cần biết JSON.
    fun createWorkflowGroup(label: String, sourceType: String, entityId: String, expectedValue: String) {
        val compiledTrigger = when (sourceType) {
            "camera" -> "camera.$entityId.state=$expectedValue"
            "tuya" -> "tuya.$entityId.state=$expectedValue"
            "chat" -> "chat.$entityId.urgency=$expectedValue"
            else -> "system.brain.state=normal"
        }

        val newGroup = WorkflowGroup(
            id = "wf_" + UUID.randomUUID().toString().take(8),
            label = label,
            triggerSource = compiledTrigger,
            enabled = true,
            steps = emptyList()
        )
        saveAllWorkflows(_workflowGroups.value + newGroup)
    }

    fun deleteWorkflowGroup(groupId: String) {
        saveAllWorkflows(_workflowGroups.value.filter { it.id != groupId })
    }

    fun toggleWorkflowGroup(groupId: String, enabled: Boolean) {
        saveAllWorkflows(_workflowGroups.value.map { if (it.id == groupId) it.copy(enabled = enabled) else it })
    }

    fun addStepToGroup(groupId: String, step: AlertActionConfig) {
        saveAllWorkflows(_workflowGroups.value.map { group ->
            if (group.id == groupId) group.copy(steps = group.steps + step) else group
        })
    }

    fun removeStepFromGroup(groupId: String, stepIndex: Int) {
        saveAllWorkflows(_workflowGroups.value.map { group ->
            if (group.id == groupId) {
                group.copy(steps = group.steps.filterIndexed { index, _ -> index != stepIndex })
            } else group
        })
    }

    fun triggerPanicSequence(cameraId: String) {
        viewModelScope.launch {
            houseManagerSkill.triggerProtectHouseSequence(cameraId)
            _activePlans.value = houseManagerSkill.getActivePlans()
        }
    }

    private fun startPlanMonitoringLoop() {
        viewModelScope.launch {
            while (true) {
                try {
                    _activePlans.value = houseManagerSkill.getActivePlans()
                } catch (_: Exception) {}
                delay(1000L)
            }
        }
    }
}