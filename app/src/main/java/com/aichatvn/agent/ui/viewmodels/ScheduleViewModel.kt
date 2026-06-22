package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.ScheduleSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleSkill: ScheduleSkill,
    private val agentKernel: AgentKernel // ✅ MỚI: để build dropdown Plugin/Action cho UI
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntity>> = scheduleSkill.schedules

    /**
     * ✅ MỚI: Danh sách plugin có thể lên lịch, dùng cho dropdown trong AddScheduleDialog.
     * Loại "schedule" ra khỏi danh sách - không có lý do gì để lên lịch tự gọi lại chính
     * ScheduleSkill (add/list/delete/toggle lịch trình khác), tránh gây nhiễu UI.
     * Plugin list cố định theo vòng đời Singleton -> build 1 lần là đủ, không cần StateFlow.
     */
    val schedulablePlugins: List<Plugin> =
        agentKernel.getAvailablePluginsForUI().filter { it.id != "schedule" }

    fun loadSchedules() {
        viewModelScope.launch {
            scheduleSkill.loadSchedules()
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            // ✅ FIX: schedule.params là JSON String — phải parse thành Map<String,Any>
            // trước khi truyền vào execute(). ScheduleSkill.handleAdd() expect key "params"
            // là Map<*,*>; nếu nhận String thì nestedParams = emptyMap() → params mất hết.
            val paramsMap: Map<String, Any> = try {
                val json = JSONObject(schedule.params.ifBlank { "{}" })
                buildMap {
                    json.keys().forEach { k -> put(k, json.get(k)) }
                }
            } catch (_: Exception) { emptyMap() }

            scheduleSkill.execute(
                "add",
                mapOf(
                    "pluginId" to schedule.pluginId,
                    "action" to schedule.action,
                    "cron" to schedule.cron,
                    "intervalMinutes" to schedule.intervalMinutes,
                    "params" to paramsMap   // ✅ Map, không phải String
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