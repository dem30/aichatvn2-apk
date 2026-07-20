package com.aichatvn.agent.skills

import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.HouseMood
import com.aichatvn.agent.data.model.HouseSituation
import com.aichatvn.agent.data.model.WorldStateEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.WorldStateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseManagerSkillImpl @Inject constructor(
    private val database: AppDatabase,
    logger: Logger
) : BaseSkill("house_manager", "Quản gia điều hành thông minh", logger), HouseManagerSkill {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(), // Quản gia đóng vai trò não bộ, không vẽ trực tiếp lên dashboard
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "evaluate",
                description = "Chạy thuật toán quy nạp để phân tích trạng thái sống của ngôi nhà",
                parameters = emptyList()
            ),
            PluginAction(
                name = "get_context",
                description = "Đọc ngữ cảnh tổng hợp từ Quản gia dưới dạng văn bản tự nhiên",
                parameters = emptyList()
            )
        )
    )

    private val mutex = Mutex()
    private var cachedSituation: HouseSituation? = null

    override suspend fun initialize() {
        logger.i("HouseManagerSkill", "🧠 Bộ não Quản gia đã khởi tạo. Chạy phân tích móng...")
        evaluateSituation()
    }

    override suspend fun shutdown() {}

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "evaluate" -> {
                val sit = evaluateSituation()
                PluginResult.Success(mapOf("situation" to sit, "message" to "✅ Đã quy nạp trạng thái: Chế độ ${sit.currentMood}. ${sit.summary}"))
            }
            "get_context" -> {
                val context = buildSystemContext()
                PluginResult.Success(mapOf("context" to context, "message" to context))
            }
            else -> PluginResult.Failure("Hành động không hỗ trợ: $action")
        }
    }

    override suspend fun evaluateSituation(): HouseSituation = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // 1. Đọc toàn bộ Bản sao số (World State) hiện có thông qua Flow.first()
                val states = database.worldStateDao().getAllStatesFlow().first()

                // 2. Phân tích trạng thái Camera
                val cameraStates = states.filter { it.source == "camera" }
                val isSuspicious = cameraStates.any { getAttr(it, "state") == "suspicious" }
                
                var suspiciousCount = 0
                var guestCount = 0
                cameraStates.forEach { state ->
                    if (getAttr(state, "state") == "suspicious") suspiciousCount++
                    // Bóc tách số lượng vật thể từ mảng objects lưu trữ trong world_state
                    val objectsJson = getAttr(state, "objects")
                    if (!objectsJson.isNullOrBlank()) {
                        try {
                            val arr = org.json.JSONArray(objectsJson)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optString(i, "").lowercase()
                                if (obj == "person" || obj == "nguoi") guestCount++
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 3. Phân tích thiết bị đóng ngắt Tuya
                val tuyaStates = states.filter { it.source == "tuya" }
                val activeDevicesCount = tuyaStates.count { getAttr(it, "state") == "true" }

                // 4. Phân tích trạng thái kênh Chat ngoại tuyến
                val chatStates = states.filter { it.source == "chat" }
                var totalUnreadChats = 0
                chatStates.forEach { state ->
                    val unread = try {
                        JSONObject(state.attributesJson).optInt("unread_count", 0)
                    } catch (_: Exception) { 0 }
                    totalUnreadChats += unread
                }

                // 5. Xác định sự diện diện của chủ nhà (Mặc định dựa vào trạng thái thiết bị hoặc cấu hình)
                // Có thể mở rộng đọc thêm GPS hoặc định danh khuôn mặt từ camera
                val ownerPresent = true 

                // 6. Quy nạp Chế độ vận hành (HouseMood)
                val computedMood = when {
                    isSuspicious -> HouseMood.ALERT
                    totalUnreadChats > 3 -> HouseMood.BUSY
                    isNightTime() && activeDevicesCount == 0 -> HouseMood.SLEEPING
                    isNightTime() -> HouseMood.NIGHT
                    !ownerPresent -> HouseMood.VACATION
                    else -> HouseMood.NORMAL
                }

                val securityLevel = when {
                    isSuspicious -> 2
                    guestCount > 0 -> 1
                    else -> 0
                }

                // 7. Tạo chuỗi tóm tắt bằng ngôn ngữ tự nhiên tối giản phục vụ RAG
                val summary = buildString {
                    append("Ngôi nhà đang ở trạng thái an ninh ")
                    append(if (securityLevel == 2) "🚨 CẢNH BÁO CAO" else "✅ AN TOÀN")
                    append(". Tâm trạng ngôi nhà là ${computedMood.name}.")
                    if (activeDevicesCount > 0) append(" Có $activeDevicesCount thiết bị điện đang bật.")
                    if (totalUnreadChats > 0) append(" Đang có $totalUnreadChats tin nhắn chờ xử lý từ khách đa kênh.")
                }

                val situation = HouseSituation(
                    securityLevel = securityLevel,
                    ownerPresent = ownerPresent,
                    guestsCount = guestCount,
                    pendingChatsCount = totalUnreadChats,
                    activeDevicesCount = activeDevicesCount,
                    suspiciousObjectsCount = suspiciousCount,
                    currentMood = computedMood,
                    summary = summary
                )

                cachedSituation = situation

                // Đồng bộ ngược trạng thái Quản gia vào Bản sao số dưới thực thể "system:brain"
                val brainJson = JSONObject().apply {
                    put("mood", computedMood.name)
                    put("security_level", securityLevel)
                    put("owner_present", ownerPresent)
                    put("active_devices_count", activeDevicesCount)
                    put("unread_chats_count", totalUnreadChats)
                    put("summary", summary)
                }.toString()

                database.worldStateDao().upsertState(
                    WorldStateEntity(
                        id = "system:brain",
                        source = "system",
                        sourceId = "brain",
                        attributesJson = brainJson,
                        updatedAt = System.currentTimeMillis()
                    )
                )

                situation
            } catch (e: Exception) {
                logger.e("HouseManagerSkill", "Lỗi phân tích trạng thái ngôi nhà: ${e.message}", e)
                // Fallback khi gặp sự cố SQLite hoặc khởi tạo ban đầu
                HouseSituation(
                    securityLevel = 0,
                    ownerPresent = true,
                    guestsCount = 0,
                    pendingChatsCount = 0,
                    activeDevicesCount = 0,
                    suspiciousObjectsCount = 0,
                    currentMood = HouseMood.NORMAL,
                    summary = "Không thể phân tích Bản sao số. Hệ thống tự động chuyển về chế độ An toàn mặc định."
                )
            }
        }
    }

    override suspend fun onWorldStateChanged(source: String, sourceId: String, key: String, value: String) {
        logger.d("HouseManagerSkill", "🔔 Nhận tin báo từ $source:$sourceId [$key = $value]. Kích hoạt tái đánh giá...")
        evaluateSituation()
    }

    override suspend fun onEvent(event: EventLogEntity) {
        // Có thể tích hợp đánh giá thói quen hoặc hành động khẩn cấp trực tiếp tại đây ở các giai đoạn sau
    }

    override suspend fun buildSystemContext(): String {
        val sit = cachedSituation ?: evaluateSituation()
        return """
            <SYSTEM_CONTEXT>
            Báo cáo trạng thái vận hành của Quản gia thông minh:
            - Chế độ/Tâm trạng hoạt động (HouseMood): ${sit.currentMood}
            - Mức độ cảnh báo an ninh: Cấp độ ${sit.securityLevel} (0: Bình thường, 2: Nguy cơ xâm nhập)
            - Sự diện diện của chủ nhà: ${if (sit.ownerPresent) "Đang có mặt ở nhà" else "Đi vắng"}
            - Số lượng thiết bị thông minh Tuya đang hoạt động: ${sit.activeDevicesCount}
            - Tin nhắn chưa đọc cần hỗ trợ trực tiếp từ đa kênh: ${sit.pendingChatsCount}
            - Nhật ký khái quát: ${sit.summary}
            </SYSTEM_CONTEXT>
        """.trimIndent()
    }

    // Helper trích xuất an toàn giá trị từ JSON World State
    private fun getAttr(entity: WorldStateEntity, key: String): String? {
        return try {
            val json = JSONObject(entity.attributesJson)
            if (json.has(key)) json.optString(key, "").ifBlank { null } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 6
    }
}