// File: com/aichatvn/agent/core/plugin/DynamicOptionRegistry.kt

package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class OptionItem(
    val value: String, // Giá trị thực gửi cho backend/plugin
    val label: String  // Chuỗi hiển thị thân thiện trên UI
)

@Singleton
class DynamicOptionRegistry @Inject constructor(
    private val database: AppDatabase,
    // 🌟 MỚI: cần Set<Plugin> để build danh sách "chọn Plugin/Action đích" cho case
    // plugin_id/action_id bên dưới — dùng lại đúng multibinding Set<Plugin> đã có sẵn qua
    // các @IntoSet trong AppModule.kt (đã dùng cho AgentKernel), không cần khai báo gì thêm
    // ở tầng DI.
    private val plugins: Set<@JvmSuppressWildcards Plugin>
) {
    suspend fun getOptions(
        semanticType: String?,
        currentValues: Map<String, String> = emptyMap()
    ): List<OptionItem> = withContext(Dispatchers.IO) {
        if (semanticType.isNullOrBlank()) return@withContext emptyList()

        when (semanticType) {
            // 🌟 MỚI: Nạp danh sách Plugin đích để lên lịch (dùng cho schedule.add/update,
            // tham số "pluginId" semanticType="plugin_id"). Loại "schedule" khỏi chính nó để
            // tránh đệ quy (lên lịch cho hành động... lên lịch).
            "plugin_id" -> {
                plugins.filter { it.manifest.routable && it.manifest.id != "schedule" }
                    .map { OptionItem(it.manifest.id, it.manifest.name) }
            }
            // 🌟 MỚI: Nạp danh sách Action của Plugin đã chọn ở "pluginId" phía trên — dùng
            // dependsOn="pluginId" (xem PluginParameter khai báo trong ScheduleSkill.kt), giống
            // hệt cơ chế precondition_attribute phụ thuộc "source" bên dưới.
            "action_id" -> {
                val pluginId = currentValues["pluginId"] ?: currentValues["plugin_id"] ?: ""
                plugins.find { it.manifest.id == pluginId }
                    ?.manifest?.actions
                    ?.filter { it.enabled }
                    ?.map { OptionItem(it.name, it.description) }
                    ?: emptyList()
            }
            "device" -> {
                database.tuyaDeviceDao().getAllDevices().map { dev ->
                    OptionItem(dev.id.trim(), "${dev.name} (${dev.id.takeLast(4)})")
                }
            }
            "camera" -> {
                database.cameraDao().getActiveCameras().map { cam ->
                    OptionItem(cam.id.trim(), "${cam.customername} (${cam.id.trim()})")
                }
            }
            // ✅ MỚI: Nạp danh sách Danh bạ cuộc gọi P2P đã lưu — dùng cho action start_call
            // (tham số targetDeviceCode, semanticType="call") khi thiếu, hỏi lại kèm gợi ý
            // chọn nhanh giống hệt cách "device"/"camera" đã làm ở trên. Sắp yêu thích lên
            // đầu (đã có sẵn trong observeAll(), không cần sort lại ở đây).
            "call" -> {
                database.callContactDao().observeAll().first().map { contact ->
                    val fav = if (contact.isFavorite) "⭐ " else ""
                    OptionItem(contact.deviceCode.trim(), "$fav${contact.displayName} (${contact.deviceCode.trim()})")
                }
            }
            // 🌟 MỚI: Nạp danh sách Khách hàng từ Database
            "customer" -> {
                database.customerDao().getAllCustomersFlow().first().map { cust ->
                    OptionItem(cust.id.trim(), "${cust.name} (${cust.id.trim()})")
                }
            }
            // 🌟 MỚI: Nạp danh sách Email của các Khách hàng
            "customer_email", "email" -> {
                database.customerDao().getAllCustomersFlow().first()
                    .filter { it.email.isNotBlank() }
                    .map { cust ->
                        OptionItem(cust.email.trim(), "${cust.name} <${cust.email.trim()}>")
                    }
            }
            // 🌟 MỚI: Nạp danh sách Chính sách an ninh Quản gia
            "house_policy" -> {
                listOf(
                    OptionItem("silent_night", "🌙 Ban đêm yên tĩnh (silent_night)"),
                    OptionItem("vacation_safety", "🏖️ An toàn vắng nhà (vacation_safety)")
                )
            }
            // 🌟 MỚI: Nạp danh sách Lịch trình tự động đang có
            "schedule", "schedule_ref" -> {
                database.scheduleDao().getAllSchedules().map { sch ->
                    val timing = if (sch.cron.isNotBlank()) "⏰ ${sch.cron}" else "🔁 ${sch.intervalMinutes}m"
                    OptionItem(sch.id.trim(), "${sch.label.ifBlank { "${sch.pluginId}.${sch.action}" }} ($timing)")
                }
            }
            "chatSession" -> {
                database.worldStateDao().getAllStatesFlow().first()
                    .filter { it.source == "chat" }
                    .map { state ->
                        val platform = state.sourceId.substringBefore("_").uppercase()
                        val rawId = state.sourceId.substringAfter("_")
                        OptionItem(state.sourceId.trim(), "$rawId [$platform]")
                    }
            }
            "precondition_source" -> {
                listOf(
                    OptionItem("camera", "📷 Mắt quét Camera"),
                    OptionItem("tuya", "🔌 Thiết bị đóng ngắt Tuya"),
                    OptionItem("chat", "💬 Hội thoại Đa kênh")
                )
            }
            "precondition_attribute" -> {
                when (currentValues["source"]) {
                    "camera" -> listOf(
                        OptionItem("state", "Trạng thái an ninh (state)"),
                        OptionItem("objects", "Vật thể phát hiện (objects)")
                    )
                    "tuya" -> listOf(
                        OptionItem("state", "Trạng thái đóng ngắt (bật/tắt)")
                    )
                    "chat" -> listOf(
                        OptionItem("session_status", "Trạng thái phiên chat"),
                        OptionItem("unread_count", "Số tin chưa đọc")
                    )
                    else -> listOf(OptionItem("state", "Trạng thái hoạt động"))
                }
            }
            "precondition_expected" -> {
                val source = currentValues["source"]
                val attr = currentValues["attribute"]
                when {
                    source == "camera" && attr == "state" -> listOf(
                        OptionItem("normal", "Bình thường (normal)"),
                        OptionItem("suspicious", "Cảnh báo / Bất thường (suspicious)")
                    )
                    source == "tuya" && attr == "state" -> listOf(
                        OptionItem("true", "Đang BẬT (true)"),
                        OptionItem("false", "Đang TẮT (false)")
                    )
                    source == "chat" && attr == "session_status" -> listOf(
                        OptionItem("waiting_agent", "Chờ người trực trả lời"),
                        OptionItem("resolved", "Đã giải quyết xong")
                    )
                    source == "chat" && attr == "unread_count" -> listOf(
                        OptionItem("0", "0 tin nhắn"),
                        OptionItem("1", "1 tin nhắn"),
                        OptionItem("2", "2 tin nhắn"),
                        OptionItem("3", "3 tin nhắn")
                    )
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
}