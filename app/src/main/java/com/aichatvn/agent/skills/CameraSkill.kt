package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.camera.ImageHashTool
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotFetcher: SnapshotFetcher,
    private val imageHashTool: ImageHashTool,
    private val groqClient: GroqClientTool,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill,
    logger: Logger, 
    
) : BaseSkill("camera", "Quản lý camera", logger), Plugin {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "scan",
                description = "Quét camera để phát hiện thay đổi và phân tích AI",
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (để trống quét tất cả)", false)
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái chi tiết của 1 camera (online/offline, smart mode, ngưỡng học)",
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (id)", true)
                )
            ),
            PluginAction(
                name = "set_active",
                description = "Bật hoặc tắt theo dõi của 1 camera cụ thể. Dùng khi user nói: bật/tắt camera X.",
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (id)", true),
                    PluginParameter("active", "boolean", "true: bật theo dõi, false: tắt theo dõi", true)
                )
            ),
            PluginAction(
                name = "set_smart_mode",
                description = "Bật hoặc tắt chế độ AI phân tích ảnh cho camera. Chấp nhận cameraId hoặc customerId.",
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (id) — ưu tiên dùng cái này", false),
                    PluginParameter("customerId", "string", "Mã khách hàng (dùng nếu không có cameraId)", false),
                    PluginParameter("enabled", "boolean", "true: bật AI, false: tắt AI", true)
                )
            ),
            PluginAction(
                name = "configure",
                description = "Cập nhật cấu hình AI cho camera: prompt phân tích, từ khoá cảnh báo, từ khoá bình thường, URL ảnh chụp, vị trí. Dùng khi user nói: đặt từ khoá, cập nhật prompt, thay URL camera, đổi vị trí.",
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (id)", true),
                    PluginParameter("aiPrompt", "string", "Prompt AI mới cho camera này", false),
                    PluginParameter("aiPositiveKeywords", "string", "Từ khoá cảnh báo, cách nhau bằng dấu phẩy. Ví dụ: cảnh báo, khói, người lạ", false),
                    PluginParameter("aiNegativeKeywords", "string", "Từ khoá bình thường, cách nhau bằng dấu phẩy. Ví dụ: bình thường, không có gì", false),
                    PluginParameter("snapshotUrl", "string", "URL ảnh chụp mới", false),
                    PluginParameter("landInfo", "string", "Thông tin vị trí / ghi chú", false)
                )
            ),
            PluginAction(
                name = "list_cameras",
                description = "Liệt kê tất cả camera kèm id, tên, trạng thái online/offline. Dùng để tra cứu cameraId trước khi cấu hình hoặc điều khiển.",
                parameters = emptyList()
            )
        )
    }
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val cameraMutexMap = mutableMapOf<String, Mutex>()
    private fun getMutexForCamera(cameraId: String): Mutex =
        cameraMutexMap.getOrPut(cameraId) { Mutex() }
    
    // ==================== HỌC TẬP THÍCH NGHI ====================
    private data class CameraLearningState(
        var lastPhash: String = "",
        var lastDiff: Int = 0,
        val falseDeltas: MutableList<Int> = mutableListOf(),
        val falseDiffs: MutableList<Int> = mutableListOf(),
        val baselineWindow: MutableList<Int> = mutableListOf(),
        var realEvents: Int = 0,
        var deltaTrigger: Int = 10,
        var absDiffTrigger: Int = 18,
        var cooldownUntil: Long = 0L
    )
    
    private data class CircuitBreakerState(
        var offlineCount: Int = 0,
        var offlineSince: Long = 0L,
        var isOpen: Boolean = false,
        var halfOpenAttempted: Boolean = false
    )
    
    private data class PendingResetState(
        var diff: Int = 0,
        var timestamp: Long = 0L
    )
    
    private data class DailyEvent(
        val timestamp: Long,
        val comment: String,
        val imageBytes: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return timestamp == (other as DailyEvent).timestamp && comment == other.comment
        }
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + comment.hashCode()
            return result
        }
    }
    
    private val learningStates = mutableMapOf<String, CameraLearningState>()
    private val circuitBreakers = mutableMapOf<String, CircuitBreakerState>()
    private val pendingResets = mutableMapOf<String, PendingResetState>()
    private val dailyEvents = mutableMapOf<String, MutableList<DailyEvent>>()
    
    private val _diagnostics = MutableStateFlow<Map<String, Any>>(emptyMap())
    val diagnostics: StateFlow<Map<String, Any>> = _diagnostics.asStateFlow()
    
    companion object {
        private const val DEFAULT_AI_PROMPT = "Camera giám sát thửa đất. Hãy xem có người/xe? hoặc xây dựng không. Nếu có ghi: cảnh báo và mô tả. Ngược lại ghi: Bình thường và mô tả."
        private val DEFAULT_POSITIVE_KEYWORDS = listOf("cảnh báo")
        private val DEFAULT_NEGATIVE_KEYWORDS = listOf("bình thường")
        private const val COOLDOWN_DURATION_MS = 3 * 60 * 60 * 1000L
        private const val CIRCUIT_BREAKER_THRESHOLD = 3
        private const val CIRCUIT_BREAKER_RESET_MS = 30 * 60 * 1000L
        private const val DAILY_REPORT_HOUR = 20
        private const val MAX_DAILY_EVENTS_PER_CAMERA = 50

        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }
    
    // ==================== PLUGIN IMPLEMENTATION ====================
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        logger.d("CameraSkill", "execute: action=$action")
        
        return when (action) {
            "scan" -> handleScan(params)
            "list_cameras" -> handleListCameras()
            "status" -> handleStatus(params)
            "set_active" -> handleSetActive(params)
            "set_smart_mode" -> handleSetSmartMode(params)
            "configure" -> handleConfigure(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }
    
    // ── Configure: cập nhật aiPrompt, keywords, snapshotUrl, landInfo ──────────
    private suspend fun handleConfigure(params: Map<String, Any>): PluginResult {
        val cameraId = params["cameraId"] as? String
            ?: return PluginResult.Failure("Thiếu cameraId. Dùng action list_cameras để xem danh sách camera.")
        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return PluginResult.Failure("Không tìm thấy camera id=$cameraId")

        val updated = cam.copy(
            aiPrompt = (params["aiPrompt"] as? String)?.trim() ?: cam.aiPrompt,
            aiPositiveKeywords = (params["aiPositiveKeywords"] as? String)?.trim() ?: cam.aiPositiveKeywords,
            aiNegativeKeywords = (params["aiNegativeKeywords"] as? String)?.trim() ?: cam.aiNegativeKeywords,
            snapshoturl = (params["snapshotUrl"] as? String)?.trim() ?: cam.snapshoturl,
            landinfo = (params["landInfo"] as? String)?.trim() ?: cam.landinfo
        )
        database.cameraDao().updateCamera(updated)

        val changed = buildList {
            if (updated.aiPrompt != cam.aiPrompt) add("prompt AI")
            if (updated.aiPositiveKeywords != cam.aiPositiveKeywords) add("từ khoá cảnh báo")
            if (updated.aiNegativeKeywords != cam.aiNegativeKeywords) add("từ khoá bình thường")
            if (updated.snapshoturl != cam.snapshoturl) add("URL ảnh chụp")
            if (updated.landinfo != cam.landinfo) add("vị trí")
        }
        val summary = if (changed.isEmpty()) "Không có thay đổi" else "Đã cập nhật: ${changed.joinToString(", ")}"
        logger.i("CameraSkill", "configure OK cameraId=$cameraId changed=$changed")
        return PluginResult.Success(mapOf("message" to "✅ Camera \"${cam.customername}\": $summary"))
    }

    // ── List cameras: trả về id + tên để LLM map khi user nói tên camera ────────
    private suspend fun handleListCameras(): PluginResult {
        val cameras = database.cameraDao().getAllCameras()
        if (cameras.isEmpty()) return PluginResult.Success(mapOf("message" to "Chưa có camera nào"))
        val list = cameras.map { c ->
            mapOf(
                "id" to c.id,
                "name" to c.customername,
                "url" to c.snapshoturl,
                "active" to (c.manualOff == 0),
                "online" to (c.isOnline == 1)
            )
        }
        val summary = cameras.joinToString("\n") { "• ${it.customername} (id: ${it.id})" }
        return PluginResult.Success(mapOf("cameras" to list, "message" to "Danh sách camera:\n$summary"))
    }

    private suspend fun handleScan(params: Map<String, Any>): PluginResult {
        val cameraId = params["cameraId"] as? String
        val result = scanCamera(cameraId, false)

        return when (result) {
            is PluginResult.Success -> {
                val data = result.data as? Map<*, *>
                val processed = data?.get("processed") as? Int ?: 0
                val results = data?.get("results") as? List<*> ?: emptyList<Any>()
                val skippedCb = data?.get("skippedCircuitBreaker") as? Int ?: 0
                val skippedIn = data?.get("skippedInactive") as? Int ?: 0
                val warning = data?.get("warning") as? String

                if (warning != null) return PluginResult.Success(mapOf("message" to warning))

                // Tóm tắt kết quả từng camera để AI trả lời có nghĩa cho người dùng
                val summary = buildString {
                    append("📷 Đã quét $processed camera")
                    if (skippedCb > 0) append(" ($skippedCb camera bị bỏ qua do lỗi liên tiếp)")
                    if (skippedIn > 0) append(" ($skippedIn camera không hoạt động)")
                    append(".\n")

                    for (r in results) {
                        val cam = r as? Map<*, *> ?: continue
                        val id = cam["cameraId"] as? String ?: continue
                        val success = cam["success"] as? Boolean ?: false
                        if (!success) {
                            val err = cam["error"] as? String ?: "lỗi không xác định"
                            append("• Camera $id: ❌ $err\n")
                            continue
                        }
                        val hasChange = cam["hasChange"] as? Boolean ?: false
                        val isSuspicious = cam["isSuspicious"] as? Boolean ?: false
                        val aiComment = cam["aiComment"] as? String
                        when {
                            isSuspicious -> append("• Camera $id: 🚨 CẢNH BÁO — ${aiComment ?: "phát hiện bất thường"} (email đã gửi)\n")
                            hasChange && aiComment != null && aiComment != "No analysis" ->
                                append("• Camera $id: 🔄 Có biến động — $aiComment\n")
                            hasChange -> append("• Camera $id: 🔄 Có biến động nhỏ, AI đánh giá bình thường\n")
                            else -> append("• Camera $id: ✅ Bình thường\n")
                        }
                    }
                }.trimEnd()

                PluginResult.Success(mapOf("message" to summary))
            }
            is PluginResult.Failure -> PluginResult.Failure(result.error)
            else -> PluginResult.Failure("Quét thất bại")
        }
    }
    
    private suspend fun handleStatus(params: Map<String, Any>): PluginResult {
        val cameraId = params["cameraId"] as? String
            ?: return PluginResult.Failure("Thiếu cameraId. Dùng action list_cameras để xem danh sách.")

        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")

        val setting = database.cameraDao().getCustomerSetting(cam.customerId)
        val diag = learningStates[cameraId]
        val cb = circuitBreakers[cameraId]

        val text = buildString {
            append("📷 Camera: ${cam.customername} (id: ${cam.id})\n")
            append("• Trạng thái kết nối: ${if (cam.isOnline == 1) "🟢 Online" else "🔴 Offline"}\n")
            append("• Theo dõi: ${if (cam.manualOff == 0) "Bật" else "Tắt (thủ công)"}\n")
            append("• Khách hàng: ${cam.customerId} — ${if (setting?.isActive == 1) "Active" else "Inactive"}\n")
            append("• AI Smart Mode: ${if (setting?.smartMode == 1) "Bật" else "Tắt"}\n")
            if (cam.landinfo != null) append("• Vị trí: ${cam.landinfo}\n")
            if (diag != null) {
                append("• Ngưỡng học (delta/diff): ${diag.deltaTrigger}/${diag.absDiffTrigger}\n")
                append("• Sự kiện thật: ${diag.realEvents} | Mẫu học: ${diag.falseDeltas.size}\n")
                val inCooldown = diag.cooldownUntil > System.currentTimeMillis()
                if (inCooldown) {
                    val remaining = (diag.cooldownUntil - System.currentTimeMillis()) / 60000
                    append("• Cooldown: còn $remaining phút\n")
                }
            }
            if (cb?.isOpen == true) append("• ⚠️ Circuit Breaker OPEN (offline ${cb.offlineCount} lần liên tiếp)\n")
        }.trimEnd()

        return PluginResult.Success(mapOf("message" to text))
    }
    
    private suspend fun handleSetActive(params: Map<String, Any>): PluginResult {
        val cameraId = params["cameraId"] as? String
            ?: return PluginResult.Failure("Bạn muốn bật/tắt camera nào? Dùng list_cameras để xem danh sách.")

        val active = params["active"] as? Boolean
            ?: (params["active"] as? String)?.lowercase()?.let { it == "true" }
            ?: return PluginResult.Failure("Bạn muốn bật hay tắt camera $cameraId?")

        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")

        // manualOff=0 là đang theo dõi (active), manualOff=1 là đã tắt thủ công.
        // Tách biệt với CustomerSettingEntity.isActive — cái đó là kill switch ở cấp khách hàng,
        // không phải toggle từng camera riêng lẻ.
        val newManualOff = if (active) 0 else 1
        if (cam.manualOff == newManualOff) {
            val state = if (active) "đang bật" else "đang tắt"
            return PluginResult.Success(mapOf("message" to "📷 Camera \"${cam.customername}\" đã $state rồi, không cần thay đổi."))
        }

        database.cameraDao().updateCamera(cam.copy(manualOff = newManualOff))
        logger.i("CameraSkill", "set_active cameraId=$cameraId active=$active (manualOff=$newManualOff)")

        val msg = if (active)
            "✅ Đã bật theo dõi camera \"${cam.customername}\" — sẽ được quét ở lần scan tiếp theo."
        else
            "✅ Đã tắt theo dõi camera \"${cam.customername}\" — sẽ không quét cho đến khi bật lại."
        return PluginResult.Success(mapOf("message" to msg))
    }
    
    private suspend fun handleSetSmartMode(params: Map<String, Any>): PluginResult {
        val enabled = params["enabled"] as? Boolean
            ?: (params["enabled"] as? String)?.lowercase()?.let { it == "true" }
            ?: return PluginResult.Failure("Bạn muốn bật hay tắt AI smart mode?")

        // Ưu tiên cameraId (user thường nói "camera X"), lookup ngược ra customerId.
        // Fallback customerId nếu AI gửi thẳng.
        val resolvedCustomerId: String
        val cameraName: String

        val cameraId = params["cameraId"] as? String
        if (cameraId != null) {
            val cam = database.cameraDao().getCameraById(cameraId)
                ?: return PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")
            resolvedCustomerId = cam.customerId
            cameraName = cam.customername
        } else {
            val customerId = params["customerId"] as? String
                ?: return PluginResult.Failure("Thiếu cameraId hoặc customerId. Dùng list_cameras để xem danh sách.")
            // Xác nhận customerId tồn tại
            val setting = database.cameraDao().getCustomerSetting(customerId)
            if (setting == null) {
                // Thử tìm như cameraId phòng trường hợp LLM nhầm field
                val cam = database.cameraDao().getCameraById(customerId)
                if (cam != null) {
                    resolvedCustomerId = cam.customerId
                    cameraName = cam.customername
                } else {
                    return PluginResult.Failure("Không tìm thấy customerId=$customerId và cũng không phải cameraId hợp lệ.")
                }
            } else {
                resolvedCustomerId = customerId
                val cameras = database.cameraDao().getCamerasByCustomer(customerId)
                cameraName = cameras.firstOrNull()?.customername ?: customerId
            }
        }

        val result = setSmartMode(resolvedCustomerId, enabled)
        return when (result) {
            is PluginResult.Success -> {
                val state = if (enabled) "bật" else "tắt"
                PluginResult.Success(mapOf(
                    "message" to "✅ Đã $state AI Smart Mode cho camera \"$cameraName\" — AI sẽ ${if (enabled) "phân tích ảnh và gửi cảnh báo khi phát hiện bất thường." else "không được gọi, chỉ so sánh ảnh bằng pHash."}"
                ))
            }
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Không thể thực hiện")
        }
    }
    
    // ==================== CORE SKILL METHODS ====================
    
    private val pruneMutex = Mutex()
    
    override suspend fun initialize() {
        val cameras = database.cameraDao().getActiveCameras()
        cameras.forEach { camera ->
            learningStates[camera.id] = CameraLearningState()
            circuitBreakers[camera.id] = CircuitBreakerState()
        }
        updateDiagnostics()
        cleanupOldAlerts()
        pruneOrphanedCameraState()
        scheduleDailyReport()
    }
    
    private suspend fun pruneOrphanedCameraState() {
        try {
            pruneMutex.withLock {
                val allIds = database.cameraDao().getAllCamerasFlow().first().map { it.id }.toSet()
                val orphans = (learningStates.keys + circuitBreakers.keys + pendingResets.keys + dailyEvents.keys) - allIds
                orphans.forEach { id ->
                    learningStates.remove(id)
                    circuitBreakers.remove(id)
                    pendingResets.remove(id)
                    dailyEvents.remove(id)
                }
                if (orphans.isNotEmpty()) {
                    logger.i("CameraSkill", "🧹 Pruned ${orphans.size} orphaned camera state entries")
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "pruneOrphanedCameraState error: ${e.message}", e)
        }
    }
    
    private fun cleanupOldAlerts() {
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                val dir = File(context.filesDir, "alert_images")
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) file.delete()
                    }
                }
                database.alertDao().deleteAlertsOlderThan(cutoff)
            } catch (e: Exception) {
                logger.e("CameraSkill", "cleanupOldAlerts error: ${e.message}", e)
            }
        }
    }
    
    private fun scheduleDailyReport() {
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, DAILY_REPORT_HOUR)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                
                var nextRun = calendar.timeInMillis
                if (nextRun <= now) {
                    nextRun += 24 * 60 * 60 * 1000L
                }
                
                val delay = nextRun - now
                kotlinx.coroutines.delay(delay)
                sendDailyReports()
                pruneOrphanedCameraState()
            }
        }
    }
    
    private suspend fun sendDailyReports() {
        try {
            val customers = database.cameraDao().getActiveCameras()
                .groupBy { it.customerId }
            
            for ((customerId, cameras) in customers) {
                val events = mutableListOf<Map<String, Any>>()
                for (camera in cameras) {
                    val dailyEventsList = dailyEvents[camera.id] ?: continue
                    if (dailyEventsList.isNotEmpty()) {
                        events.add(mapOf(
                            "cameraId" to camera.id,
                            "cameraName" to camera.customername,
                            "landInfo" to (camera.landinfo ?: "N/A"),
                            "events" to dailyEventsList.map { event ->
                                mapOf(
                                    "time" to TIME_FORMATTER.format(Instant.ofEpochMilli(event.timestamp)),
                                    "comment" to event.comment
                                )
                            },
                            "hasImage" to dailyEventsList.any { it.imageBytes != null }
                        ))
                        dailyEvents[camera.id]?.clear()
                    }
                }
                
                if (events.isNotEmpty()) {
                    val customerSetting = database.cameraDao().getCustomerSetting(customerId)
                    val customerEmail = cameras.firstOrNull()?.customeremail
                    
                    if (customerEmail != null && customerSetting?.isActive == 1) {
                        sendDailySummaryEmail(customerEmail, customerId, events)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "Error sending daily reports: ${e.message}", e)
        }
    }
    
    private suspend fun sendDailySummaryEmail(to: String, customerId: String, events: List<Map<String, Any>>) {
        val subject = "📋 BÁO CÁO GIÁM SÁT ĐẤT ĐAI ĐỊNH KỲ - $customerId - ${DATE_FORMATTER.format(Instant.now())}"
        
        val body = buildString {
            append("<html><body style='font-family: Arial, sans-serif;'>")
            append("<h2>BÁO CÁO GIÁM SÁT ĐẤT ĐAI</h2>")
            append("<p>Xin chào Quý khách hàng <b>$customerId</b>,</p>")
            append("<p>Hệ thống gửi báo cáo hôm nay (${events.size} camera có sự kiện):</p>")
            
            for (event in events) {
                append("<div style='margin-bottom: 20px; padding: 10px; border: 1px solid #ccc; border-radius: 5px;'>")
                append("<h3>📷 Camera: ${event["cameraName"]} - ${event["landInfo"]}</h3>")
                
                @Suppress("UNCHECKED_CAST")
                val eventList = event["events"] as? List<Map<String, String>> ?: emptyList()
                if (eventList.isNotEmpty()) {
                    append("<div style='background: #fff3cd; padding: 10px; border-left: 4px solid #ffc107;'>")
                    append("<strong>⚠️ NHẬT KÝ BIẾN CỐ:</strong><br>")
                    for (ev in eventList) {
                        append("• <b>[${ev["time"]}]</b>: ${ev["comment"]}<br>")
                    }
                    append("</div>")
                }
                append("</div>")
            }
            
            append("<p style='font-size: 12px; color: #666;'>Hệ thống camera giám sát AI tự động — AIChatVN2</p>")
            append("</body></html>")
        }
        
        emailSkill.sendEmail(to, subject, body, null)
    }
    
    override suspend fun shutdown() {}
    
    // ==================== CIRCUIT BREAKER ====================
    
    private fun isCircuitBreakerOpen(cameraId: String): Boolean {
        val cb = circuitBreakers[cameraId] ?: return false
        if (!cb.isOpen) return false

        val elapsed = System.currentTimeMillis() - cb.offlineSince

        if (elapsed > CIRCUIT_BREAKER_RESET_MS) {
            if (!cb.halfOpenAttempted) {
                cb.halfOpenAttempted = true
                logger.i("CameraSkill", "🔁 Circuit Breaker HALF-OPEN for camera $cameraId - thử lại...")
                return false
            } else {
                cb.offlineSince = System.currentTimeMillis()
                cb.halfOpenAttempted = false
                logger.w("CameraSkill", "🔌 Circuit Breaker reset timer for camera $cameraId")
                return true
            }
        }
        return true
    }
    
    private fun recordOffline(cameraId: String) {
        val cb = circuitBreakers.getOrPut(cameraId) { CircuitBreakerState() }
        cb.offlineCount++
        cb.halfOpenAttempted = false
        if (cb.offlineCount >= CIRCUIT_BREAKER_THRESHOLD) {
            cb.isOpen = true
            cb.offlineSince = System.currentTimeMillis()
            logger.w("CameraSkill", "🔌 Circuit Breaker OPEN for camera $cameraId (offline ${cb.offlineCount} times)")
        }
    }
    
    private fun recordOnline(cameraId: String) {
        circuitBreakers[cameraId] = CircuitBreakerState()
    }
    
    private fun checkPendingReset(cameraId: String, currentDiff: Int, absDiffTrigger: Int): Boolean {
        val pending = pendingResets[cameraId]
        if (pending == null) return false
        
        val diffChange = kotlin.math.abs(currentDiff - pending.diff)
        val timeSince = System.currentTimeMillis() - pending.timestamp
        
        if (timeSince > 60 * 60 * 1000L && diffChange <= 5 && currentDiff >= absDiffTrigger / 2) {
            logger.w("CameraSkill", "🔄 Pending Reset triggered for camera $cameraId - resetting learning state")
            pendingResets.remove(cameraId)
            return true
        }
        
        if (currentDiff < absDiffTrigger / 2) {
            pendingResets.remove(cameraId)
        }
        
        return false
    }
    
    // ==================== SCAN METHODS ====================
    
    suspend fun scanCamera(cameraId: String?, isDailyReport: Boolean): PluginResult {
        return try {
            val cameras = if (cameraId != null) {
                listOfNotNull(database.cameraDao().getCameraById(cameraId))
            } else {
                database.cameraDao().getActiveCameras()
            }

            if (cameras.isEmpty()) {
                logger.w("CameraSkill", "scanCamera: không có camera nào trong DB" +
                    if (cameraId != null) " (cameraId=$cameraId không tồn tại)" else " (chưa thêm camera)")
                return PluginResult.Success(
                    mapOf(
                        "processed" to 0,
                        "results" to emptyList<Any>(),
                        "warning" to "Chưa có camera nào được cấu hình"
                    )
                )
            }

            val results = mutableListOf<Map<String, Any>>()
            var skippedCircuitBreaker = 0
            var skippedInactive = 0

            for (camera in cameras) {
                if (!isDailyReport && isCircuitBreakerOpen(camera.id)) {
                    logger.w("CameraSkill", "⏭️ Circuit Breaker OPEN - skipping camera ${camera.id}")
                    skippedCircuitBreaker++
                    continue
                }

                val customerSetting = database.cameraDao().getCustomerSetting(camera.customerId)
                if (customerSetting?.isActive != 1) {
                    logger.d("CameraSkill", "⏭️ Camera ${camera.id} skipped: " +
                        if (customerSetting == null) "không có customerSetting (chưa tạo?)"
                        else "isActive=${customerSetting.isActive} (không phải 1)")
                    skippedInactive++
                    continue
                }

                val result = if (isDailyReport) {
                    scanForDailyReport(camera)
                } else {
                    scanWithLearning(camera, customerSetting.smartMode == 1)
                }
                results.add(result)
            }

            if (results.isEmpty() && cameras.isNotEmpty()) {
                logger.w("CameraSkill", "scanCamera: có ${cameras.size} camera nhưng 0 được xử lý " +
                    "(skippedCircuitBreaker=$skippedCircuitBreaker, skippedInactive=$skippedInactive)")
            }

            PluginResult.Success(
                mapOf(
                    "processed" to results.size,
                    "results" to results,
                    "skippedCircuitBreaker" to skippedCircuitBreaker,
                    "skippedInactive" to skippedInactive
                )
            )

        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Camera scan failed")
        }
    }
    
    suspend fun processImage(cameraId: String, imageBytes: ByteArray): PluginResult {
        return try {
            val camera = database.cameraDao().getCameraById(cameraId)
            if (camera == null) {
                logger.w("CameraSkill", "processImage: camera not found: $cameraId")
                return PluginResult.Failure("Camera not found")
            }
            
            val customerSetting = database.cameraDao().getCustomerSetting(camera.customerId)
            if (customerSetting?.isActive != 1) {
                logger.d("CameraSkill", "processImage: camera ${camera.id} is inactive, skipping")
                return PluginResult.Success(mapOf("skipped" to true))
            }
            
            val result = processImageWithLearning(camera, imageBytes, customerSetting?.smartMode == 1)
            PluginResult.Success(result)
            
        } catch (e: Exception) {
            logger.e("CameraSkill", "processImage error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Process image failed")
        }
    }
    
    private suspend fun processImageWithLearning(
        camera: CameraConfigEntity,
        imageBytes: ByteArray,
        isSmartMode: Boolean
    ): Map<String, Any> {
        return getMutexForCamera(camera.id).withLock {
            val state = learningStates.getOrPut(camera.id) { CameraLearningState() }
            val now = System.currentTimeMillis()
            
            try {
                recordOnline(camera.id)
                
                val currentPhash = imageHashTool.calculatePhash(imageBytes)
                val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
                
                var currentDiff = 0
                if (state.lastPhash.isNotEmpty()) {
                    currentDiff = imageHashTool.calculateHammingDistance(state.lastPhash, currentPhash)
                }
                
                val delta = kotlin.math.abs(currentDiff - state.lastDiff)
                val deltaTrigger = state.deltaTrigger
                val absDiffTrigger = state.absDiffTrigger
                val driftTrigger = 12
                
                val baselineDiff = if (state.baselineWindow.isNotEmpty()) {
                    state.baselineWindow.average().toInt()
                } else {
                    currentDiff
                }
                val drift = kotlin.math.abs(currentDiff - baselineDiff)
                
                val isSuddenChange = delta >= deltaTrigger || 
                                     currentDiff >= absDiffTrigger || 
                                     drift >= driftTrigger
                
                val isMature = state.baselineWindow.size >= 3
                
                val shouldReset = checkPendingReset(camera.id, currentDiff, absDiffTrigger)
                if (shouldReset) {
                    learningStates[camera.id] = CameraLearningState(
                        lastPhash = currentPhash,
                        lastDiff = currentDiff
                    )
                    return@withLock mapOf(
                        "cameraId" to camera.id,
                        "success" to true,
                        "hasChange" to false,
                        "message" to "Learning reset due to stable scene change"
                    )
                }
                
                val shouldCallAi = isSuddenChange && isSmartMode && now >= state.cooldownUntil
                
                var aiComment: String? = null
                var isSuspicious = false
                
                if (shouldCallAi) {
                    val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else DEFAULT_AI_PROMPT
                    val aiResult: String = try {
                        withTimeout(20_000L) {
                            groqClient.analyzeImage(optimizedBytes, prompt)
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.w("CameraSkill", "⏱️ Groq timeout (20s) camera=${camera.id}, bỏ qua phân tích AI lần này")
                        "Không thể phân tích (AI timeout)"
                    }
                    aiComment = aiResult
                    
                    val positiveKeywords = if (camera.aiPositiveKeywords.isNotEmpty()) {
                        camera.aiPositiveKeywords.split(",").map { it.trim().lowercase() }
                    } else {
                        DEFAULT_POSITIVE_KEYWORDS
                    }
                    val negativeKeywords = if (camera.aiNegativeKeywords.isNotEmpty()) {
                        camera.aiNegativeKeywords.split(",").map { it.trim().lowercase() }
                    } else {
                        DEFAULT_NEGATIVE_KEYWORDS
                    }
                    
                    val textClean = aiComment.lowercase()
                    val hasPositive = positiveKeywords.any { textClean.contains(it) }
                    val hasNegative = negativeKeywords.any { textClean.contains(it) }
                    isSuspicious = hasPositive && !hasNegative
                    
                    if (isSuspicious) {
                        state.realEvents++
                        state.cooldownUntil = now + COOLDOWN_DURATION_MS
                        
                        val cameraDailyEvents = dailyEvents.getOrPut(camera.id) { mutableListOf() }
                        cameraDailyEvents.add(DailyEvent(now, aiComment, optimizedBytes))
                        if (cameraDailyEvents.size > MAX_DAILY_EVENTS_PER_CAMERA) {
                            cameraDailyEvents.removeAt(0)
                        }
                        
                        val customerEmail = camera.customeremail
                        var emailSent = false
                        if (customerEmail.isNotEmpty()) {
                            emailSkill.sendEmail(
                                to = customerEmail,
                                subject = "🚨 CẢNH BÁO AN NINH KHẨN CẤP!",
                                body = buildAlertEmailBody(camera, aiComment),
                                imageBytes = optimizedBytes
                            )
                            emailSent = true
                        }
                        
                        notificationSkill.sendNotification(
                            title = "Cảnh Báo Camera ${camera.customername}",
                            message = aiComment.take(100)
                        )
                        
                        saveAlertToHistory(
                            camera = camera,
                            aiComment = aiComment,
                            imageBytes = optimizedBytes,
                            diff = currentDiff,
                            deltaTrigger = deltaTrigger,
                            absDiffTrigger = absDiffTrigger,
                            emailSent = emailSent
                        )
                        
                        logger.i("CameraSkill", "🚨 ALERT detected for camera ${camera.id}: $aiComment")
                        
                    } else {
                        if (isMature && isSuddenChange) {
                            pendingResets[camera.id] = PendingResetState(currentDiff, now)
                            logger.i("CameraSkill", "⚠️ Pending reset for camera ${camera.id} - monitoring next cycle")
                        }
                    }
                }
                
                if (!shouldCallAi || !isSuspicious) {
                    state.baselineWindow.add(currentDiff)
                    if (state.baselineWindow.size > 20) {
                        state.baselineWindow.removeAt(0)
                    }
                    
                    if (isSuddenChange && !isSuspicious) {
                        state.falseDeltas.add(delta)
                        state.falseDiffs.add(currentDiff)
                        if (state.falseDeltas.size > 100) {
                            state.falseDeltas.removeAt(0)
                            state.falseDiffs.removeAt(0)
                        }
                    }
                    
                    if (state.falseDeltas.size >= 3) {
                        val recentDeltas = state.falseDeltas.takeLast(30).sorted()
                        val idx = (recentDeltas.size * 0.9).toInt().coerceIn(0, recentDeltas.size - 1)
                        state.deltaTrigger = (recentDeltas[idx] + 2).coerceAtMost(25)
                        
                        val recentDiffs = state.falseDiffs.takeLast(30).sorted()
                        val idxDiff = (recentDiffs.size * 0.9).toInt().coerceIn(0, recentDiffs.size - 1)
                        state.absDiffTrigger = (recentDiffs[idxDiff] + 3).coerceAtMost(35)
                    }
                }
                
                state.lastPhash = currentPhash
                state.lastDiff = currentDiff
                
                if (camera.isOnline != 1) {
                    database.cameraDao().updateCamera(camera.copy(isOnline = 1, status = "online"))
                }
                
                logger.d("CameraSkill", "📷 Camera ${camera.id} scanned, hasChange=$isSuddenChange")
                
                updateDiagnostics()
                
                return@withLock mapOf(
                    "cameraId" to camera.id,
                    "success" to true,
                    "hasChange" to isSuddenChange,
                    "isSuspicious" to isSuspicious,
                    "aiComment" to (aiComment ?: "No analysis"),
                    "diff" to currentDiff,
                    "delta" to delta,
                    "drift" to drift,
                    "deltaTrigger" to deltaTrigger,
                    "absDiffTrigger" to absDiffTrigger
                )
                
            } catch (e: Exception) {
                logger.e("CameraSkill", "Error in processImageWithLearning: ${e.message}", e)
                return@withLock mapOf(
                    "cameraId" to camera.id,
                    "success" to false,
                    "error" to (e.message ?: "Unknown error")
                )
            }
        }
    }
    
    private suspend fun scanWithLearning(camera: CameraConfigEntity, isSmartMode: Boolean): Map<String, Any> {
        // ⚠️ KHÔNG lock mutex ở đây — processImageWithLearning() đã tự lock rồi.
        // Mutex của Kotlin không reentrant, lock 2 lần liên tiếp => deadlock vĩnh viễn.
        try {
            val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
            if (imageBytes == null) {
                handleOfflineCamera(camera)
                recordOffline(camera.id)
                return mapOf(
                    "cameraId" to camera.id,
                    "success" to false,
                    "error" to "Cannot fetch snapshot"
                )
            }

            return processImageWithLearning(camera, imageBytes, isSmartMode)

        } catch (e: Exception) {
            logger.e("CameraSkill", "Error in scanWithLearning: ${e.message}", e)
            return mapOf(
                "cameraId" to camera.id,
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    private suspend fun scanForDailyReport(camera: CameraConfigEntity): Map<String, Any> {
        return try {
            val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
            if (imageBytes == null) {
                return mapOf(
                    "cameraId" to camera.id,
                    "success" to false,
                    "error" to "Cannot fetch snapshot for daily report"
                )
            }
            
            val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
            val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else DEFAULT_AI_PROMPT
            val aiComment = try {
                withTimeout(20_000L) {
                    groqClient.analyzeImage(optimizedBytes, prompt)
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("CameraSkill", "⏱️ Groq timeout (20s) camera=${camera.id} (daily report)")
                "Không thể phân tích (AI timeout)"
            }
            
            mapOf(
                "cameraId" to camera.id,
                "success" to true,
                "aiComment" to aiComment,
                "imageBytes" to optimizedBytes
            )
            
        } catch (e: Exception) {
            mapOf(
                "cameraId" to camera.id,
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    private suspend fun handleOfflineCamera(camera: CameraConfigEntity) {
        if (camera.isOnline != 0) {
            database.cameraDao().updateCamera(camera.copy(isOnline = 0, status = "offline"))
            notificationSkill.sendNotification(
                title = "Camera ${camera.customername} mất kết nối",
                message = "Không thể kết nối đến camera. Vui lòng kiểm tra!"
            )
            sendAdminAlert(camera, "Không thể kết nối đến camera. Vui lòng kiểm tra đường truyền!")
        }
    }
    
    private suspend fun sendAdminAlert(camera: CameraConfigEntity, reason: String) {
        val subject = "🚨 [HỆ THỐNG] LỖI CAMERA: ${camera.customername} (${camera.id})"
        val body = """
            <html>
            <body>
                <h2 style="color: #dc2626;">🚨 CẢNH BÁO SỰ CỐ VẬN HÀNH CAMERA</h2>
                <p>Hệ thống phát hiện sự cố tại thiết bị camera giám sát thửa đất:</p>
                <table>
                    <tr><td><strong>Tên Camera:</strong></td><td>${camera.customername}</td></tr>
                    <tr><td><strong>Mã Camera:</strong></td><td>${camera.id}</td></tr>
                    <tr><td><strong>Mã Khách hàng:</strong></td><td>${camera.customerId}</td></tr>
                    <tr><td><strong>Thời gian:</strong></td><td>${DATETIME_FORMATTER.format(Instant.now())}</td></tr>
                </table>
                <div style="background: #fef2f2; padding: 10px; border-left: 4px solid #dc2626;">
                    <strong>Chi tiết lỗi:</strong><br>$reason
                </div>
                <p>Vui lòng kiểm tra hạ tầng đường truyền camera.</p>
            </body>
            </html>
        """.trimIndent()
        
        val adminEmail = "admin@aichatvn.com"
        if (adminEmail.isNotBlank()) {
            emailSkill.sendEmail(adminEmail, subject, body, null)
        }
    }
    
    // ==================== CRUD METHODS ====================
    
    suspend fun saveCameraConfig(config: Map<String, Any>): PluginResult {
        return try {
            val id = config["id"] as? String ?: return PluginResult.Failure("Missing camera id")
            val existing = database.cameraDao().getCameraById(id)

            val camera = CameraConfigEntity(
                id = id,
                customerId = config["customerId"] as? String ?: existing?.customerId ?: "",
                customername = config["customername"] as? String ?: existing?.customername ?: "",
                customeremail = config["customeremail"] as? String ?: existing?.customeremail ?: "",
                snapshoturl = config["snapshoturl"] as? String ?: existing?.snapshoturl ?: "",
                landinfo = config["landinfo"] as? String ?: existing?.landinfo,
                snapshotPath = existing?.snapshotPath,
                timestamp = System.currentTimeMillis(),
                status = config["status"] as? String ?: existing?.status ?: "online",
                isOnline = (config["isOnline"] as? Int) ?: (config["isOnline"] as? Number)?.toInt() ?: existing?.isOnline ?: 1,
                manualOff = (config["manualOff"] as? Int) ?: (config["manualOff"] as? Number)?.toInt() ?: existing?.manualOff ?: 0,
                aiPrompt = config["aiPrompt"] as? String ?: existing?.aiPrompt ?: "",
                aiPositiveKeywords = config["aiPositiveKeywords"] as? String ?: existing?.aiPositiveKeywords ?: "",
                aiNegativeKeywords = config["aiNegativeKeywords"] as? String ?: existing?.aiNegativeKeywords ?: ""
            )
            
            database.cameraDao().insertCamera(camera)
            
            val existingSetting = database.cameraDao().getCustomerSetting(camera.customerId)
            if (existingSetting == null && camera.customerId.isNotEmpty()) {
                val setting = CustomerSettingEntity(
                    customerId = camera.customerId,
                    smartMode = 0,
                    isActive = 1,
                    updatedAt = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis()
                )
                database.cameraDao().insertCustomerSetting(setting)
            }
            
            if (!learningStates.containsKey(camera.id)) {
                learningStates[camera.id] = CameraLearningState()
                circuitBreakers[camera.id] = CircuitBreakerState()
            }
            
            PluginResult.Success(mapOf("message" to "Camera saved"))
            
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveCameraConfig error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Save camera failed")
        }
    }
    
    suspend fun deleteCamera(cameraId: String): PluginResult {
        return try {
            database.cameraDao().deleteCamera(cameraId)
            learningStates.remove(cameraId)
            circuitBreakers.remove(cameraId)
            pendingResets.remove(cameraId)
            dailyEvents.remove(cameraId)
            PluginResult.Success(mapOf("message" to "Camera deleted"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Delete camera failed")
        }
    }
    
    suspend fun deleteCustomer(customerId: String): PluginResult {
        return try {
            val cameras = database.cameraDao().getCamerasByCustomer(customerId)
            database.cameraDao().deleteCamerasByCustomer(customerId)
            database.cameraDao().deleteCustomerSetting(customerId)
            
            cameras.forEach { camera ->
                learningStates.remove(camera.id)
                circuitBreakers.remove(camera.id)
                pendingResets.remove(camera.id)
                dailyEvents.remove(camera.id)
            }
            
            PluginResult.Success(mapOf("message" to "Customer deleted"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Delete customer failed")
        }
    }
    
    suspend fun setSmartMode(customerId: String, enabled: Boolean): PluginResult {
        return try {
            database.cameraDao().updateSmartMode(customerId, enabled, System.currentTimeMillis())
            PluginResult.Success(mapOf("message" to "Smart mode updated"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Update smart mode failed")
        }
    }
    
    suspend fun setCustomerActive(customerId: String, active: Boolean): PluginResult {
        return try {
            database.cameraDao().updateActiveStatus(customerId, active, System.currentTimeMillis())
            PluginResult.Success(mapOf("message" to "Customer status updated"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Update customer status failed")
        }
    }
    
    suspend fun getCamerasPaginated(page: Int, pageSize: Int): PluginResult {
        return try {
            val cameras = database.cameraDao().getActiveCameras()
            val start = (page - 1) * pageSize
            val end = minOf(start + pageSize, cameras.size)
            val paginated = if (start < cameras.size) cameras.subList(start, end) else emptyList()
            
            PluginResult.Success(
                mapOf(
                    "cameras" to paginated,
                    "total" to cameras.size,
                    "page" to page,
                    "pageSize" to pageSize
                )
            )
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Get cameras failed")
        }
    }
    
    fun observeCameras() = database.cameraDao().getAllCamerasFlow()

    suspend fun testCameraUrl(snapshotUrl: String): PluginResult {
        return try {
            logger.i("CameraSkill", "🧪 testCameraUrl: $snapshotUrl")
            val imageBytes = snapshotFetcher.fetchSnapshot(snapshotUrl)

            if (imageBytes == null) {
                logger.w("CameraSkill", "🧪 testCameraUrl: fetch trả về null (URL sai hoặc camera offline)")
                return PluginResult.Failure("Không thể fetch ảnh từ URL này. Kiểm tra: URL đúng định dạng? Camera online? Network ok?")
            }

            val optimized = imageHashTool.optimizeImage(imageBytes)
            val phash = imageHashTool.calculatePhash(optimized)
            logger.i("CameraSkill", "🧪 testCameraUrl OK: ${optimized.size} bytes, phash=$phash")

            PluginResult.Success(
                mapOf(
                    "imageBytes" to optimized,
                    "originalSize" to imageBytes.size,
                    "optimizedSize" to optimized.size,
                    "phash" to phash,
                    "message" to "Camera fetch OK — ${optimized.size} bytes"
                )
            )
        } catch (e: Exception) {
            logger.e("CameraSkill", "🧪 testCameraUrl error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Test camera URL failed")
        }
    }

    fun resetCircuitBreaker(cameraId: String) {
        circuitBreakers[cameraId] = CircuitBreakerState()
        learningStates[cameraId] = CameraLearningState()
        logger.i("CameraSkill", "🔄 Circuit Breaker reset manually for camera $cameraId")
    }

    fun resetAllCircuitBreakers() {
        circuitBreakers.keys.forEach { id ->
            circuitBreakers[id] = CircuitBreakerState()
        }
        logger.i("CameraSkill", "🔄 All Circuit Breakers reset manually")
    }

    fun getDiagnostics(): Map<String, Any> = _diagnostics.value
    
    private fun updateDiagnostics() {
        scope.launch {
            val stats = learningStates.mapValues { (cameraId, state) ->
                val cb = circuitBreakers[cameraId]
                mapOf(
                    "samples" to state.falseDeltas.size,
                    "realEvents" to state.realEvents,
                    "deltaTrigger" to state.deltaTrigger,
                    "absDiffTrigger" to state.absDiffTrigger,
                    "baselineSize" to state.baselineWindow.size,
                    "inCooldown" to (state.cooldownUntil > System.currentTimeMillis()),
                    "circuitBreakerOpen" to (cb?.isOpen ?: false),
                    "offlineCount" to (cb?.offlineCount ?: 0),
                    "pendingReset" to pendingResets.containsKey(cameraId)
                )
            }
            _diagnostics.value = stats
        }
    }
    
    // ==================== ALERT HELPER METHODS ====================
    
    private suspend fun saveAlertToHistory(
        camera: CameraConfigEntity,
        aiComment: String,
        imageBytes: ByteArray?,
        diff: Int,
        deltaTrigger: Int,
        absDiffTrigger: Int,
        emailSent: Boolean
    ) {
        try {
            val alertId = UUID.randomUUID().toString()
            val imagePath = imageBytes?.let { saveAlertImage(alertId, it) }

            val alert = AlertEntity(
                id = alertId,
                cameraId = camera.id,
                customerId = camera.customerId,
                cameraName = camera.customername,
                timestamp = System.currentTimeMillis(),
                aiComment = aiComment,
                diff = diff,
                deltaTrigger = deltaTrigger,
                absDiffTrigger = absDiffTrigger,
                imagePath = imagePath,
                emailSent = if (emailSent) 1 else 0,
                isSuspicious = 1,
                isRead = 0
            )
            database.alertDao().insertAlert(alert)
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveAlertToHistory error: ${e.message}", e)
        }
    }

    private fun saveAlertImage(alertId: String, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "alert_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$alertId.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveAlertImage error: ${e.message}", e)
            null
        }
    }

    private fun buildAlertEmailBody(camera: CameraConfigEntity, analysis: String): String {
        return """
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2 style="color: #dc2626;">🚨 CẢNH BÁO AN NINH KHẨN CẤP</h2>
                <p>Hệ thống phát hiện biến động bất thường tại camera <strong>${camera.customername}</strong></p>
                <table style="margin: 15px 0;">
                    <tr><td><strong>Vị trí:</strong></td><td>${camera.landinfo ?: "Không xác định"}</td></tr>
                    <tr><td><strong>Thời gian:</strong></td><td>${DATETIME_FORMATTER.format(Instant.now())}</td></tr>
                </table>
                <div style="background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107;">
                    <strong>🤖 Phân tích AI:</strong><br>
                    $analysis
                </div>
                <p><small>Hình ảnh bằng chứng được đính kèm email này.</small></p>
            </body>
            </html>
        """.trimIndent()
    }
}