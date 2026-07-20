package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
// ✅ ĐÃ SỬA: Sửa từ "exec1ution" thành "execution" để Hilt/Kapt nhận diện chính xác
import com.aichatvn.agent.core.execution.IntentExecutor 
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.*
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.WorldStateHelper
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class HouseManagerSkillImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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

    override suspend fun onWorldStateChanged(source: String, sourceId: String, key: String, value: String) {
        evaluateSituation()
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

    override suspend fun sendDefaultCameraAlerts(
        camera: CameraConfigEntity,
        aiComment: String,
        imageBytes: ByteArray?,
        activeAlertId: String,
        shouldMerge: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        var emailSent = false
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

        if (!shouldMerge && camera.id.trim() == "cam_01") { 
            triggerProtectHouseSequence(camera.id.trim())
        }

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
                        val actualValue = WorldStateHelper.getAttribute(
                            database.worldStateDao(),
                            condition.source,
                            condition.sourceId,
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

    override suspend fun triggerProtectHouseSequence(cameraId: String) {
        val steps = listOf(
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                params = mapOf("device" to "đèn sân trước", "state" to true)
            ),
            ActionStep(
                pluginId = "notification",
                action = "send",
                params = mapOf(
                    "title" to "🚨 PHÁT HIỆN NGHI VẤN SÂN TRƯỚC",
                    "message" to "Quản gia đã tự động bật đèn sân để răn đe. Đang theo dõi sát sao..."
                )
            ),
            ActionStep(
                pluginId = "camera",
                action = "scan",
                params = mapOf("cameraId" to cameraId, "force" to true),
                delayMs = 30000L 
            ),
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                params = mapOf("device" to "còi báo động", "state" to true),
                delayMs = 5000L,
                precondition = "camera.$cameraId.state=suspicious" 
            ),
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                params = mapOf("device" to "còi báo động", "state" to false),
                delayMs = 60000L, 
                precondition = "tuya.còi báo động.state=true" 
            )
        )

        executePlan("Kịch bản liên hoàn bảo vệ an ninh sân trước", steps)
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

    private fun saveAlertImage(alertId: String, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "alert_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$alertId.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
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