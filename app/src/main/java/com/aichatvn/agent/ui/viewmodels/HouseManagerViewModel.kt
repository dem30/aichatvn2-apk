package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.skills.HouseManagerSkill
import com.aichatvn.agent.skills.PlanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HouseManagerViewModel @Inject constructor(
    private val houseManagerSkill: HouseManagerSkill
) : ViewModel() {

    private val _situation = MutableStateFlow<HouseSituation?>(null)
    val situation: StateFlow<HouseSituation?> = _situation.asStateFlow()

    private val _activePlans = MutableStateFlow<List<PlanStatus>>(emptyList())
    val activePlans: StateFlow<List<PlanStatus>> = _activePlans.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // Tải thông tin ban đầu và kích hoạt vòng lặp cập nhật trạng thái kịch bản thời gian thực
        refreshAll()
        startPlanMonitoringLoop()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Yêu cầu Quản gia quy nạp lại tình huống sống
                val sit = houseManagerSkill.evaluateSituation()
                _situation.value = sit
                _activePlans.value = houseManagerSkill.getActivePlans()
            } catch (e: Exception) {
                _situation.value = null
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // Kích hoạt thủ công kịch bản liên hoàn bảo vệ nhà khẩn cấp để kiểm thử nhanh trên UI
    fun triggerPanicSequence(cameraId: String) {
        viewModelScope.launch {
            houseManagerSkill.triggerProtectHouseSequence(cameraId)
            _activePlans.value = houseManagerSkill.getActivePlans()
        }
    }

    // Định kỳ 1 giây thăm dò tiến trình đếm ngược/log chạy dưới nền của Planner để cập nhật UI Screen
    private fun startPlanMonitoringLoop() {
        viewModelScope.launch {
            while (true) {
                try {
                    _activePlans.value = houseManagerSkill.getActivePlans()
                } catch (e: Exception) {
                    // Tránh crash khi có ngoại lệ
                }
                delay(1000L) // Cập nhật nhịp đập tiến trình mỗi giây
            }
        }
    }
}
