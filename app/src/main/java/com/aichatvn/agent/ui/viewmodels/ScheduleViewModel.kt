package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.ScheduleSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleSkill: ScheduleSkill,
    private val agentKernel: AgentKernel,
    private val database: AppDatabase
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntity>> = scheduleSkill.schedules

    private val _tuyaDevices = MutableStateFlow<List<TuyaDeviceEntity>>(emptyList())
    val tuyaDevices: StateFlow<List<TuyaDeviceEntity>> = _tuyaDevices.asStateFlow()

    private val _activeCameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val activeCameras: StateFlow<List<CameraConfigEntity>> = _activeCameras.asStateFlow()

    val schedulablePlugins: List<Plugin> =
        agentKernel.getAvailablePluginsForUI().filter { it.id != "schedule" }

    init {
        loadSchedules()
        loadDevicesAndCameras()
    }

    fun loadSchedules() {
        viewModelScope.launch {
            scheduleSkill.loadSchedules()
        }
    }

    fun loadDevicesAndCameras() {
        viewModelScope.launch(Dispatchers.IO) {
            _tuyaDevices.value = database.tuyaDeviceDao().getAllDevices()
            _activeCameras.value = database.cameraDao().getActiveCameras()
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        saveSchedule(schedule, isEdit = false)
    }

    fun updateSchedule(schedule: ScheduleEntity) {
        saveSchedule(schedule, isEdit = true)
    }

    private fun saveSchedule(schedule: ScheduleEntity, isEdit: Boolean) {
        viewModelScope.launch {
            val paramsMap: Map<String, Any> = withContext(Dispatchers.Default) {
                try {
                    val json = JSONObject(schedule.params.ifBlank { "{}" })
                    jsonToMap(json)
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            val actionName = if (isEdit) "update" else "add"
            val executionParams = mutableMapOf<String, Any>(
                "pluginId" to schedule.pluginId,
                "action" to schedule.action,
                "cron" to schedule.cron,
                "intervalMinutes" to schedule.intervalMinutes,
                "params" to paramsMap,
                "label" to schedule.label
            )
            if (isEdit) {
                executionParams["id"] = schedule.id
            }

            scheduleSkill.execute(actionName, executionParams)
            loadSchedules()
        }
    }

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