package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.ScheduleSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleSkill: ScheduleSkill,
    private val agentKernel: AgentKernel
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntity>> = scheduleSkill.schedules

    val schedulablePlugins: List<Plugin> =
        agentKernel.getAvailablePluginsForUI().filter { it.id != "schedule" }

    init {
        // Tự động tải trước dữ liệu lịch trình khi khởi tạo để tránh màn hình rỗng ban đầu
        loadSchedules()
    }

    fun loadSchedules() {
        viewModelScope.launch {
            scheduleSkill.loadSchedules()
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            // Chuyển việc parse chuỗi JSON sang Dispatchers.Default để tránh chiếm dụng luồng chính
            val paramsMap: Map<String, Any> = withContext(Dispatchers.Default) {
                try {
                    val json = JSONObject(schedule.params.ifBlank { "{}" })
                    jsonToMap(json)
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            scheduleSkill.execute(
                "add",
                mapOf(
                    "pluginId" to schedule.pluginId,
                    "action" to schedule.action,
                    "cron" to schedule.cron,
                    "intervalMinutes" to schedule.intervalMinutes,
                    "params" to paramsMap
                )
            )
            loadSchedules()
        }
    }

    // Đệ quy giải mã chuỗi JSON lồng ghép sang Map và List chuẩn Kotlin bản địa
    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val value = json.get(key)
            if (value != JSONObject.NULL) {
                map[key] = when (value) {
                    is JSONObject -> jsonToMap(value)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            val item = value.get(i)
                            if (item != JSONObject.NULL) {
                                list.add(if (item is JSONObject) jsonToMap(item) else item)
                            }
                        }
                        list
                    }
                    else -> value
                }
            }
        }
        return map
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