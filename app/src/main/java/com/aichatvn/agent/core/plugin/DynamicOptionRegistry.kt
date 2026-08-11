// File: com/aichatvn/agent/core/plugin/DynamicOptionRegistry.kt

package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.HouseMood
import com.aichatvn.agent.data.model.displayName
import com.aichatvn.agent.devices.DeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ✅ MỚI: Emoji gợi nhớ cho từng Chế độ nhà trong picker "house_mood" — tách riêng hàm nhỏ
// ở đây (không đặt trong HouseMood.kt) vì đây là chi tiết hiển thị của riêng màn hình Planner
// Builder, không phải quy tắc nghiệp vụ chung như displayName().
private fun moodEmoji(mood: HouseMood): String = when (mood) {
    HouseMood.NORMAL -> "🙂"
    HouseMood.BUSY -> "🏃"
    HouseMood.QUIET -> "🤫"
    HouseMood.NIGHT -> "🌆"
    HouseMood.ALERT -> "🚨"
    HouseMood.SLEEPING -> "😴"
    HouseMood.VACATION -> "🏖️"
}

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
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    // ✅ MỚI (Tầng 2c — đồng bộ kiến trúc DeviceController): dùng cho case "device" và
    // "precondition_source"/"precondition_attribute"/"precondition_expected" bên dưới, thay
    // vì query thẳng database.tuyaDeviceDao() và hardcode ["camera","tuya","chat"]. Thêm
    // driver mới (MQTT...) qua DeviceModule.kt sẽ tự xuất hiện ở mọi dropdown liên quan
    // trong file này — KHÔNG cần sửa lại file này lần nữa.
    private val deviceRegistry: DeviceRegistry
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
            // ✅ SỬA (Tầng 2c): gộp thiết bị từ MỌI driver đã đăng ký (Tuya, MQTT sau này...)
            // qua deviceRegistry.all() thay vì query thẳng database.tuyaDeviceDao() — file
            // này không còn nơi nào biết "Tuya" là gì nữa, giống hệt nguyên tắc đã áp dụng
            // cho SmartSwitchSkill/TuyaViewModel ở Tầng 2b.
            "device" -> {
                deviceRegistry.all().flatMap { controller ->
                    controller.listDevices().map { dev ->
                        OptionItem(dev.id.trim(), "${dev.displayName} (${dev.id.takeLast(4)})")
                    }
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
            // ✅ MỚI: Nạp danh sách Chế độ nhà (HouseMood) để làm ngòi nổ kịch bản — trước đây
            // Planner Builder chỉ biết 3 nguồn cứng (camera/tuya/chat), không có cách nào tạo
            // kịch bản dựa trên "Chế độ nhà đổi thành X" (vd tự động bật đèn hành lang khi nhà
            // chuyển sang SLEEPING). Value dùng đúng tên enum (import com.aichatvn.agent.data.model.HouseMood)
            // vì đó là giá trị thật được ghi vào world_state (system:brain:mood, xem
            // HouseManagerSkillImpl.evaluateSituation()) — label dùng displayName() tiếng Việt.
            "house_mood" -> {
                com.aichatvn.agent.data.model.HouseMood.entries.map { mood ->
                    OptionItem(mood.name, "${moodEmoji(mood)} ${mood.displayName()}")
                }
            }
            // 🌟 MỚI: Nạp danh sách Chính sách an ninh Quản gia
            "house_policy" -> {
                listOf(
                    OptionItem("silent_night", "🌙 Ban đêm yên tĩnh (silent_night)"),
                    OptionItem("vacation_safety", "🏖️ An toàn vắng nhà (vacation_safety)")
                )
            }
            // 🌟 MỚI: Nạp danh sách cảnh báo gần đây của camera đã chọn — dùng cho
            // action mark_false_positive (tham số "alertId"), dependsOn="cameraId" giống
            // hệt cơ chế "action_id" phụ thuộc "pluginId" ở trên. Chỉ lấy 1 lần (first())
            // và giới hạn 10 bản ghi gần nhất để tránh danh sách quá dài khi camera có
            // nhiều lịch sử cảnh báo.
            "alert_id" -> {
                val cameraId = currentValues["cameraId"] ?: ""
                if (cameraId.isBlank()) return@withContext emptyList()
                database.alertDao().getAlertsByCameraFlow(cameraId).first()
                    .take(10)
                    .map { alert ->
                        val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(alert.timestamp))
                        val comment = alert.aiComment.take(40)
                        OptionItem(alert.id.trim(), "$time — $comment")
                    }
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
            // ✅ SỬA (Tầng 2c): gộp "camera" (tĩnh) + mọi protocol thiết bị đã đăng ký (Tuya,
            // MQTT sau này...) qua deviceRegistry.all() + "chat" (tĩnh) — thay vì hardcode
            // ["camera","tuya","chat"]. Thêm driver mới tự xuất hiện ở dropdown "Nguồn" của
            // Precondition Guard mà không cần sửa file này.
            "precondition_source" -> {
                listOf(OptionItem("camera", "📷 Mắt quét Camera")) +
                    deviceRegistry.all().map { controller ->
                        OptionItem(controller.worldStateSource, controller.displayName)
                    } +
                    listOf(OptionItem("chat", "💬 Hội thoại Đa kênh"))
            }
            // ✅ SỬA (Tầng 2c): nhánh "tuya" cứng trước đây đổi thành nhánh chung cho MỌI
            // protocol thiết bị đã đăng ký — Tuya và MQTT (theo SHAS_INTEGRATION_GUIDE.md,
            // MQTT chỉ có capability REALTIME_STATUS, cùng ngữ nghĩa on/off) dùng CHUNG 1 case
            // "state" boolean, không nhân bản switch theo từng protocol. Chỉ nên tách case
            // riêng khi có protocol với capability THỰC SỰ khác boolean (vd dimmer %).
            "precondition_attribute" -> {
                val source = currentValues["source"]
                when {
                    source == "camera" -> listOf(
                        OptionItem("state", "Trạng thái an ninh (state)"),
                        OptionItem("objects", "Vật thể phát hiện (objects)")
                    )
                    source == "chat" -> listOf(
                        OptionItem("session_status", "Trạng thái phiên chat"),
                        OptionItem("unread_count", "Số tin chưa đọc")
                    )
                    source != null && deviceRegistry.all().any { it.worldStateSource == source } -> listOf(
                        OptionItem("state", "Trạng thái đóng ngắt (bật/tắt)")
                    )
                    else -> listOf(OptionItem("state", "Trạng thái hoạt động"))
                }
            }
            // ✅ SỬA (Tầng 2c): cùng nguyên tắc — "true"/"false" cho MỌI protocol thiết bị đã
            // đăng ký khi attribute="state", không riêng "tuya".
            "precondition_expected" -> {
                val source = currentValues["source"]
                val attr = currentValues["attribute"]
                when {
                    source == "camera" && attr == "state" -> listOf(
                        OptionItem("normal", "Bình thường (normal)"),
                        OptionItem("suspicious", "Cảnh báo / Bất thường (suspicious)")
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
                    source != null && attr == "state" && deviceRegistry.all().any { it.worldStateSource == source } -> listOf(
                        OptionItem("true", "Đang BẬT (true)"),
                        OptionItem("false", "Đang TẮT (false)")
                    )
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
}