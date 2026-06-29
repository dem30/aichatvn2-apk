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

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(schedule = true), // Tuyên bố năng lực lập lịch
        routable = true,
        visibleOnDashboard = false,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "add",
                description = "Thêm lịch trình mới để tự động gọi 1 action của plugin khác theo cron/interval. " +
                    "QUAN TRỌNG: param 'params' phải chứa ĐẦY ĐỦ các tham số bắt buộc của action đích " +
                    "(pluginId.action), tra đúng theo schema action đó trong danh sách plugin — " +
                    "ví dụ pluginId=email, action=send thì params={to, subject, body}; " +
                    "pluginId=camera, action=scan thì params={camera}. Áp dụng cho MỌI plugin, không riêng email.",
                examples = listOf("đặt lịch", "tạo lịch", "lên lịch", "thêm lịch"),
                parameters = listOf(
                    PluginParameter("pluginId", "string", "Tên plugin đích (camera, light, email...)", true, "plugin_id"),
                    PluginParameter("action", "string", "Hành động của plugin đích (scan, set, send...)", true, "action_id"),
                    PluginParameter("cron", "string", "Cron expression (0 7 * * *)", false, "time"),
                    PluginParameter("intervalMinutes", "number", "Khoảng cách phút", false, "interval"),
                    PluginParameter("params", "object", "Tham số cho action đích theo đúng schema của plugin đó", false, "params")
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
                description = "Xóa hoàn toàn một lịch trình tự động theo ID",
                examples = listOf("xóa lịch trình"),
                parameters = listOf(
                    PluginParameter("id", "string", "ID lịch trình", true, "string")
                )
            ),
            PluginAction(
                name = "toggle",
                description = "Bật hoặc tắt trạng thái kích hoạt của một lịch trình",
                examples = listOf("bật lịch trình", "tắt lịch trình"),
                parameters = listOf(
                    PluginParameter("id", "string", "ID lịch trình", true, "string"),
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

        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            pluginId = pluginId,
            action = action,
            params = paramsJson,
            cron = cron,
            intervalMinutes = intervalMinutes,
            enabled = 1,
            lastRunAt = 0L,
            createdAt = System.currentTimeMillis()
        )
        
        withContext(Dispatchers.IO) {
            database.scheduleDao().insertSchedule(schedule)
            TaskScheduler.runNow(context)
        }
        loadSchedules()

        return success(
            message = "✅ Đã tạo lịch: $pluginId.$action sẽ chạy ${if(cron.isNotEmpty()) "theo cron ($cron)" else "mỗi $intervalMinutes phút"}",
            data = mapOf("schedule" to schedule)
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
        
        withContext(Dispatchers.IO) {
            database.scheduleDao().deleteSchedule(id)
        }
        loadSchedules()
        
        return success("✅ Đã xóa lịch trình")
    }

    private suspend fun handleToggle(params: Map<String, Any>): AgentKernel.PluginResult {
        val id = params["id"] as? String ?: return failure("Thiếu id")
        val enabled = params["enabled"] as? Boolean ?: return failure("Thiếu enabled")
        
        withContext(Dispatchers.IO) {
            database.scheduleDao().toggleSchedule(id, if (enabled) 1 else 0)
        }
        loadSchedules()
        
        return success("✅ Đã ${if(enabled) "bật" else "tắt"} lịch trình")
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