package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.ScheduleSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleSkill: ScheduleSkill
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntity>> = scheduleSkill.schedules

    fun loadSchedules() {
        viewModelScope.launch {
            scheduleSkill.loadSchedules()
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            // Gọi plugin execute để thêm
            scheduleSkill.execute(
                "add",
                mapOf(
                    "pluginId" to schedule.pluginId,
                    "action" to schedule.action,
                    "cron" to schedule.cron,
                    "intervalMinutes" to schedule.intervalMinutes,
                    "params" to schedule.params
                )
            )
            loadSchedules()
        }
    }

    fun toggleSchedule(id: String) {
        viewModelScope.launch {
            val current = schedules.value.find { it.id == id }
            if (current != null) {
                scheduleSkill.execute(
                    "toggle",
                    mapOf(
                        "id" to id,
                        "enabled" to (current.enabled != 1)
                    )
                )
                loadSchedules()
            }
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            scheduleSkill.execute("delete", mapOf("id" to id))
            loadSchedules()
        }
    }
}