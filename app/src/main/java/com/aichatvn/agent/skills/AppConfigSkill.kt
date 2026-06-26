package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppConfigSkill
 *
 * Plugin quản lý cấu hình toàn cục.
 * Cho phép người dùng đọc / ghi config qua chat hoặc qua UI Settings.
 *
 * Actions:
 *  - get   : đọc 1 biến theo key
 *  - set   : ghi 1 biến theo key
 *  - list  : liệt kê tất cả biến (hoặc lọc theo pluginId)
 *  - reset : reset 1 biến về giá trị mặc định
 */
@Singleton
class AppConfigSkill @Inject constructor(
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("appconfig", "Cấu hình hệ thống", logger), Plugin {


  override val routable: Boolean = false
    override val visibleOnDashboard: Boolean = false
    override val autoGenerateQA: Boolean = false
    override fun getActions(): List<PluginAction> = listOf(
        PluginAction(
            name = "get",
            description = "Đọc giá trị 1 biến cấu hình theo key. Ví dụ: key = camera.cooldown_ms",
            parameters = listOf(
                PluginParameter("key", "string", "Tên biến cấu hình (vd: camera.cooldown_ms)", true)
            )
        ),
        PluginAction(
            name = "set",
            description = "Ghi / cập nhật giá trị 1 biến cấu hình. Ví dụ: đặt camera.cooldown_ms = 3600000",
            parameters = listOf(
                PluginParameter("key", "string", "Tên biến cấu hình", true),
                PluginParameter("value", "string", "Giá trị mới (kiểu string — hệ thống tự ép kiểu)", true)
            )
        ),
        PluginAction(
            name = "list",
            description = "Liệt kê tất cả biến cấu hình. Có thể lọc theo plugin (camera, groq, email, schedule…).",
            parameters = listOf(
                PluginParameter("pluginId", "string", "Lọc theo plugin (để trống = liệt kê tất cả)", false)
            )
        ),
        PluginAction(
            name = "reset",
            description = "Đặt lại 1 biến cấu hình về giá trị mặc định ban đầu.",
            parameters = listOf(
                PluginParameter("key", "string", "Tên biến cần reset", true)
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "get"   -> handleGet(params)
            "set"   -> handleSet(params)
            "list"  -> handleList(params)
            "reset" -> handleReset(params)
            else    -> failure("Action không xác định: $action")
        }
    }

    // ─────────────────────────── GET ────────────────────────────

    private suspend fun handleGet(params: Map<String, Any>): PluginResult {
        val key = params["key"] as? String
            ?: return failure("Thiếu tham số key")

        val entity = configProvider.getAll().firstOrNull { it.key == key }
            ?: return failure("Không tìm thấy biến '$key'. Dùng action=list để xem danh sách.")

        return success(
            "🔧 ${entity.label.ifBlank { key }} = ${entity.value}",
            mapOf("key" to entity.key, "value" to entity.value, "type" to entity.type)
        )
    }

    // ─────────────────────────── SET ────────────────────────────

    private suspend fun handleSet(params: Map<String, Any>): PluginResult {
        val key   = params["key"]   as? String ?: return failure("Thiếu tham số key")
        val value = params["value"] as? String ?: return failure("Thiếu tham số value")

        val existing = configProvider.getAll().firstOrNull { it.key == key }

        // Nếu đã có entity thì giữ nguyên metadata, chỉ đổi value
        val entity = existing?.copy(value = value)
            ?: AppConfigEntity(key = key, value = value)

        configProvider.upsert(entity)
        logger.i("AppConfigSkill", "set $key = $value")

        return success(
            "✅ Đã cập nhật ${entity.label.ifBlank { key }} = $value",
            mapOf("key" to key, "value" to value)
        )
    }

    // ─────────────────────────── LIST ───────────────────────────

    private suspend fun handleList(params: Map<String, Any>): PluginResult {
        val pluginFilter = params["pluginId"] as? String

        val all = configProvider.getAll()
        val filtered = if (pluginFilter.isNullOrBlank()) all
        else all.filter { it.pluginId == pluginFilter }

        if (filtered.isEmpty()) {
            return failure("Không có biến cấu hình nào${if (!pluginFilter.isNullOrBlank()) " cho plugin '$pluginFilter'" else ""}.")
        }

        val summary = filtered
            .groupBy { it.pluginId }
            .entries
            .joinToString("\n\n") { (pid, items) ->
                "📦 $pid\n" + items.joinToString("\n") { e ->
                    "  • ${e.key} = ${e.value}  (${e.type})"
                }
            }

        return success(summary, mapOf("configs" to filtered))
    }

    // ─────────────────────────── RESET ──────────────────────────

    private suspend fun handleReset(params: Map<String, Any>): PluginResult {
        val key = params["key"] as? String ?: return failure("Thiếu tham số key")

        val default = AppConfigDefaults.all().firstOrNull { it.key == key }
            ?: return failure("Không có giá trị mặc định cho '$key'.")

        configProvider.upsert(default)
        logger.i("AppConfigSkill", "reset $key -> ${default.value}")

        return success(
            "🔄 Đã reset ${default.label.ifBlank { key }} về mặc định: ${default.value}",
            mapOf("key" to key, "value" to default.value)
        )
    }
}