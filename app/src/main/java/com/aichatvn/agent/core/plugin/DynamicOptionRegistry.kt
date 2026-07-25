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
    private val database: AppDatabase
) {
    suspend fun getOptions(
        semanticType: String?,
        currentValues: Map<String, String> = emptyMap()
    ): List<OptionItem> = withContext(Dispatchers.IO) {
        if (semanticType.isNullOrBlank()) return@withContext emptyList()

        when (semanticType) {
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
                    else -> emptyList() // Nếu rỗng, UI sẽ tự động hiện ô gõ chữ
                }
            }
            else -> emptyList()
        }
    }
}