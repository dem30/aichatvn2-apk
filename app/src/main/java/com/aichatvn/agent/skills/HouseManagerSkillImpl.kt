package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
// ✅ ĐÃ SỬA: Sửa từ "exec1ution" thành "execution" để Hilt/Kapt nhận diện chính xác
import com.aichatvn.agent.core.execution.IntentExecutor 
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.*
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.WorldStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// ✅ MỚI (Kiến trúc Nhóm Kịch bản): Định nghĩa cấu trúc nhóm kịch bản tự chọn của gia chủ —
// mỗi nhóm có 1 ngòi nổ (triggerSource) riêng và danh sách bước thi hành liên hoàn, cho phép
// Quản gia phản ứng công bằng với MỌI nguồn sự kiện, không chỉ hardcode camera/tuya.
data class WorkflowGroup(
    val id: String,
    val label: String,
    val triggerSource: String,      // Ngòi nổ, ví dụ: "camera.cam_01.state=suspicious" hoặc "tuya.sensor_door.state=true"
    val enabled: Boolean = true,
    val steps: List<AlertActionConfig> = emptyList()
)

fun workflowGroupsFromJson(json: String): List<WorkflowGroup> {
    return try {
        val arr = JSONArray(json.ifBlank { "[]" })
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val stepsList = (0 until stepsArr.length()).map { j ->
                val sObj = stepsArr.getJSONObject(j)
                val paramsObj = sObj.optJSONObject("params") ?: JSONObject()
                val paramsMap = mutableMapOf<String, String>()
                paramsObj.keys().forEach { k -> paramsMap[k] = paramsObj.optString(k) }
                AlertActionConfig(
                    pluginId = sObj.optString("pluginId"),
                    action = sObj.optString("action"),
                    params = paramsMap
                )
            }
            WorkflowGroup(
                id = obj.optString("id"),
                label = obj.optString("label"),
                triggerSource = obj.optString("triggerSource"),
                enabled = obj.optBoolean("enabled", true),
                steps = stepsList
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Mã hóa danh sách nhóm kịch bản thành chuỗi JSON — chiều ghi tương ứng với workflowGroupsFromJson.
 */
fun workflowGroupsToJson(groups: List<WorkflowGroup>): String {
    return try {
        val arr = JSONArray()
        groups.forEach { group ->
            val obj = JSONObject().apply {
                put("id", group.id)
                put("label", group.label)
                put("triggerSource", group.triggerSource)
                put("enabled", group.enabled)

                val stepsArr = JSONArray()
                group.steps.forEach { step ->
                    val sObj = JSONObject().apply {
                        put("pluginId", step.pluginId)
                        put("action", step.action)

                        val paramsObj = JSONObject()
                        step.params.forEach { (k, v) -> paramsObj.put(k, v) }
                        put("params", paramsObj)
                    }
                    stepsArr.put(sObj)
                }
                put("steps", stepsArr)
            }
            arr.put(obj)
        }
        arr.toString()
    } catch (e: Exception) {
        "[]"
    }
}

@Singleton
class HouseManagerSkillImpl @Inject constructor(
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill,
    private val intentExecutorProvider: Provider<IntentExecutor>,
    logger: Logger
) : BaseSkill("house_manager", "Quản gia điều hành thông minh", logger), HouseManagerSkill {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true),
        routable = true,
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "evaluate",
                description = "Yêu cầu Quản gia phân tích trạng thái sống hiện tại của căn nhà",
                parameters = emptyList()
            ),
            PluginAction(
                name = "set_away_mode",
                description = "Bật hoặc tắt chế độ vắng nhà dài ngày (Vacation mode)",
                parameters = listOf(
                    PluginParameter("enabled", "boolean", "true để bật, false để tắt vắng nhà", true, "boolean")
                )
            ),
            PluginAction(
                name = "set_policy",
                description = "Bật hoặc tắt một chính sách an toàn của căn nhà",
                parameters = listOf(
                    PluginParameter("policyId", "string", "Mã chính sách (silent_night / vacation_safety)", true, "string"),
                    PluginParameter("enabled", "boolean", "true để kích hoạt chính sách", true, "boolean")
                )
            ),
            PluginAction(
                name = "mine_habits",
                description = "Kích hoạt Quản gia phân tích hành vi để đề xuất thói quen mới",
                parameters = emptyList()
            ),
            // ✅ MỚI: Đăng ký hành động dành riêng cho Planner Tự Do (No-Code Planner Builder) —
            // cho phép chủ nhà tự chèn bước "trì hoãn" / "kiểm duyệt điều kiện" vào giữa kịch bản
            // bằng UI (AlertActionFormSheet) mà không cần code cứng thứ tự bước như trước.
            PluginAction(
                name = "delay",
                description = "Trì hoãn tiến trình kịch bản (Planner Delay Step)",
                parameters = listOf(
                    PluginParameter("delayMs", "number", "Thời gian trì hoãn (milli-giây), ví dụ: 30000", true, "interval")
                )
            ),
            PluginAction(
                // ✅ ĐÃ SỬA: Thay vì bắt chủ nhà gõ tay chuỗi precondition thô (dễ gõ sai), tách
                // thành các tham số riêng với semanticType đặc thù để AlertActionFormSheet tự
                // render Dropdown chọn nguồn/thiết bị/camera/thớt chat/thuộc tính/trạng thái —
                // không cần gõ tay bất kỳ từ khóa hệ thống nào.
                name = "check_precondition",
                description = "Kiểm duyệt điều kiện thực tế (Planner Precondition Step)",
                parameters = listOf(
                    PluginParameter("source", "string", "Nguồn dữ liệu kiểm tra", true, "precondition_source"),
                    PluginParameter("device", "string", "Chọn thiết bị Tuya", false, "device"),
                    PluginParameter("camera", "string", "Chọn camera", false, "camera"),
                    // ✅ MỚI (Sửa lỗi logic): Thêm tham số chọn thớt chat khách hàng đa kênh —
                    // nếu không có tham số này, nhánh "chat" không thể biết đang kiểm tra thớt của ai.
                    PluginParameter("chatSession", "string", "Chọn thớt chat khách hàng", false, "chatSession"),
                    PluginParameter("attribute", "string", "Chọn thuộc tính kiểm tra", true, "precondition_attribute"),
                    PluginParameter("expected", "string", "Chọn trạng thái mong muốn", true, "precondition_expected")
                )
            )
        )
    )

    private val mutex = Mutex()
    private var cachedSituation: HouseSituation? = null

    private val plannerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activePlansMap = ConcurrentHashMap<String, PlanStatus>()
    private val planLogsMap = ConcurrentHashMap<String, MutableList<String>>()

    companion object {
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    override suspend fun initialize() {
        evaluateSituation()
        // 🧠 Khởi động bộ lắng nghe phản ứng Bản sao số tự động dưới nền
        startWorldStateReactiveObserver()
    }

    override suspend fun shutdown() {}

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "evaluate" -> {
                val sit = evaluateSituation()
                PluginResult.Success(mapOf("situation" to sit, "message" to "✅ Quy nạp trạng thái thành công."))
            }
            "set_away_mode" -> {
                val enabled = params["enabled"] as? Boolean ?: return PluginResult.Failure("Thiếu tham số enabled")
                WorldStateHelper.setAttribute(database.worldStateDao(), "system", "house", "away_mode", enabled.toString())
                val sit = evaluateSituation()
                PluginResult.Success(mapOf("situation" to sit, "message" to "✅ Đã chuyển đổi Chế độ vắng nhà: $enabled."))
            }
            "set_policy" -> {
                val policyId = params["policyId"] as? String ?: return PluginResult.Failure("Thiếu policyId")
                val enabled = params["enabled"] as? Boolean ?: return PluginResult.Failure("Thiếu enabled")
                WorldStateHelper.setAttribute(database.worldStateDao(), "system", "policy", policyId, enabled.toString())
                PluginResult.Success(mapOf("message" to "✅ Đã cập nhật chính sách '$policyId' sang: $enabled"))
            }
            "mine_habits" -> {
                mineUserHabits()
                PluginResult.Success(mapOf("message" to "✅ Quản gia đã phân tích và khai thác thói quen xong."))
            }
            // ✅ MỚI: Hiện thực hóa 2 hành động riêng của Planner Tự Do — cho phép mỗi bước
            // "trì hoãn" / "kiểm duyệt điều kiện" mà chủ nhà tự thêm qua UI được gọi như một
            // plugin action bình thường, thay vì bị bó buộc trong đúng 5 bước hardcode cũ.
            "delay" -> {
                val delayMs = (params["delayMs"] as? String)?.toLongOrNull()
                    ?: (params["delayMs"] as? Number)?.toLong()
                    ?: 0L
                delay(delayMs)
                PluginResult.Success(mapOf("message" to "Đã trì hoãn $delayMs ms"))
            }
            "check_precondition" -> {
                // ✅ ĐỒNG BỘ: Hỗ trợ cả kịch bản gõ tay cũ (params["precondition"]) lẫn bộ chọn
                // tham số tách rời mới từ UI (source/device/camera/attribute/expected) — nhờ vậy
                // các kịch bản mặc định cũ và kịch bản người dùng mới tạo qua picker đều chạy được.
                val precondition = params["precondition"] as? String
                val condition = if (precondition != null) {
                    WorldStateHelper.parseCondition(precondition)
                } else {
                    val source = params["source"] as? String ?: ""
                    val attribute = params["attribute"] as? String ?: "state"
                    val expected = params["expected"] as? String ?: ""
                    // ✅ ĐÃ SỬA: trước đây nhánh "chat" bị rơi vào else -> lấy "camera" (luôn rỗng),
                    // khiến điều kiện chat luôn coi như không có (bypass âm thầm). Giờ tách rõ when.
                    val sourceId = when (source) {
                        "tuya" -> params["device"] as? String ?: ""
                        "camera" -> params["camera"] as? String ?: ""
                        "chat" -> params["chatSession"] as? String ?: ""
                        else -> ""
                    }
                    if (source.isNotEmpty() && sourceId.isNotEmpty()) {
                        WorldStateHelper.WorldStateCondition(source, sourceId, attribute, expected)
                    } else null
                }

                if (condition != null) {
                    // Cùng logic quy đổi tên thân thiện Tuya -> ID thật đã dùng trong executePlan,
                    // để hành động check_precondition độc lập vẫn tra cứu đúng world_state.
                    val resolvedSourceId = if (condition.source == "tuya") {
                        database.tuyaDeviceDao().getDeviceByName(condition.sourceId)?.id
                            ?: database.tuyaDeviceDao().getDeviceById(condition.sourceId)?.id
                            ?: condition.sourceId
                    } else {
                        condition.sourceId
                    }
                    val actualValue = WorldStateHelper.getAttribute(database.worldStateDao(), condition.source, resolvedSourceId, condition.attrKey)
                    if (actualValue != condition.expected) {
                        PluginResult.Failure("Điều kiện không thỏa mãn: Cần ${condition.attrKey}=${condition.expected}, Thực tế: $actualValue")
                    } else {
                        PluginResult.Success(mapOf("message" to "Điều kiện thỏa mãn"))
                    }
                } else {
                    PluginResult.Success(mapOf("message" to "Không có điều kiện"))
                }
            }
            else -> PluginResult.Failure("Hành động không hỗ trợ: $action")
        }
    }

    override suspend fun evaluateSituation(): HouseSituation = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val states = database.worldStateDao().getAllStatesFlow().first()
                val cameraStates = states.filter { it.source == "camera" }
                val isSuspicious = cameraStates.any { getAttr(it, "state") == "suspicious" }

                var suspiciousCount = 0
                var guestCount = 0
                cameraStates.forEach { state ->
                    if (getAttr(state, "state") == "suspicious") suspiciousCount++
                    val objectsJson = getAttr(state, "objects")
                    if (!objectsJson.isNullOrBlank()) {
                        try {
                            val arr = JSONArray(objectsJson)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optString(i, "").lowercase()
                                if (obj == "person" || obj == "nguoi") guestCount++
                            }
                        } catch (_: Exception) {}
                    }
                }

                val tuyaStates = states.filter { it.source == "tuya" }
                val activeDevicesCount = tuyaStates.count { getAttr(it, "state") == "true" }

                val chatStates = states.filter { it.source == "chat" }
                var totalUnreadChats = 0
                chatStates.forEach { state ->
                    val unread = try { JSONObject(state.attributesJson).optInt("unread_count", 0) } catch (_: Exception) { 0 }
                    totalUnreadChats += unread
                }

                val isAway = WorldStateHelper.getAttribute(database.worldStateDao(), "system", "house", "away_mode") == "true"
                val ownerPresent = !isAway

                val computedMood = when {
                    isAway -> HouseMood.VACATION
                    isSuspicious -> HouseMood.ALERT
                    totalUnreadChats > 3 -> HouseMood.BUSY
                    isNightTime() && activeDevicesCount == 0 -> HouseMood.SLEEPING
                    isNightTime() -> HouseMood.NIGHT
                    else -> HouseMood.NORMAL
                }

                val securityLevel = if (isSuspicious) 2 else 0
                val summary = if (isAway) {
                    "Gia đình đang đi vắng. Quản gia đã siết chặt chính sách an ninh phòng chống quá tải điện."
                } else {
                    "Nhà đang ở chế độ $computedMood. An toàn mức $securityLevel."
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
                HouseSituation(0, true, 0, 0, 0, 0, HouseMood.NORMAL, "Fallback")
            }
        }
    }

    // 🧠 BỘ QUAN SÁT PHẢN ỨNG BẢN SAO SỐ (WORLD STATE REACTIVE OBSERVER)
    // ⚠️ CẦN KIỂM TRA TRƯỚC KHI BUILD: onWorldStateChanged bên dưới là hàm override của
    // interface HouseManagerSkill/BaseSkill — hãy xác nhận trong AgentKernel/BaseSkill xem
    // hàm này đã được framework tự gọi mỗi khi world_state đổi hay chưa. Nếu đã có cơ chế đó,
    // bộ observer thủ công này sẽ khiến onWorldStateChanged bị gọi 2 LẦN cho cùng 1 sự kiện
    // (1 lần do framework, 1 lần do vòng collect thủ công dưới đây) -> kích hoạt còi/đèn/email
    // báo động 2 lần liên tiếp cho cùng một lần camera nghi vấn.
    private fun startWorldStateReactiveObserver() {
        plannerScope.launch(Dispatchers.IO) {
            // Bộ nhớ đệm lưu trạng thái cũ trên RAM để so sánh tìm ra trường thay đổi (Delta Change)
            val lastStateMap = ConcurrentHashMap<String, String>()

            database.worldStateDao().getAllStatesFlow().collect { currentStates ->
                currentStates.forEach { entity ->
                    try {
                        val json = JSONObject(entity.attributesJson)
                        json.keys().forEach { key ->
                            val value = json.optString(key, "")
                            val stateKey = "${entity.source}:${entity.sourceId}:$key"
                            val lastValue = lastStateMap[stateKey]

                            if (lastValue != value) {
                                lastStateMap[stateKey] = value
                                // Chỉ phát tín hiệu khi là biến động thực tế phát sinh sau khi mở app
                                if (lastValue != null) {
                                    // Chạy bất đồng bộ để không làm nghẽn dòng collect của SQLite Flow
                                    launch(Dispatchers.Default) {
                                        onWorldStateChanged(entity.source, entity.sourceId, key, value)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.e("HouseManager", "Lỗi phân tích Bản sao số: ${e.message}")
                    }
                }
            }
        }
    }

    override suspend fun onWorldStateChanged(source: String, sourceId: String, key: String, value: String) {
        // 1. Luôn quy nạp lại tình huống sống để đổi màu Mood giao diện và cập nhật system:brain
        evaluateSituation()

        // 2. 🧠 BỘ ĐIỀU PHỐI ĐA NHÓM KỊCH BẢN TỰ DO (Dynamic Group-Based Orchestrator)
        // ⚠️ THAY ĐỔI KIẾN TRÚC: nhánh camera/tuya hardcode cũ (kèm fix targetCamId Rủi ro 2)
        // đã được GỠ BỎ hoàn toàn. Từ giờ, việc kích hoạt tự động CHỈ đến từ các Nhóm kịch bản
        // (HOUSE_MANAGER_WORKFLOWS) — HOUSE_MANAGER_PROTECT_ACTIONS/light/siren/camera_ids cũ
        // không còn tự kích hoạt được nữa, chỉ còn dùng cho nút Panic thủ công
        // (triggerProtectHouseSequence gọi trực tiếp từ UI).
        val workflowsJson = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_WORKFLOWS, "[]").trim()
        val groups = workflowGroupsFromJson(workflowsJson)

        groups.forEach { group ->
            if (!group.enabled) return@forEach

            val condition = WorldStateHelper.parseCondition(group.triggerSource)
            if (condition != null) {
                // Kiểm duyệt xem sự kiện Bản sao số hiện tại có khớp "ngòi nổ" của nhóm kịch bản này không
                val isTriggerMatched = source == condition.source &&
                        (condition.sourceId == "*" || sourceId.trim().equals(condition.sourceId.trim(), ignoreCase = true)) &&
                        key == condition.attrKey &&
                        value == condition.expected

                if (isTriggerMatched) {
                    logger.i("HouseManager", "🔥 Nhóm kịch bản '${group.label}' được kích hoạt từ ngòi nổ: $source.$sourceId.$key=$value")

                    // Quy đổi động các tham số bước chạy (ví dụ: __CAMERA_ID__ -> ID camera/nguồn báo động thật)
                    val compiledSteps = group.steps.map { cfg ->
                        var delayVal = 0L
                        var preconditionVal: String? = null

                        if (cfg.pluginId == "house_manager" && cfg.action == "delay") {
                            delayVal = cfg.params["delayMs"]?.toLongOrNull() ?: 0L
                        }
                        if (cfg.pluginId == "house_manager" && cfg.action == "check_precondition") {
                            val rawPrecondition = cfg.params["precondition"]
                            preconditionVal = rawPrecondition ?: run {
                                val pSource = cfg.params["source"] ?: ""
                                val pAttr = cfg.params["attribute"] ?: "state"
                                val pExpected = cfg.params["expected"] ?: ""
                                // ✅ Giữ nguyên fix "chat" -> chatSession đã áp dụng trước đó (không để
                                // rơi về nhánh "camera" rỗng như bản gốc tài liệu đề xuất lần này).
                                val pSourceId = when (pSource) {
                                    "tuya" -> cfg.params["device"] ?: ""
                                    "camera" -> cfg.params["camera"] ?: ""
                                    "chat" -> cfg.params["chatSession"] ?: ""
                                    else -> ""
                                }
                                if (pSource.isNotEmpty() && pSourceId.isNotEmpty()) "$pSource.$pSourceId.$pAttr=$pExpected" else null
                            }
                        }

                        val updatedParams = cfg.params.mapValues { (_, v) ->
                            val resolvedValue = if (v == "__CAMERA_ID__") sourceId else v
                            when {
                                resolvedValue.equals("true", ignoreCase = true) -> true
                                resolvedValue.equals("false", ignoreCase = true) -> false
                                resolvedValue.toLongOrNull() != null -> resolvedValue.toLong()
                                else -> resolvedValue
                            }
                        }

                        ActionStep(
                            pluginId = cfg.pluginId,
                            action = cfg.action,
                            params = updatedParams,
                            delayMs = delayVal,
                            precondition = preconditionVal
                        )
                    }

                    executePlan(group.label, compiledSteps)
                }
            }
        }
    }

    override suspend fun onEvent(event: EventLogEntity) {}

    override suspend fun buildSystemContext(): String {
        val sit = cachedSituation ?: evaluateSituation()
        return """
            <SYSTEM_CONTEXT>
            Báo cáo trạng thái vận hành của Quản gia thông minh:
            - Chế độ hoạt động (HouseMood): ${sit.currentMood}
            - Mức độ cảnh báo an ninh: Cấp độ ${sit.securityLevel} (0: Bình thường, 2: Nguy cơ xâm nhập)
            - Số lượng thiết bị thông minh Tuya đang hoạt động: ${sit.activeDevicesCount}
            - Tin nhắn chưa đọc cần hỗ trợ từ đa kênh: ${sit.pendingChatsCount}
            </SYSTEM_CONTEXT>
        """.trimIndent()
    }



    
    
// Thay thế hàm sendDefaultCameraAlerts trong HouseManagerSkillImpl.kt:

override suspend fun sendDefaultCameraAlerts(
    camera: CameraConfigEntity,
    aiComment: String,
    imageBytes: ByteArray?,
    activeAlertId: String,
    shouldMerge: Boolean
): Boolean = withContext(Dispatchers.IO) {
    var emailSent = false
    
    // 1. Chỉ thực hiện gửi thông báo (Email và Push Notification) nếu camera bật tính năng này
    if (camera.enableNotification == 1) {
        if (!shouldMerge && camera.customeremail.isNotEmpty()) {
            try {
                val emailSubject = "${configProvider.getString(AppConfigDefaults.EMAIL_SUBJECT_PREFIX, "🚨 CẢNH BÁO AN NINH")} - ${camera.customername}"
                emailSkill.sendEmail(
                    to = camera.customeremail,
                    subject = emailSubject,
                    body = buildAlertEmailBody(camera, aiComment),
                    imageBytes = imageBytes
                )
                emailSent = true
            } catch (e: Exception) {
                logger.e("HouseManager", "Gửi email mặc định thất bại: ${e.message}")
            }
        }

        try {
            notificationSkill.sendNotification(
                title = "Cảnh Báo Camera ${camera.customername}",
                message = aiComment.take(100),
                notificationId = NotificationSkill.notificationIdForAlert(activeAlertId),
                deepLinkRoute = "alert_history?cameraId=${camera.id.trim()}"
            )
        } catch (e: Exception) {
            logger.e("HouseManager", "Gửi thông báo đẩy mặc định thất bại: ${e.message}")
        }
    }

    // 🔴 ĐÃ LOẠI BỎ: Đoạn code tự động kích hoạt triggerProtectHouseSequence() chạy ngầm cũ [12].
    // Từ bây giờ, việc kích hoạt kịch bản răn đe vật lý khi có trộm sẽ được quản lý tập trung 
    // và duy nhất bởi bộ điều phối đa nhóm kịch bản (onWorldStateChanged) dựa trên Bản sao số [12, 13].

    return@withContext emailSent
}





    
    override suspend fun handleChatEventDecision(
        platform: String,
        senderId: String,
        message: String,
        timestamp: Long
    ): ChatDecision = withContext(Dispatchers.IO) {
        val unifiedUsername = "${platform}_$senderId"
        val normMsg = message.lowercase()

        val intent = when {
            normMsg.contains("gia bao nhieu") || normMsg.contains("bao gia") || normMsg.contains("mua") || normMsg.contains("ban") -> "ask_price"
            normMsg.contains("loi") || normMsg.contains("hong") || normMsg.contains("bao hanh") || normMsg.contains("ho tro") -> "support"
            else -> "general"
        }

        val isUrgent = normMsg.contains("gap") || normMsg.contains("ngay") || normMsg.contains("khan") ||
                       normMsg.contains("trom") || normMsg.contains("chay") || normMsg.contains("nguy hiem")
        val urgency = if (isUrgent) "high" else "normal"

        val existingState = database.worldStateDao().getState("chat", unifiedUsername)
        val prevUnread = existingState?.let {
            try { JSONObject(it.attributesJson).optInt("unread_count", 0) } catch (_: Exception) { 0 }
        } ?: 0
        val newUnread = prevUnread + 1

        val eventPayload = JSONObject().apply {
            put("platform", platform)
            put("senderId", senderId)
            put("session_status", "waiting_agent")
            put("customer_intent", intent)
            put("urgency", urgency)
            put("unread_count", newUnread)
            put("last_message", message.take(80))
        }.toString()

        database.worldStateDao().upsertState(
            WorldStateEntity(
                id = "chat:$unifiedUsername",
                source = "chat",
                sourceId = unifiedUsername,
                attributesJson = eventPayload,
                updatedAt = System.currentTimeMillis()
            )
        )

        database.eventLogDao().insertLog(
            EventLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                source = platform,
                sourceId = unifiedUsername,
                eventType = "chat_session_state_change",
                value = eventPayload,
                summary = "Tin nhắn từ [$platform] $senderId (Ý định: $intent, Khẩn cấp: $urgency, Chưa đọc: $newUnread)"
            )
        )

        val currentSituation = cachedSituation ?: evaluateSituation()
        if (urgency == "high" && (currentSituation.currentMood == HouseMood.SLEEPING || currentSituation.currentMood == HouseMood.NIGHT)) {
            try {
                notificationSkill.sendNotification(
                    title = "🚨 KHÁCH HÀNG LIÊN HỆ KHẨN CẤP",
                    message = "Tin nhắn khẩn cấp từ [$platform] $senderId: \"${message.take(60)}\""
                )
            } catch (e: Exception) {
                logger.e("HouseManager", "Lỗi gửi thông báo khẩn cấp tin nhắn: ${e.message}")
            }
        }

        val customerSetting = database.cameraDao().getCustomerSetting(senderId)
        val shouldAutoRespond = customerSetting?.smartMode != 0

        ChatDecision(
            shouldAutoRespond = shouldAutoRespond,
            intent = intent,
            urgency = urgency,
            unreadCount = newUnread,
            summary = "Quản gia đã duyệt thớt chat $unifiedUsername."
        )
    }

    override suspend fun checkPolicy(
        pluginId: String,
        action: String,
        params: Map<String, Any>
    ): PolicyResult = withContext(Dispatchers.IO) {
        val currentSituation = cachedSituation ?: evaluateSituation()
        
        val isSilentNightEnabled = WorldStateHelper.getAttribute(database.worldStateDao(), "system", "policy", "silent_night") ?: "true"
        if (isSilentNightEnabled == "true" && currentSituation.currentMood == HouseMood.SLEEPING && pluginId == "smart_switch" && action == "set") {
            val state = params["state"] as? Boolean ?: false
            val deviceKey = params["device"]?.toString() ?: ""
            val isRestricted = deviceKey.contains("tivi", ignoreCase = true) || 
                               deviceKey.contains("loa", ignoreCase = true) || 
                               deviceKey.contains("còi", ignoreCase = true)
                               
            if (state && isRestricted) {
                return@withContext PolicyResult.Blocked(
                    "❌ Bị chặn bởi Chính sách Quản gia: Cả nhà đang ngủ, chặn kích hoạt các thiết bị gây tiếng ồn '$deviceKey'."
                )
            }
        }

        val isVacationSafetyEnabled = WorldStateHelper.getAttribute(database.worldStateDao(), "system", "policy", "vacation_safety") ?: "true"
        if (isVacationSafetyEnabled == "true" && currentSituation.currentMood == HouseMood.VACATION && pluginId == "smart_switch" && action == "set") {
            val state = params["state"] as? Boolean ?: false
            val deviceKey = params["device"]?.toString() ?: ""
            val isHeavyLoad = deviceKey.contains("bơm", ignoreCase = true) || 
                               deviceKey.contains("bình nóng", ignoreCase = true)
                               
            if (state && isHeavyLoad) {
                return@withContext PolicyResult.Blocked(
                    "❌ Bị chặn bởi Chính sách Quản gia: Đang ở chế độ vắng nhà, chặn bật thiết bị phụ tải lớn '$deviceKey' để phòng chống cháy nổ."
                )
            }
        }

        return@withContext PolicyResult.Allowed
    }

    override fun getActivePlans(): List<PlanStatus> {
        return activePlansMap.values.toList()
    }

    override suspend fun executePlan(goalName: String, steps: List<ActionStep>) {
        val planId = "PLAN-${UUID.randomUUID().toString().take(8).uppercase()}"
        val logsList = Collections.synchronizedList(mutableListOf<String>())
        planLogsMap[planId] = logsList

        plannerScope.launch {
            logger.i("HousePlanner", "🎬 Bắt đầu vận hành kế hoạch [$planId]: '$goalName' (${steps.size} bước)")
            logsList.add("Khởi động kế hoạch lúc ${DATETIME_FORMATTER.format(Instant.now())}")

            activePlansMap[planId] = PlanStatus(
                planId = planId,
                goalName = goalName,
                currentStepIndex = 0,
                totalSteps = steps.size,
                status = "RUNNING",
                logs = logsList
            )

            for ((index, step) in steps.withIndex()) {
                activePlansMap[planId] = activePlansMap[planId]!!.copy(currentStepIndex = index)

                if (step.delayMs > 0) {
                    val delaySec = step.delayMs / 1000
                    logger.d("HousePlanner", "[$planId] Chờ trì hoãn $delaySec giây...")
                    logsList.add("Bước ${index + 1}: Chờ hoãn $delaySec giây")
                    delay(step.delayMs)
                }

                var proceed = true
                if (!step.precondition.isNullOrBlank()) {
                    val condition = WorldStateHelper.parseCondition(step.precondition)
                    if (condition != null) {
                        // ✅ ĐÃ SỬA: SmartSwitchSkill lưu Bản sao số (world_state) dưới ID thật
                        // trên đám mây Tuya (vd "tuya_siren_01"), trong khi precondition của
                        // Planner được viết bằng tên thân thiện chủ nhà đặt (vd "còi báo động").
                        // Nếu không quy đổi, getAttribute() sẽ luôn trả về null -> bước bị bỏ qua oan.
                        val resolvedSourceId = if (condition.source == "tuya") {
                            database.tuyaDeviceDao().getDeviceByName(condition.sourceId)?.id
                                ?: database.tuyaDeviceDao().getDeviceById(condition.sourceId)?.id
                                ?: condition.sourceId
                        } else {
                            condition.sourceId
                        }

                        val actualValue = WorldStateHelper.getAttribute(
                            database.worldStateDao(),
                            condition.source,
                            resolvedSourceId, // Dùng ID thật đã quy đổi thay vì tên thân thiện gốc
                            condition.attrKey
                        )
                        proceed = actualValue == condition.expected
                        if (!proceed) {
                            logger.w("HousePlanner", "[$planId] ⚠️ Bước ${index + 1} bị BỎ QUA do sai điều kiện thực tế (Yêu cầu: ${condition.attrKey}=${condition.expected}, Thực tế: $actualValue)")
                            logsList.add("Bước ${index + 1}: ⚠️ Bỏ qua — Ràng buộc thế giới thực không khớp (${condition.attrKey}=${condition.expected})")
                        }
                    }
                }

                if (!proceed) continue

                logger.i("HousePlanner", "[$planId] ⚡ Bước ${index + 1}/${steps.size}: Kích hoạt ${step.pluginId}.${step.action}")
                logsList.add("Bước ${index + 1}: Kích hoạt thực thi ${step.pluginId}.${step.action}")

                try {
                    val outcome = intentExecutorProvider.get().executePluginAction(
                        step.pluginId,
                        step.action,
                        step.params
                    )

                    when (outcome) {
                        is PluginResult.Success -> {
                            logger.i("HousePlanner", "[$planId] ✅ Bước ${index + 1} thành công.")
                            logsList.add("Bước ${index + 1}: ✅ Thành công.")
                        }
                        is PluginResult.Failure -> {
                            logger.e("HousePlanner", "[$planId] ❌ Bước ${index + 1} thất bại: ${outcome.error}")
                            logsList.add("Bước ${index + 1}: ❌ Thất bại: ${outcome.error}")
                        }
                        else -> {
                            logsList.add("Bước ${index + 1}: Chờ bổ sung thông tin.")
                        }
                    }
                } catch (e: Exception) {
                    logger.e("HousePlanner", "[$planId] ❌ Lỗi hệ thống tại bước ${index + 1}: ${e.message}")
                    logsList.add("Bước ${index + 1}: ❌ Lỗi hệ thống: ${e.message}")
                }
            }

            logger.i("HousePlanner", "🏁 Kế hoạch [$planId] đã hoàn thành chu kỳ chạy.")
            logsList.add("Kế hoạch hoàn tất lúc ${DATETIME_FORMATTER.format(Instant.now())}")
            activePlansMap[planId] = activePlansMap[planId]!!.copy(
                currentStepIndex = steps.size,
                status = "COMPLETED"
            )

            delay(10 * 60 * 1000L)
            activePlansMap.remove(planId)
            planLogsMap.remove(planId)
        }
    }

    // 🧠 GIAI ĐOẠN 4 (NÂNG CẤP PLANNER TỰ DO):
    // Biên dịch danh sách AlertActionConfig tự chọn của chủ nhà (được thêm qua
    // AlertActionFormSheet trên UI) thành các bước ActionStep chạy trên Planner.
    // Nếu chủ nhà chưa cấu hình gì (JSON rỗng "[]") thì fallback về đúng kịch bản
    // 5 bước mặc định cũ để bảo đảm khả năng tương thích ngược.
    override suspend fun triggerProtectHouseSequence(cameraId: String) {
        val actionsJson = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_ACTIONS, "[]").trim()

        
      
      
      
      
      // Đọc tên thiết bị cấu hình dự phòng — đưa giá trị mặc định về rỗng để đồng bộ housekeeping sạch
        val lightDevice = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_LIGHT, "").trim()
        val sirenDevice = configProvider.getString(AppConfigDefaults.HOUSE_MANAGER_PROTECT_SIREN, "").trim()
        
        val alertActions = if (actionsJson == "[]" || actionsJson.isBlank()) {
            buildDefaultProtectActions(lightDevice, sirenDevice, cameraId)
        } else {
            alertActionsFromJson(actionsJson)
        }

        // Ánh xạ 1-1 danh sách AlertActionConfig tự do thành ActionStep chạy trên Planner —
        // trích riêng delayMs/precondition từ 2 hành động đặc biệt "delay"/"check_precondition"
        // của chính Quản gia, còn lại chạy như plugin action bình thường.
        val steps = alertActions.map { cfg ->
            var delayVal = 0L
            var preconditionVal: String? = null

            if (cfg.pluginId == "house_manager" && cfg.action == "delay") {
                delayVal = cfg.params["delayMs"]?.toLongOrNull() ?: 0L
            }
            if (cfg.pluginId == "house_manager" && cfg.action == "check_precondition") {
                // ✅ ĐÃ SỬA: Ưu tiên chuỗi precondition gõ tay cũ nếu có (tương thích ngược với
                // kịch bản 5 bước mặc định), nếu không thì tự biên dịch từ 5 tham số người dùng
                // chọn trên Picker trực quan (source/device|camera/attribute/expected) thành
                // đúng định dạng "source.sourceId.attr=expected" mà executePlan() hiểu được.
                val rawPrecondition = cfg.params["precondition"]
                preconditionVal = rawPrecondition ?: run {
                    val source = cfg.params["source"] ?: ""
                    val attr = cfg.params["attribute"] ?: "state"
                    val expected = cfg.params["expected"] ?: ""
                    // ✅ ĐÃ SỬA: đọc đúng thớt chat được chọn thay vì luôn rơi vào "camera" (rỗng).
                    val sourceId = when (source) {
                        "tuya" -> cfg.params["device"] ?: ""
                        "camera" -> cfg.params["camera"] ?: ""
                        "chat" -> cfg.params["chatSession"] ?: ""
                        else -> ""
                    }
                    if (source.isNotEmpty() && sourceId.isNotEmpty()) "$source.$sourceId.$attr=$expected" else null
                }
            }

            // Quy đổi động placeholder (vd: __CAMERA_ID__ -> mã camera thật của sự kiện hiện tại)
            // và ép kiểu về Boolean/Long đúng như IntentExecutor mong đợi trong ActionStep.params.
            val updatedParams: Map<String, Any> = cfg.params.mapValues { (_, value) ->
                val resolvedValue = if (value == "__CAMERA_ID__") cameraId else value
                when {
                    resolvedValue.equals("true", ignoreCase = true) -> true
                    resolvedValue.equals("false", ignoreCase = true) -> false
                    resolvedValue.toLongOrNull() != null -> resolvedValue.toLong()
                    else -> resolvedValue
                }
            }

            ActionStep(
                pluginId = cfg.pluginId,
                action = cfg.action,
                params = updatedParams,
                delayMs = delayVal,
                precondition = preconditionVal
            )
        }

        val cameraName = database.cameraDao().getCameraById(cameraId)?.customername ?: cameraId
        executePlan("Kịch bản liên hoàn bảo vệ an ninh cho camera $cameraName", steps)
    }

    // Kịch bản 5 bước mặc định cũ, được dựng lại thành AlertActionConfig để dùng làm fallback
    // an toàn khi chủ nhà chưa tự cấu hình kịch bản tự do nào trên UI.
    
  
  
  // Bộ sinh kịch bản mặc định tự động lọc bỏ các thiết bị chưa được cấu hình (tránh gửi lệnh rỗng)
    private fun buildDefaultProtectActions(light: String, siren: String, cameraId: String): List<AlertActionConfig> {
        val list = mutableListOf<AlertActionConfig>()
        
        // 1. Chỉ bật đèn dọa trộm nếu chủ nhà đã cấu hình đèn thật
        if (light.isNotBlank()) {
            list.add(AlertActionConfig("smart_switch", "set", mapOf("device" to light, "state" to "true")))
        }
        
        // 2. Thông báo khẩn cấp mặc định của Quản gia
        list.add(
            AlertActionConfig(
                pluginId = "notification", 
                action = "send", 
                params = mapOf("title" to "🚨 PHÁT HIỆN NGHI VẤN AN NINH", "message" to "Quản gia đã phát hiện bất thường và đang kích hoạt các kịch bản an toàn.")
            )
        )
        
        // 3. Tiến trình đếm ngược Planner quét camera lại
        list.add(AlertActionConfig("house_manager", "delay", mapOf("delayMs" to "30000")))
        list.add(AlertActionConfig("camera", "scan", mapOf("cameraId" to cameraId, "force" to "true")))
        list.add(AlertActionConfig("house_manager", "delay", mapOf("delayMs" to "5000")))
        list.add(AlertActionConfig("house_manager", "check_precondition", mapOf("precondition" to "camera.$cameraId.state=suspicious")))
        
        // 4. Chỉ bật/tắt còi báo động nếu chủ nhà đã cấu hình còi thật
        if (siren.isNotBlank()) {
            list.add(AlertActionConfig("smart_switch", "set", mapOf("device" to siren, "state" to "true")))
            list.add(AlertActionConfig("house_manager", "delay", mapOf("delayMs" to "60000")))
            list.add(AlertActionConfig("smart_switch", "set", mapOf("device" to siren, "state" to "false")))
        }
        
        return list
    }



  

    override suspend fun mineUserHabits() = withContext(Dispatchers.IO) {
        logger.i("HouseManager", "🔄 Quản gia bắt đầu tiến trình tự học thói quen người dùng từ nhật ký 7 ngày...")
        
        val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 
        val now = System.currentTimeMillis()
        val logs = database.eventLogDao().getLogsInTimeframe(since, now)
        if (logs.isEmpty()) {
            logger.d("HouseManager", "Nhật ký trống, dừng tiến trình học.")
            return@withContext
        }

        val calendar = Calendar.getInstance()
        data class GroupKey(val source: String, val sourceId: String, val eventType: String, val value: String, val hour: Int)

        val grouped = logs.groupBy { log ->
            calendar.timeInMillis = log.timestamp
            GroupKey(log.source, log.sourceId, log.eventType, log.value, calendar.get(Calendar.HOUR_OF_DAY))
        }

        val qaDao = database.qaDao()
        val existingPatterns = getTrainingSkillPatternCache()

        grouped.forEach { (key, entries) ->
            if (key.source != "tuya") return@forEach

            val distinctDays = entries.map { entry ->
                calendar.timeInMillis = entry.timestamp
                calendar.get(Calendar.DAY_OF_YEAR)
            }.distinct().size

            if (distinctDays >= 4) {
                val question = "pattern:${key.source}:${key.sourceId}:${key.eventType}:${key.value}:${key.hour}h"
                val actionLabel = if (key.value.lowercase() == "true") "bật" else "tắt"
                val answer = "Bạn thường $actionLabel thiết bị ${key.sourceId} vào khoảng ${key.hour}h hằng ngày ($distinctDays/7 ngày gần nhất). Hệ thống đề xuất đặt lịch tự động cho thói quen này."

                val existingCategory = existingPatterns[question + "_category"]

                when (existingCategory) {
                    null -> {
                        val qa = QAEntity(
                            id = UUID.randomUUID().toString(),
                            question = question,
                            answer = answer,
                            type = "pattern",
                            category = "pending_pattern",
                            createdBy = "default_user",
                            createdAt = System.currentTimeMillis(),
                            timestamp = System.currentTimeMillis()
                        )
                        qaDao.insertQA(qa)
                        logger.i("HouseManager", "🔁 Phát hiện thói quen mới chờ duyệt: $answer")

                        val eventPayload = JSONObject().apply {
                            put("pattern_id", qa.id)
                            put("device_id", key.sourceId)
                            put("device_name", key.sourceId)
                            put("action", key.eventType)
                            put("value", key.value)
                            put("hour", key.hour)
                            put("confidence", "$distinctDays/7 ngày")
                            put("recommendation_text", answer)
                            put("status", "pending")
                        }.toString()

                        database.eventLogDao().insertLog(
                            EventLogEntity(
                                id = UUID.randomUUID().toString(),
                                timestamp = now,
                                source = "system",
                                sourceId = "brain",
                                eventType = "pattern_discovered",
                                value = eventPayload,
                                summary = "Hệ thống tự học thói quen mới: $answer"
                            )
                        )
                    }
                    "pending_pattern" -> {
                        if (existingPatterns[question] != answer) {
                            val qa = QAEntity(
                                id = existingPatterns[question + "_id"] ?: UUID.randomUUID().toString(),
                                question = question,
                                answer = answer,
                                type = "pattern",
                                category = "pending_pattern",
                                createdBy = "default_user",
                                createdAt = System.currentTimeMillis(),
                                timestamp = System.currentTimeMillis()
                            )
                            qaDao.insertQA(qa)
                        }
                    }
                    else -> {}
                }
            }
        }

        val pendingCount = qaDao.getAllQAs("default_user").count { it.category == "pending_pattern" }
        WorldStateHelper.setAttribute(database.worldStateDao(), "system", "brain", "pending_patterns_count", pendingCount.toString())
        WorldStateHelper.setAttribute(database.worldStateDao(), "system", "brain", "last_learning_run", System.currentTimeMillis().toString())
        logger.i("HouseManager", "✅ Tiến trình tự học thói quen hoàn tất. Ghi nhận $pendingCount đề xuất thói quen chờ duyệt.")
    }

    private suspend fun getTrainingSkillPatternCache(): Map<String, String> {
        return try {
            database.qaDao().getAllQAs("default_user")
                .filter { it.type == "pattern" }
                .flatMap {
                    listOf(
                        it.question to it.answer,
                        it.question + "_id" to it.id,
                        it.question + "_category" to it.category
                    )
                }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun buildAlertEmailBody(camera: CameraConfigEntity, analysis: String): String {
        return """
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2 style="color: #dc2626;">🚨 QUẢN GIA AI: CẢNH BÁO AN NINH KHẨN CẤP</h2>
                <p>Hệ thống phát hiện biến động bất thường tại vị trí camera: <strong>${camera.customername}</strong></p>
                <div style="background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107;">
                    <strong>🤖 Đánh giá từ Quản gia:</strong><br>
                    $analysis
                </div>
            </body>
            </html>
        """.trimIndent()
    }

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