package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.skills.HouseManagerSkill
import com.aichatvn.agent.skills.PlanStatus
import com.aichatvn.agent.utils.WorldStateHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HouseManagerViewModel @Inject constructor(
    private val houseManagerSkill: HouseManagerSkill,
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider
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

            // 4. Đọc cấu hình ánh xạ thiết bị Quản gia + nạp danh sách thiết bị/camera thật
            // của chủ nhà để hiển thị picker chọn lựa trên Bảng cấu hình Thiết bị Răn đe.
            withContext(Dispatchers.IO) {
                _protectLightDevice.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_LIGHT, "đèn sân trước")
                _protectSirenDevice.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_SIREN, "còi báo động")
                _protectCameraIds.value = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_CAMERAS, "cam_01")
                _availableTuyaDevices.value = database.tuyaDeviceDao().getAllDevices()
                _availableCameras.value = database.cameraDao().getAllCameras()
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