package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ScheduleEntity
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
import com.aichatvn.agent.core.plugin.DynamicOptionRegistry // 🌟 MỚI


@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleSkill: ScheduleSkill,
    private val agentKernel: AgentKernel,
    private val database: AppDatabase,
    val optionRegistry: DynamicOptionRegistry // 🌟 MỚI: Inject Bộ điều hành Dropdown Động
) : ViewModel() {

    val schedules: StateFlow<List<ScheduleEntity>> = scheduleSkill.schedules

    // ✅ MỚI: bề mặt kết quả thật của lần lưu gần nhất — trước đây saveSchedule() gọi
    // scheduleSkill.execute() nhưng KHÔNG đọc PluginResult trả về, nên khi handleAdd() từ
    // chối lưu (thiếu tham số bắt buộc, ví dụ "device"/"cameraId" khi tạo lịch nhanh từ
    // Dashboard mà quên merge node.defaultParams), lỗi bị nuốt âm thầm — UI tưởng đã lưu
    // xong (dialog đóng bình thường) trong khi DB không hề có bản ghi mới. Nơi gọi
    // (DashboardScreen/ScheduleScreen) tự quyết định hiển thị (snackbar/toast) rồi gọi
    // clearSaveResultMessage(), không ép buộc UI cụ thể ở đây.
    private val _saveResultMessage = MutableStateFlow<String?>(null)
    val saveResultMessage: StateFlow<String?> = _saveResultMessage.asStateFlow()

    fun clearSaveResultMessage() {
        _saveResultMessage.value = null
    }

    val schedulablePlugins: List<Plugin> =
        agentKernel.getAvailablePluginsForUI().filter { it.id != "schedule" }

    init {
        loadSchedules()
    }

    fun loadSchedules() {
        viewModelScope.launch {
            scheduleSkill.loadSchedules()
        }
    }

    fun addSchedule(schedule: ScheduleEntity) {
        saveSchedule(schedule, isEdit = false)
    }

    // ✅ MỚI: sao chép lịch có sẵn — KHÁC addSchedule() thường ở chỗ gửi kèm "copy" = true để
    // ScheduleSkill.handleAdd() bỏ qua kiểm tra trùng (xem ghi chú tại ScheduleSkill.kt). Bản
    // sao lúc mới tạo giống hệt bản gốc 100% (chỉ khác id/label) nên chắc chắn sẽ bị chặn nếu
    // đi qua addSchedule() thường — đó chính là lý do nút "Sao chép" trước đây luôn báo trùng.
    fun copySchedule(schedule: ScheduleEntity) {
        saveSchedule(schedule, isEdit = false, isCopy = true)
    }

    fun updateSchedule(schedule: ScheduleEntity) {
        saveSchedule(schedule, isEdit = true)
    }



    
    private fun saveSchedule(schedule: ScheduleEntity, isEdit: Boolean, isCopy: Boolean = false) {
    viewModelScope.launch {
        // ✅ GIẢI PHÁP PHÒNG THỦ: Nếu là chỉnh sửa (isEdit = true), truy xuất bản ghi cũ
        // trong DB để giữ lại các trường cấu hình nâng cao (alertActions, force), tránh việc UI lịch chung ghi đè làm mất.
        val existingParamsMap = if (isEdit) {
            withContext(Dispatchers.IO) {
                database.scheduleDao().getScheduleById(schedule.id)
            }?.params?.let { existingJson ->
                try {
                    val json = JSONObject(existingJson)
                    jsonToMap(json)
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val paramsMap: Map<String, Any> = withContext(Dispatchers.Default) {
            try {
                val json = JSONObject(schedule.params.ifBlank { "{}" })
                val newMap = jsonToMap(json).toMutableMap()
                
                // Trộn cấu hình alertActions và force cũ từ database vào nếu bản ghi mới từ UI bị thiếu
                existingParamsMap?.forEach { (key, value) ->
                    if (!newMap.containsKey(key)) {
                        newMap[key] = value
                    }
                }
                newMap
            } catch (_: Exception) {
                existingParamsMap ?: emptyMap()
            }
        }

        val actionName = if (isEdit) "update" else "add"
        val executionParams = mutableMapOf<String, Any>(
            "pluginId" to schedule.pluginId,
            "action" to schedule.action,
            "cron" to schedule.cron,
            "intervalMinutes" to schedule.intervalMinutes,
            "params" to paramsMap, // ✅ Gửi đi paramsMap đã được bảo vệ cấu hình
            "label" to schedule.label
        )
        if (isEdit) {
            executionParams["id"] = schedule.id
        }
        if (isCopy) {
            executionParams["copy"] = true
        }

        // ✅ MỚI: đọc PluginResult thật thay vì bỏ qua — xem ghi chú ở _saveResultMessage.
        when (val result = scheduleSkill.execute(actionName, executionParams)) {
            is PluginResult.Success -> {
                val isDuplicate = (result.data as? Map<*, *>)?.get("duplicate") as? Boolean ?: false
                _saveResultMessage.value = if (isDuplicate) {
                    "ℹ️ Lịch trình tương tự đã tồn tại — không tạo thêm bản trùng."
                } else {
                    "✅ Đã lưu lịch trình."
                }
            }
            is PluginResult.Failure -> {
                _saveResultMessage.value = "❌ Không thể lưu lịch trình: ${result.error}"
            }
            is PluginResult.NeedMoreInfo -> {
                _saveResultMessage.value = "⚠️ Yêu cầu thông tin thêm: ${result.question}"
            }
        }
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