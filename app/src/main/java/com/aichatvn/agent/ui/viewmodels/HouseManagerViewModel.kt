package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.HouseSituation
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
    private val database: AppDatabase
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

    init {
        refreshAll()
        startPlanMonitoringLoop()
    }

    fun refreshAll() {
        viewModelScope.launch {
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

            } catch (e: Exception) {
                _situation.value = null
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setAwayMode(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Cập nhật trạng thái away_mode vào SQLite
                WorldStateHelper.setAttribute(database.worldStateDao(), "system", "house", "away_mode", enabled.toString())
            }
            // Quy nạp lại tình huống ngay lập tức để UI Screen đổi màu theo Mood
            refreshAll()
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
            refreshAll()
        }
    }

    fun mineHabitsNow() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                houseManagerSkill.mineUserHabits()
                refreshAll()
            } catch (e: Exception) {
                // Xử lý lỗi
            } finally {
                _isRefreshing.value = false
            }
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