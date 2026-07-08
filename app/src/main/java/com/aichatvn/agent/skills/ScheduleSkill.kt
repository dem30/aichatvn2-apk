package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.scheduler.TaskScheduler
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: Logger
) : BaseSkill("schedule", "Lên lịch trình", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(schedule = true),
        routable = true,
        visibleOnDashboard = false,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "add",
                description = "Thêm lịch trình mới để tự động gọi 1 action của plugin khác theo cron/interval. " +
                    "QUAN TRỌNG: param 'params' phải chứa ĐẦY ĐỦ các tham số bắt buộc của action đích.",
                examples = listOf("đặt lịch", "tạo lịch", "lên lịch", "thêm lịch"),
                parameters = listOf(
                    PluginParameter("pluginId", "string", "Tên plugin đích (camera, light, email...)", true, "plugin_id"),
                    PluginParameter("action", "string", "Hành động của plugin đích (scan, set, send...)", true, "action_id"),
                    PluginParameter("cron", "string", "Cron expression (0 7 * * *)", false, "time"),
                    PluginParameter("intervalMinutes", "number", "Khoảng cách phút", false, "interval"),
                    PluginParameter("params", "object", "Tham số cho action đích theo đúng schema của plugin đó", false, "params"),
                    PluginParameter("label", "string", "Tên gợi nhớ cho lịch trình", false, "string")
                )
            ),
            PluginAction(
                name = "update",
                description = "Cập nhật thay đổi một lịch trình tự động có sẵn theo ID.",
                examples = listOf("sửa lịch trình", "cập nhật lịch trình", "thay đổi lịch trình", "đặt lại lịch"),
                parameters = listOf(
                    PluginParameter("id", "string", "ID của lịch trình cần cập nhật", true, "schedule_ref"),
                    PluginParameter("pluginId", "string", "Tên plugin đích (camera, light, email...)", false, "plugin_id"),
                    PluginParameter("action", "string", "Hành động của plugin đích (scan, set, send...)", false, "action_id"),
                    PluginParameter("cron", "string", "Cron expression (0 7 * * *)", false, "time"),
                    PluginParameter("intervalMinutes", "number", "Khoảng cách phút", false, "interval"),
                    PluginParameter("params", "object", "Tham số mới cho action đích", false, "params"),
                    PluginParameter("label", "string", "Tên gợi nhớ mới cho lịch trình", false, "string")
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê danh sách tất cả các lịch trình đang hoạt động",
                examples = listOf("danh sách lịch trình", "xem lịch trình"),
                parameters = emptyList()
            ),
            PluginAction(
                name = "delete",
                description = "Xóa hoàn toàn một lịch trình tự động theo số thứ tự hoặc tên gợi nhớ",
                examples = listOf("xóa lịch trình"),
                parameters = listOf(
                    PluginParameter("id", "string", "Số thứ tự hoặc tên lịch trình", true, "schedule_ref")
                )
            ),
            PluginAction(
                name = "toggle",
                description = "Bật hoặc tắt trạng thái kích hoạt của một lịch trình",
                examples = listOf("bật lịch trình", "tắt lịch trình"),
                exampleOverrides = mapOf(
                    "bật lịch trình" to mapOf("enabled" to true),
                    "tắt lịch trình" to mapOf("enabled" to false)
                ),
                parameters = listOf(
                    PluginParameter("id", "string", "Số thứ tự hoặc tên lịch trình", true, "schedule_ref"),
                    PluginParameter("enabled", "boolean", "true: bật, false: tắt", true, "boolean")
                )
            )
        )
    )

    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _schedules = MutableStateFlow<List<ScheduleEntity>>(emptyList())
    val schedules: StateFlow<List<ScheduleEntity>> = _schedules.asStateFlow()

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        return when (action) {
            "add" -> handleAdd(params)
            "update" -> handleUpdate(params)
            "list" -> handleList()
            "delete" -> handleDelete(params)
            "toggle" -> handleToggle(params)
            else -> failure("Action không xác định: $action")
        }
    }

    private suspend fun handleAdd(params: Map<String, Any>): AgentKernel.PluginResult {
        val pluginId = params["pluginId"] as? String
            ?: return failure("Thiếu pluginId")
        
        val action = params["action"] as? String
            ?: return failure("Thiếu action")
        
        val cron = params["cron"] as? String ?: ""
        val intervalMinutes = (params["intervalMinutes"] as? Number)?.toInt() ?: 0
        
        if (cron.isEmpty() && intervalMinutes <= 0) {
            return failure("Cần cron hoặc intervalMinutes")
        }
        
        @Suppress("UNCHECKED_CAST")
        val nestedParams: Map<String, Any> = when (val raw = params["params"]) {
            is Map<*, *> -> raw as Map<String, Any>
            null -> emptyMap()
            else -> emptyMap()
        }
        val paramsJson = JSONObject(nestedParams).toString()

        val label = (params["label"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "$pluginId.$action"

        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            pluginId = pluginId,
            action = action,
            params = paramsJson,
            cron = cron,
            intervalMinutes = intervalMinutes,
            enabled = 1,
            lastRunAt = 0L,
            createdAt = System.currentTimeMillis(),
            label = label
        )
        
        withContext(Dispatchers.IO) {
            database.scheduleDao().insertSchedule(schedule)
            TaskScheduler.runNow(context)
        }
        loadSchedules()

        return success(
            message = "✅ Đã tạo lịch \"$label\": sẽ chạy ${if(cron.isNotEmpty()) "theo cron ($cron)" else "mỗi $intervalMinutes phút"}",
            data = mapOf("schedule" to schedule)
        )
    }

    private suspend fun handleUpdate(params: Map<String, Any>): AgentKernel.PluginResult {
        val id = params["id"] as? String ?: return failure("Thiếu id")
        
        val existing = withContext(Dispatchers.IO) {
            database.scheduleDao().getAllSchedules().find { it.id == id }
        } ?: return failure("Không tìm thấy lịch trình cần sửa")

        val pluginId = params["pluginId"] as? String ?: existing.pluginId
        val action = params["action"] as? String ?: existing.action
        val cron = params["cron"] as? String ?: ""
        val intervalMinutes = (params["intervalMinutes"] as? Number)?.toInt() ?: 0

        @Suppress("UNCHECKED_CAST")
        val nestedParams: Map<String, Any> = when (val raw = params["params"]) {
            is Map<*, *> -> raw as Map<String, Any>
            null -> emptyMap()
            else -> emptyMap()
        }
        val paramsJson = if (params.containsKey("params")) {
            JSONObject(nestedParams).toString()
        } else {
            existing.params
        }

        val label = (params["label"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: existing.label

        val updatedSchedule = existing.copy(
            pluginId = pluginId,
            action = action,
            params = paramsJson,
            cron = cron,
            intervalMinutes = intervalMinutes,
            label = label
        )

        withContext(Dispatchers.IO) {
            database.scheduleDao().insertSchedule(updatedSchedule)
            TaskScheduler.runNow(context)
        }
        loadSchedules()

        return success(
            message = "✅ Đã cập nhật lịch trình \"$label\" thành công.",
            data = mapOf("schedule" to updatedSchedule)
        )
    }

    private suspend fun handleList(): AgentKernel.PluginResult {
        val list = withContext(Dispatchers.IO) {
            database.scheduleDao().getAllSchedules()
        }
        return AgentKernel.PluginResult.Success(list)
    }

    private suspend fun handleDelete(params: Map<String, Any>): AgentKernel.PluginResult {
        val id = params["id"] as? String ?: return failure("Thiếu id")
        val label = _schedules.value.find { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: id

        withContext(Dispatchers.IO) {
            database.scheduleDao().deleteSchedule(id)
        }
        loadSchedules()
        
        return success("✅ Đã xóa lịch trình \"$label\"")
    }

    private suspend fun handleToggle(params: Map<String, Any>): AgentKernel.PluginResult {
        val id = params["id"] as? String ?: return failure("Thiếu id")
        val enabled = params["enabled"] as? Boolean ?: return failure("Thiếu enabled")
        val label = _schedules.value.find { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: id

        withContext(Dispatchers.IO) {
            database.scheduleDao().toggleSchedule(id, if (enabled) 1 else 0)
        }
        loadSchedules()
        
        return success("✅ Đã ${if(enabled) "bật" else "tắt"} lịch trình \"$label\"")
    }

    suspend fun loadSchedules() {
        val list = withContext(Dispatchers.IO) {
            database.scheduleDao().getAllSchedules()
        }
        _schedules.value = list
    }

    override suspend fun initialize() {
        loadSchedules()
    }

    override suspend fun shutdown() {}
}