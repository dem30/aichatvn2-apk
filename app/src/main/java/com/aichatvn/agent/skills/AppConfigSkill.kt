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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigSkill @Inject constructor(
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("appconfig", "Cấu hình hệ thống", logger), Plugin {

    override val routable: Boolean = false
    override val visibleOnDashboard: Boolean = false
    override val autoGenerateQA: Boolean = true

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "add",
                description = "Thiết lập cấu hình hệ thống",
                examples = listOf("cài đặt cấu hình", "thiết lập thông số", "gán tham số"),
                aliases = listOf("cài đặt", "thiết lập", "cấu hình"),
                parameters = listOf(
                    PluginParameter("key", "string", "Mã khóa cấu hình", true),
                    PluginParameter("value", "string", "Giá trị cấu hình mới", true)
                )
            ),
            PluginAction(
                name = "get",
                description = "Đọc giá trị 1 biến cấu hình theo key. Ví dụ: key = camera.cooldown_ms",
                examples = listOf("xem cấu hình cooldown", "đọc biến hệ thống"),
                aliases = listOf("đọc", "lấy", "xem"),
                parameters = listOf(
                    PluginParameter("key", "string", "Tên biến cấu hình (vd: camera.cooldown_ms)", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê tất cả biến cấu hình. Có thể lọc theo plugin (camera, groq, email, schedule…).",
                examples = listOf("danh sách biến hệ thống", "liệt kê tham số cấu hình"),
                aliases = listOf("danh sách", "liệt kê"),
                parameters = listOf(
                    PluginParameter("pluginId", "string", "Lọc theo plugin (để trống = liệt kê tất cả)", false)
                )
            ),
            PluginAction(
                name = "reset",
                description = "Đặt lại 1 biến cấu hình về giá trị mặc định ban đầu.",
                examples = listOf("khôi phục cấu hình mặc định", "reset thông số hệ thống"),
                aliases = listOf("khôi phục", "reset", "đặt lại"),
                parameters = listOf(
                    PluginParameter("key", "string", "Tên biến cần reset", true)
                )
            )
        )
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "get"   -> handleGet(params)
            "set"   -> handleSet(params)
            "list"  -> handleList(params)
            "reset" -> handleReset(params)
            else    -> failure("Action không xác định: $action")
        }
    }

    private suspend fun handleGet(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val key = params["key"] as? String
            ?: return@withContext failure("Thiếu tham số key")

        val entity = configProvider.getAll().firstOrNull { it.key == key }
            ?: return@withContext failure("Không tìm thấy biến '$key'. Dùng action=list để xem danh sách.")

        success(
            "🔧 ${entity.label.ifBlank { key }} = ${entity.value}",
            mapOf("key" to entity.key, "value" to entity.value, "type" to entity.type)
        )
    }

    private suspend fun handleSet(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val key   = params["key"]   as? String ?: return@withContext failure("Thiếu tham số key")
        val value = params["value"] as? String ?: return@withContext failure("Thiếu tham số value")

        val existing = configProvider.getAll().firstOrNull { it.key == key }

        val entity = existing?.copy(value = value)
            ?: AppConfigEntity(key = key, value = value)

        configProvider.upsert(entity)
        logger.i("AppConfigSkill", "set $key = $value")

        success(
            "✅ Đã cập nhật ${entity.label.ifBlank { key }} = $value",
            mapOf("key" to key, "value" to value)
        )
    }

    private suspend fun handleList(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val pluginFilter = params["pluginId"] as? String

        val all = configProvider.getAll()
        val filtered = if (pluginFilter.isNullOrBlank()) all
        else all.filter { it.pluginId == pluginFilter }

        if (filtered.isEmpty()) {
            return@withContext failure("Không có biến cấu hình nào${if (!pluginFilter.isNullOrBlank()) " cho plugin '$pluginFilter'" else ""}.")
        }

        val summary = filtered
            .groupBy { it.pluginId }
            .entries
            .joinToString("\n") { (category, list) ->
                "🔹 $category:\n" + list.joinToString("\n") { "  - ${it.key}: ${it.value}" }
            }

        success(summary, mapOf("configs" to filtered))
    }

    private suspend fun handleReset(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val key = params["key"] as? String ?: return@withContext failure("Thiếu tham số key")

        val default = AppConfigDefaults.all().firstOrNull { it.key == key }
            ?: return@withContext failure("Không có giá trị mặc định cho '$key'.")

        configProvider.upsert(default)
        logger.i("AppConfigSkill", "reset $key -> ${default.value}")

        success(
            "🔄 Đã reset ${default.label.ifBlank { key }} về mặc định: ${default.value}",
            mapOf("key" to key, "value" to default.value)
        )
    }

    private suspend fun handleReset(key: String): PluginResult = withContext(Dispatchers.IO) {
        val default = AppConfigDefaults.all().firstOrNull { it.key == key }
            ?: return@withContext failure("Không có giá trị mặc định cho '$key'.")

        configProvider.upsert(default)
        logger.i("AppConfigSkill", "reset $key -> ${default.value}")

        success(
            "🔄 Đã reset ${default.label.ifBlank { key }} về mặc định: ${default.value}",
            mapOf("key" to key, "value" to default.value)
        )
    }

    private suspend fun handleReset(params: Map<String, Any>, key: String): PluginResult = withContext(Dispatchers.IO) {
        val default = AppConfigDefaults.all().firstOrNull { it.key == key }
            ?: return@withContext failure("Không có giá trị mặc định cho '$key'.")

        configProvider.upsert(default)
        logger.i("AppConfigSkill", "reset $key -> ${default.value}")

        success(
            "🔄 Đã reset ${default.label.ifBlank { key }} về mặc định: ${default.value}",
            mapOf("key" to key, "value" to default.value)
        )
    }

    // Sửa lỗi biên dịch cho interface Plugin gốc
    override suspend fun initialize() {}
    override suspend fun shutdown() {}
}