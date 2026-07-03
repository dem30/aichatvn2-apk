package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
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

    // ✅ ĐÃ SỬA: Chuyển đổi toàn bộ cấu trúc định danh cũ sang PluginManifest thống nhất
    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(), // Năng lực cơ bản mặc định
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "add",
                description = "Thiết lập cấu hình hệ thống",
                examples = listOf("cài đặt cấu hình", "thiết lập thông số", "gán tham số"),
                parameters = listOf(
                    PluginParameter("key", "string", "Mã khóa cấu hình", true),
                    PluginParameter("value", "string", "Giá trị cấu hình mới", true)
                )
            ),
            PluginAction(
                name = "get",
                description = "Đọc giá trị 1 biến cấu hình theo key. Ví dụ: key = camera.cooldown_ms",
                examples = listOf("xem cấu hình cooldown", "đọc biến hệ thống"),
                parameters = listOf(
                    PluginParameter("key", "string", "Tên biến cấu hình (vd: camera.cooldown_ms)", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê tất cả biến cấu hình. Có thể lọc theo plugin (camera, groq, email, schedule…).",
                examples = listOf("danh sách biến hệ thống", "liệt kê tham số cấu hình"),
                parameters = listOf(
                    PluginParameter("pluginId", "string", "Lọc theo plugin (để trống = liệt kê tất cả)", false)
                )
            ),
            PluginAction(
                name = "reset",
                description = "Đặt lại 1 biến cấu hình về giá trị mặc định ban đầu.",
                examples = listOf("khôi phục cấu hình mặc định", "reset thông số hệ thống"),
                parameters = listOf(
                    PluginParameter("key", "string", "Tên biến cần reset", true)
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "get"   -> handleGet(params)
            "add", "set" -> handleSet(params) // "set" giữ lại làm alias tương thích ngược cho code gọi trực tiếp bằng chuỗi cũ, không khai báo trong manifest
            "list"  -> handleList(params)
            "reset" -> handleReset(params)
            else    -> failure("Action không xác định: $action")
        }
    }

    private suspend fun handleGet(params: Map<String, Any>): PluginResult {
        val key = params["key"] as? String
            ?: return failure("Thiếu tham số key")

        val entity = configProvider.allConfigs.value.firstOrNull { it.key == key }
            ?: return failure("Không tìm thấy biến '$key'. Dùng action=list để xem danh sách.")

        return success(
            "🔧 ${entity.label.ifBlank { key }} = ${entity.value}",
            mapOf("key" to entity.key, "value" to entity.value, "type" to entity.type)
        )
    }

    private suspend fun handleSet(params: Map<String, Any>): PluginResult {
        val key   = params["key"]   as? String ?: return failure("Thiếu tham số key")
        val value = params["value"] as? String ?: return failure("Thiếu tham số value")

        val existing = configProvider.allConfigs.value.firstOrNull { it.key == key }

        val entity = existing?.copy(value = value)
            ?: AppConfigEntity(key = key, value = value)

        configProvider.upsert(entity)
        logger.i("AppConfigSkill", "set $key = $value")

        return success(
            "✅ Đã cập nhật ${entity.label.ifBlank { key }} = $value",
            mapOf("key" to key, "value" to value)
        )
    }

    private suspend fun handleList(params: Map<String, Any>): PluginResult {
        val pluginFilter = params["pluginId"] as? String

        val all = configProvider.allConfigs.value
        val filtered = if (pluginFilter.isNullOrBlank()) all
        else all.filter { it.pluginId == pluginFilter }

        if (filtered.isEmpty()) {
            return failure("Không có biến cấu hình nào${if (!pluginFilter.isNullOrBlank()) " cho plugin '$pluginFilter'" else ""}.")
        }

        val summary = filtered
            .groupBy { it.pluginId }
            .entries
            .joinToString("\n") { (category, list) ->
                "🔹 $category:\n" + list.joinToString("\n") { "  - ${it.key}: ${it.value}" }
            }

        return success(summary, mapOf("configs" to filtered))
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

    override suspend fun initialize() {}
    override suspend fun shutdown() {}
}