package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
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
// ✅ MỚI (Giai đoạn 3 - Planner): các import bất đồng bộ cần cho plannerScope
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
// ✅ MỚI (Giai đoạn 3 - Planner): thiếu trong bản gốc, cần cho activePlansMap/planLogsMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class HouseManagerSkillImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    // ✅ ĐÃ SỬA LỖI 1: Bổ sung đầy đủ 3 Dependency thiếu hụt vào Constructor
    private val configProvider: AppConfigProvider,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill,
    private val intentExecutorProvider: Provider<IntentExecutor>,
    logger: Logger
) : BaseSkill("house_manager", "Quản gia điều hành thông minh", logger), HouseManagerSkill {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(),
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "evaluate",
                description = "Chạy quy nạp để phân tích trạng thái sống của ngôi nhà",
                parameters = emptyList()
            ),
            PluginAction(
                name = "get_context",
                description = "Đọc ngữ cảnh tổng hợp của Quản gia phục vụ RAG",
                parameters = emptyList()
            )
        )
    )

    private val mutex = Mutex()
    private var cachedSituation: HouseSituation? = null

    // ✅ MỚI (Giai đoạn 3 - Planner): Scope bất đồng bộ riêng để chạy kịch bản dưới nền,
    // không phụ thuộc lifecycle của coroutine gọi nó (vd: webhook camera).
    private val plannerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Quản lý trạng thái các kế hoạch đang chạy / log để hiển thị lên UI Screen sau này
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
            "get_context" -> PluginResult.Success(mapOf("context" to buildSystemContext()))
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

                val ownerPresent = true

                val computedMood = when {
                    isSuspicious -> HouseMood.ALERT
                    totalUnreadChats > 3 -> HouseMood.BUSY
                    isNightTime() && activeDevicesCount == 0 -> HouseMood.SLEEPING
                    isNightTime() -> HouseMood.NIGHT
                    else -> HouseMood.NORMAL
                }

                val securityLevel = if (isSuspicious) 2 else 0
                val summary = "Nhà an toàn mức $securityLevel, tâm trạng $computedMood."

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

        // 🚨 KÍCH HOẠT PHẢN ỨNG LIÊN HOÀN CỦA PLANNER:
        // Nếu phát hiện biến cố an ninh thực tế và KHÔNG phải sự kiện đang bị gộp trùng (tránh kích hoạt lặp rơ-le).
        // ✅ ĐÃ SỬA: gọi trực tiếp (suspend), KHÔNG bọc thêm plannerScope.launch { } ở đây —
        // executePlan() bên dưới đã tự launch trên plannerScope và trả về ngay, bọc thêm launch
        // là dư một tầng coroutine không cần thiết.
        if (!shouldMerge && camera.id.trim() == "cam_01") { // Ví dụ áp dụng cho camera Sân Trước (cam_01)
            triggerProtectHouseSequence(camera.id.trim())
        }

        return@withContext emailSent
    }

    // ✅ ĐÃ SỬA LỖI 4: Hiện thực hóa đầy đủ method handleChatEventDecision tránh lỗi biên dịch lớp kế thừa
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

    // ─────────────────────────────────────────────────────────────────────────
    // 🧠 PLANNER ENGINE (Giai đoạn 3 — Vận hành chuỗi kịch bản liên hoàn nhiều bước)
    // ─────────────────────────────────────────────────────────────────────────
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
                // 1. Cập nhật tiến trình cho UI Screen
                activePlansMap[planId] = activePlansMap[planId]!!.copy(currentStepIndex = index)

                // 2. Chờ hoãn nếu bước này yêu cầu trì hoãn trước khi chạy
                if (step.delayMs > 0) {
                    val delaySec = step.delayMs / 1000
                    logger.d("HousePlanner", "[$planId] Chờ trì hoãn $delaySec giây...")
                    logsList.add("Bước ${index + 1}: Chờ hoãn $delaySec giây")
                    delay(step.delayMs)
                }

                // 3. Đánh giá điều kiện thế giới thực (Precondition check) trước khi chạy bước
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

                // 4. Kích hoạt thực thi hành động qua IntentExecutor
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

            // 5. Kết thúc kịch bản và lưu trạng thái hoàn thành
            logger.i("HousePlanner", "🏁 Kế hoạch [$planId] đã hoàn thành chu kỳ chạy.")
            logsList.add("Kế hoạch hoàn tất lúc ${DATETIME_FORMATTER.format(Instant.now())}")
            activePlansMap[planId] = activePlansMap[planId]!!.copy(
                currentStepIndex = steps.size,
                status = "COMPLETED"
            )

            // Dọn dẹp kế hoạch cũ khỏi bộ nhớ hiển thị sau 10 phút để tránh tốn RAM
            delay(10 * 60 * 1000L)
            activePlansMap.remove(planId)
            planLogsMap.remove(planId)
        }
    }

    // Kịch bản mẫu liên hoàn bảo vệ nhà thông minh 5 bước của Quản gia
    override suspend fun triggerProtectHouseSequence(cameraId: String) {
        val steps = listOf(
            // Bước 1: Bật ngay đèn sân trước của Tuya để dọa trộm
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                params = mapOf("device" to "đèn sân trước", "state" to true)
            ),
            // Bước 2: Gửi thông báo khẩn cho chủ nhà
            ActionStep(
                pluginId = "notification",
                action = "send",
                params = mapOf(
                    "title" to "🚨 PHÁT HIỆN NGHI VẤN SÂN TRƯỚC",
                    "message" to "Quản gia đã tự động bật đèn sân để răn đe. Đang theo dõi sát sao..."
                )
            ),
            // Bước 3: Đợi 30 giây để trộm tự rút lui
            ActionStep(
                pluginId = "camera",
                action = "scan",
                params = mapOf("cameraId" to cameraId, "force" to true),
                delayMs = 30000L // ⏳ Trì hoãn 30 giây trước khi quét lại
            ),
            // Bước 4: Kiểm tra lại thế giới thực. Nếu trộm vẫn cố tình ở lại (trạng thái camera vẫn suspicious)
            // thì hú còi báo động khẩn cấp
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                params = mapOf("device" to "còi báo động", "state" to true),
                delayMs = 5000L,
                precondition = "camera.$cameraId.state=suspicious" // 🔒 Chỉ hú còi nếu trộm chưa đi!
            ),
            // Bước 5: Đưa còi báo động về trạng thái an toàn sau 1 phút hú còi dọa
            ActionStep(
                pluginId = "smart_switch",
                action = "set",
                // ✅ ĐÃ SỬA LỖI CÚ PHÁP: bản gốc dùng "state" -> false (sai cú pháp, không compile được)
                params = mapOf("device" to "còi báo động", "state" to false),
                delayMs = 60000L, // ⏳ Tự động tắt còi sau 1 phút
                precondition = "tuya.còi báo động.state=true" // Chỉ tắt nếu còi thực sự đang hú
            )
        )

        executePlan("Kịch bản liên hoàn bảo vệ an ninh sân trước", steps)
    }

    // ✅ ĐÃ SỬA LỖI 2: Di dời hàm sinh body email sang HouseManagerSkillImpl thay vì gọi qua hàm private của CameraSkill
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